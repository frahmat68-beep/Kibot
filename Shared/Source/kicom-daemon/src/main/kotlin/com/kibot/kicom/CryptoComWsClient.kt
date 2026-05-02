package com.kibot.kicom

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class WsRequest(
    val id: Long,
    val method: String,
    val params: JsonObject? = null,
    val nonce: Long? = null
)

@Serializable
data class WsTickerData(
    val i: String, // instrument_name
    val a: Double? = null, // last price (sometimes in 'a', sometimes 'k')
    val b: Double? = null,
    val k: Double? = null, // bid
    val t: Long? = null    // timestamp
)

class CryptoComWsClient(
    private val httpClient: HttpClient,
    private val symbols: List<String>
) {
    private val logger = LoggerFactory.getLogger(CryptoComWsClient::class.java)
    private val isRunning = AtomicBoolean(true)
    private val _tickerFlow = MutableSharedFlow<WsTickerData>(extraBufferCapacity = 100)
    val tickerFlow = _tickerFlow.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isRunning.get()) {
                try {
                    connectAndSubscribe()
                } catch (e: Exception) {
                    logger.error("WebSocket error, reconnecting in 5s: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    private suspend fun connectAndSubscribe() {
        httpClient.webSocket(
            method = HttpMethod.Get,
            host = "stream.crypto.com",
            path = "/v2/market"
        ) {
            logger.info("Connected to Crypto.com WebSocket")

            // subscribe to tickers
            val subscribeReq = WsRequest(
                id = System.currentTimeMillis(),
                method = "public/subscribe",
                params = buildJsonObject {
                    put("channels", JsonArray(symbols.map { JsonPrimitive("ticker.$it") }))
                }
            )
            send(json.encodeToString(subscribeReq))

            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val msg = json.parseToJsonElement(text).jsonObject
                    
                    val method = msg["method"]?.jsonPrimitive?.content
                    if (method == "public/heartbeat") {
                        val id = msg["id"]?.jsonPrimitive?.longOrNull ?: 0L
                        val pong = buildJsonObject {
                            put("id", id)
                            put("method", "public/respond-heartbeat")
                        }
                        send(json.encodeToString(pong))
                        continue
                    }

                    val result = msg["result"]?.jsonObject
                    val channel = result?.get("channel")?.jsonPrimitive?.content
                    if (channel == "ticker") {
                        val dataArray = result["data"]?.jsonArray
                        dataArray?.forEach { dataElement ->
                            try {
                                val data = json.decodeFromJsonElement<WsTickerData>(dataElement)
                                _tickerFlow.emit(data)
                            } catch (e: Exception) {
                                // logger.warn("Failed to parse ticker data: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
    }
}
