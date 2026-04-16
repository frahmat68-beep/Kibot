package com.kicryp.indodax

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal actual object HmacSha512Signer {
    actual fun sign(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA512"))
        return mac.doFinal(payload.toByteArray()).joinToString("") { byte ->
            "%02x".format(byte)
        }
    }
}

