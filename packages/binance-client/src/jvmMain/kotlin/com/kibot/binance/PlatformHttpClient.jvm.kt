package com.kibot.binance

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
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 8_000
        socketTimeoutMillis = 15_000
    }
    defaultRequest {
        headers.append(HttpHeaders.UserAgent, "KiCryp/1.0 (+https://kibot.local)")
        headers.append(HttpHeaders.Accept, "application/json,text/plain,*/*")
    }
    // ContentEncoding MUST be installed BEFORE ContentNegotiation
    // This handles automatic gzip/deflate decompression from server responses
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
