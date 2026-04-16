package com.kicryp.aisupport

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

internal expect fun createPlatformHttpClient(json: Json, timeoutMillis: Long): HttpClient
