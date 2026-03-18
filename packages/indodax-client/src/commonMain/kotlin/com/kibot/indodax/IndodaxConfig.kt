package com.kibot.indodax

import kotlinx.serialization.Serializable

@Serializable
data class IndodaxCredentials(
    val apiKey: String,
    val apiSecret: String,
)

@Serializable
data class IndodaxClientConfig(
    val publicBaseUrl: String = "https://indodax.com/api",
    val privateBaseUrl: String = "https://indodax.com/tapi",
    val tradeApiV2BaseUrl: String = "https://tapi.indodax.com",
    val publicWebSocketUrl: String = "wss://ws1.indodax.com/ws",
    val privateWebSocketUrl: String = "wss://ws1.indodax.com/ws/private",
    val receiveWindowMillis: Long = 15_000,
    val defaultFeePct: Double = 0.003,
)

data class SignedPrivateRequest(
    val body: Map<String, String>,
    val headers: Map<String, String>,
)
