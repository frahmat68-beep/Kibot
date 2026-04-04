package com.kibot.binance

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
        headers.append(HttpHeaders.UserAgent, "KiBot/1.0 (+https://kibot.local)")
        headers.append(HttpHeaders.Accept, "application/json,text/plain,*/*")
    }
    install(ContentNegotiation) {
        json(json)
    }
    install(Logging) {
        logger = QuietKtorLogger
        level = LogLevel.INFO
    }
}
