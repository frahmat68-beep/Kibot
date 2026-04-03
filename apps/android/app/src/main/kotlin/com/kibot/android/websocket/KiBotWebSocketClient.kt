package com.kibot.android.websocket

import android.util.Log
import com.google.gson.Gson
import com.kibot.android.data.BotStatus
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class KiBotWebSocketClient(
    private val wsUrl: String,
    private val onStatusUpdate: (BotStatus) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private var pingJob: Job? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var isConnecting = false
    private var lastPingTime = 0L
    private val PING_INTERVAL_MS = 5000L // 5 seconds

    fun connect() {
        if (webSocket != null && !isConnecting) {
            Log.d(TAG, "Already connected or connecting")
            return
        }

        isConnecting = true
        scope.launch {
            try {
                val request = Request.Builder().url(wsUrl).build()
                webSocket = client.newWebSocket(request, WebSocketListener())
                Log.d(TAG, "Connecting to $wsUrl")
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}", e)
                onError("Connection failed: ${e.message}")
                isConnecting = false
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        pingJob?.cancel()
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        isConnecting = false
        scope.coroutineContext.cancelChildren()
    }

    fun requestStatus() {
        val now = System.currentTimeMillis()
        if (now - lastPingTime < PING_INTERVAL_MS) {
            return
        }
        lastPingTime = now
        
        webSocket?.send("""{"action":"getStatus"}""")
        Log.d(TAG, "Sent status request")
    }

    fun sendCommand(command: String) {
        val json = """{"action":"command","value":"$command"}"""
        webSocket?.send(json)
        Log.d(TAG, "Sent command: $command")
    }

    private inner class WebSocketListener : okhttp3.WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            Log.d(TAG, "WebSocket opened")
            isConnecting = false
            onConnectionChange(true)
            
            // Start periodic ping
            pingJob = scope.launch {
                while (isActive) {
                    delay(PING_INTERVAL_MS)
                    requestStatus()
                }
            }
            
            // Request initial status
            requestStatus()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Message received: $text")
            try {
                val status = gson.fromJson(text, BotStatus::class.java)
                onStatusUpdate(status)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message: ${e.message}", e)
                onError("Parse error: ${e.message}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d(TAG, "Binary message received")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
            this@KiBotWebSocketClient.webSocket = null
            onConnectionChange(false)
            pingJob?.cancel()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            this@KiBotWebSocketClient.webSocket = null
            onConnectionChange(false)
            pingJob?.cancel()
            
            // Auto-reconnect after 5 seconds
            scope.launch {
                delay(5000)
                connect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            isConnecting = false
            onConnectionChange(false)
            onError("Connection lost: ${t.message}")
            pingJob?.cancel()
            
            // Auto-reconnect after 5 seconds
            scope.launch {
                delay(5000)
                connect()
            }
        }
    }

    companion object {
        private const val TAG = "KiBotWebSocket"
    }
}
