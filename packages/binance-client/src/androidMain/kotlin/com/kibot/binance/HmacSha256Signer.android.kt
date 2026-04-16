package com.kibot.binance

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal actual object HmacSha256Signer {
    actual fun sign(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
