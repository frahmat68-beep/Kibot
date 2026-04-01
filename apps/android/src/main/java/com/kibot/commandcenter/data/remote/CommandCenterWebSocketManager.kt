package com.kibot.commandcenter.data.remote

import com.kibot.commandcenter.data.model.ConnectionState
import com.kibot.commandcenter.data.model.CommandCenterServerStateWire
import com.kibot.commandcenter.data.repository.CommandCenterStore
import com.kibot.commandcenter.data.repository.CommandCenterSnapshotCacheStore
import com.kibot.shared.models.CommandCenterCommandReply
import com.kibot.shared.models.CommandCenterCommandRequest
import com.kibot.shared.models.CommandCenterLiveSnapshot
import com.kibot.shared.models.CommandCenterWsEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.util.Log
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class CommandCenterWebSocketManager(
    private val scope: CoroutineScope,
    private val store: CommandCenterStore,
    private val snapshotCacheStore: CommandCenterSnapshotCacheStore,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = HttpClient {
        install(WebSockets)
    }
    private val sequence = AtomicInteger(1)
    private val jobs = mutableListOf<Job>()

    fun start(kidaxWsUrl: String) {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch { connectLoop("kidax", "KiDax", kidaxWsUrl) }
    }

    suspend fun stop() {
        jobs.forEach { it.cancelAndJoin() }
        jobs.clear()
        client.close()
    }

    suspend fun sendCommand(serverWsUrl: String, command: String, argument: String? = null): CommandCenterCommandReply {
        var reply = CommandCenterCommandReply(accepted = false, message = "No reply")
        client.webSocket(
            method = HttpMethod.Get,
            host = serverWsUrl.toUriHost(),
            port = serverWsUrl.toUriPort(),
            path = serverWsUrl.toUriPath(),
        ) {
            val request = CommandCenterCommandRequest(
                command = command,
                argument = argument,
                idempotencyKey = "${command}_${sequence.getAndIncrement()}",
                issuedAtEpochMs = System.currentTimeMillis(),
            )
            send(json.encodeToString(request))
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                val envelope = runCatching { json.decodeFromString<CommandCenterWsEnvelope>(text) }.getOrNull()
                if (envelope is CommandCenterWsEnvelope.Reply) {
                    reply = envelope.reply
                    break
                }
            }
        }
        return reply
    }

    private suspend fun connectLoop(serverKey: String, label: String, wsUrl: String) {
        while (currentCoroutineContext().isActive) {
            try {
                connectServer(serverKey, label, wsUrl)
                delay(3_000L)
            } catch (e: Throwable) {
                store.appendConsole(com.kibot.commandcenter.data.model.ConsoleRole.ERROR, "WS error [$label]: ${e.message ?: "unknown"}")
                delay(3_000L)
            }
        }
    }

    private suspend fun connectServer(serverKey: String, label: String, wsUrl: String) {
        client.webSocket(
            method = HttpMethod.Get,
            host = wsUrl.toUriHost(),
            port = wsUrl.toUriPort(),
            path = wsUrl.toUriPath(),
        ) {
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                when (val envelope = runCatching { json.decodeFromString<CommandCenterWsEnvelope>(text) }.getOrNull()) {
                    is CommandCenterWsEnvelope.Snapshot -> {
                        val snapshot = envelope.snapshot
                        store.updateServer(serverKey, label, snapshot, ConnectionState.CONNECTED)
                        snapshotCacheStore.save(serverKey, snapshot)
                    }
                    is CommandCenterWsEnvelope.Reply -> {
                        store.appendConsole(com.kibot.commandcenter.data.model.ConsoleRole.SYSTEM, "${label}: ${envelope.reply.message}")
                    }
                    is CommandCenterWsEnvelope.Ping -> Unit
                    null -> Unit
                }
            }
        }
    }
}

private fun String.toUriHost(): String {
    val normalized = removePrefix("ws://").removePrefix("wss://").removePrefix("http://").removePrefix("https://")
    return normalized.substringBefore(':').substringBefore('/')
}

private fun String.toUriPort(): Int {
    val normalized = removePrefix("ws://").removePrefix("wss://").removePrefix("http://").removePrefix("https://")
    return normalized.substringAfter(':', "80").substringBefore('/').toIntOrNull() ?: 80
}

private fun String.toUriPath(): String {
    val normalized = removePrefix("ws://").removePrefix("wss://").removePrefix("http://").removePrefix("https://")
    val path = normalized.substringAfter('/', "")
    return if (path.isBlank()) "/" else "/$path"
}

private fun String.toHttpStateUrl(): String {
    val normalized = removePrefix("ws://").removePrefix("wss://").removePrefix("http://").removePrefix("https://")
    val host = normalized.substringBefore(':').substringBefore('/')
    val port = normalized.substringAfter(':', "80").substringBefore('/').toIntOrNull() ?: 80
    return "http://$host:$port/api/state"
}
