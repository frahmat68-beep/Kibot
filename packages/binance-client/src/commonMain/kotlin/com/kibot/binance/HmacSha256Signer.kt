package com.kibot.binance

internal expect object HmacSha256Signer {
    fun sign(secret: String, payload: String): String
}
