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

    @Test
    fun `formats decimals for indodax precision`() {
        assertEquals("123.12345678", formatIndodaxDecimal("123.123456789123"))
        assertEquals("0.00000012", formatIndodaxDecimal("1.23456789E-7"))
        assertEquals("12345", formatIndodaxDecimal("1.2345e4"))
        assertEquals("0", formatIndodaxDecimal("0.0000000001"))
    }

    @Test
    fun `normalizes trade amount for decimal rejection retry`() {
        assertEquals("24588", normalizeIndodaxTradeAmount("24588.000000"))
        assertEquals("12345", normalizeIndodaxTradeAmount("1.2345e4"))
        assertEquals(null, normalizeIndodaxTradeAmount("0.001"))
    }
}
