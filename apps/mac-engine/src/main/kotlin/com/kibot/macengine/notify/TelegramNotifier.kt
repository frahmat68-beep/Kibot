package com.kibot.macengine.notify

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

class TelegramNotifier(
    private val botToken: String?,
    private val chatId: String?,
) {
    private val enabled = !botToken.isNullOrBlank() && !chatId.isNullOrBlank()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    fun sendProfitAlert(
        pair: String,
        engineType: String,
        profitPct: Double,
        profitIdr: Double,
        bucketType: String,
    ) {
        if (!enabled) return
        val text = buildString {
            appendLine("✅ [KiDax] TAKE PROFIT!")
            appendLine("Pair: ${pair.uppercase()}")
            appendLine("Engine: $engineType")
            appendLine("Profit: +${"%.2f".format(profitPct)}% (Rp ${"%.0f".format(profitIdr)})")
            appendLine("Bucket: $bucketType")
        }
        sendAsync(text)
    }

    private fun sendAsync(message: String) {
        val token = botToken ?: return
        val chat = chatId ?: return
        val encoded = URLEncoder.encode(message, StandardCharsets.UTF_8)
        val url = "https://api.telegram.org/bot${token}/sendMessage?chat_id=${chat}&text=${encoded}"

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()

        CompletableFuture.runAsync {
            runCatching {
                httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            }
        }
    }

    companion object {
        fun fromEnv(): TelegramNotifier = TelegramNotifier(
            botToken = System.getenv("TELEGRAM_BOT_TOKEN")
                ?: System.getenv("KIBOT_TELEGRAM_BOT_TOKEN"),
            chatId = System.getenv("TELEGRAM_CHAT_ID")
                ?: System.getenv("KIBOT_TELEGRAM_CHAT_ID"),
        )
    }
}
