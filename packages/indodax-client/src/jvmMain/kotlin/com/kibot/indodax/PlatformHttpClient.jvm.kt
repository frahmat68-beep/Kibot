package com.kibot.indodax

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private object QuietKtorLogger : Logger {
    override fun log(message: String) = Unit
}

internal actual fun createPlatformHttpClient(json: Json): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 8_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 8_000
    }
    defaultRequest {
        // Use browser-like User-Agent to bypass Cloudflare protection
        headers.append(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        headers.append(HttpHeaders.Accept, "application/json,text/plain,*/*")
        // Add headers that help bypass Cloudflare bot protection
        headers.append("Accept-Language", "en-US,en;q=0.9")
        headers.append("Accept-Encoding", "gzip, deflate, br")
        headers.append("Sec-Fetch-Dest", "document")
        headers.append("Sec-Fetch-Mode", "navigate")
        headers.append("Sec-Fetch-Site", "none")
        headers.append("Cache-Control", "max-age=0")
    }
    install(ContentNegotiation) {
        json(json)
    }
    install(Logging) {
        logger = QuietKtorLogger
        level = LogLevel.INFO
    }
}
