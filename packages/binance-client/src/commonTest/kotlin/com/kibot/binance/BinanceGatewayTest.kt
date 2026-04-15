package com.kibot.binance

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BinanceGatewayTest {
    @Test
    fun `ticker payload is selective and excludes stable self pair`() {
        val payload = buildTicker24hSymbolsPayload()

        assertTrue(payload.contains("BTCUSDT"))
        assertTrue(payload.contains("ETHUSDT"))
        assertFalse(payload.contains("USDTUSDT"))
    }
}
