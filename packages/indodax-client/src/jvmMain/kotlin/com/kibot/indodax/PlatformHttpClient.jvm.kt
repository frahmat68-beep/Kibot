package com.kibot.indodax

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.compression.ContentEncoding
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
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 6_000
        socketTimeoutMillis = 10_000
    }
    defaultRequest {
        // Use browser-like User-Agent to bypass Cloudflare protection
        headers.append(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        headers.append(HttpHeaders.Accept, "application/json,text/plain,*/*")
        // Headers that help bypass Cloudflare bot protection
        headers.append("Accept-Language", "en-US,en;q=0.9")
        headers.append("Sec-Fetch-Dest", "document")
        headers.append("Sec-Fetch-Mode", "navigate")
        headers.append("Sec-Fetch-Site", "none")
        headers.append("Cache-Control", "max-age=0")
    }
    // ContentEncoding MUST be installed BEFORE ContentNegotiation
    // This handles automatic gzip/deflate decompression from server responses
    // Critical fix: Indodax sends gzip even if not requested, so we MUST decompress
    install(ContentEncoding) {
        gzip()
        deflate()
    }
    install(ContentNegotiation) {
        json(json)
    }
    install(Logging) {
        logger = QuietKtorLogger
        level = LogLevel.INFO
    }
}
