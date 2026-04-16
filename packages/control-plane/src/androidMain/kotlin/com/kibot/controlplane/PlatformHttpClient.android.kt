package com.kibot.controlplane

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private object QuietKtorLogger : Logger {
    override fun log(message: String) = Unit
}

internal actual fun createPlatformHttpClient(json: Json): HttpClient = HttpClient(CIO) {
    expectSuccess = true
    install(ContentNegotiation) {
        json(json)
    }
    install(Logging) {
        logger = QuietKtorLogger
        level = LogLevel.INFO
    }
}
