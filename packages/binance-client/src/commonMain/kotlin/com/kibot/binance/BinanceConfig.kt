package com.kibot.binance

import kotlinx.serialization.Serializable

@Serializable
data class BinanceCredentials(
    val apiKey: String,
    val apiSecret: String,
)

@Serializable
data class BinanceClientConfig(
    val publicBaseUrl: String = "https://api.binance.com",
    val privateBaseUrl: String = "https://api.binance.com",
    val receiveWindowMillis: Long = 10_000,
    val defaultFeePct: Double = 0.001,
    val primaryQuoteAsset: String = "usdt",
    val shadowMode: Boolean = false,
)
