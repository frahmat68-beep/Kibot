package com.kibot.indodax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndodaxPrivateRequestFactoryTest {
    @Test
    fun `builds signed request with nonce and method`() {
        val request = IndodaxPrivateRequestFactory(
            IndodaxCredentials(
                apiKey = "key-1",
                apiSecret = "secret-1",
            ),
        ).build(
            method = "getInfo",
            nonce = 42L,
        )

        assertEquals("42", request.body["nonce"])
        assertEquals("getInfo", request.body["method"])
        assertEquals("key-1", request.headers["Key"])
        assertTrue(request.headers["Sign"]?.isNotBlank() == true)
    }
}
