package com.kicryp.indodax

class IndodaxPrivateRequestFactory(
    private val credentials: IndodaxCredentials,
) {
    fun build(
        method: String,
        nonce: Long,
        params: Map<String, String> = emptyMap(),
    ): SignedPrivateRequest {
        val body = linkedMapOf(
            "method" to method,
            "nonce" to nonce.toString(),
        ).apply { putAll(params) }

        val payload = body.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }

        return SignedPrivateRequest(
            body = body,
            headers = mapOf(
                "Key" to credentials.apiKey,
                "Sign" to HmacSha512Signer.sign(credentials.apiSecret, payload),
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
        )
    }
}

internal expect object HmacSha512Signer {
    fun sign(secret: String, payload: String): String
}

internal fun String.urlEncode(): String = buildString {
    for (character in this@urlEncode) {
        when (character) {
            ' ' -> append("%20")
            '+' -> append("%2B")
            '&' -> append("%26")
            '=' -> append("%3D")
            else -> append(character)
        }
    }
}
