package com.kibot.indodax

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

internal expect fun createPlatformHttpClient(json: Json): HttpClient
