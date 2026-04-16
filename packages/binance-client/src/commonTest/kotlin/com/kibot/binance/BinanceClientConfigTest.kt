package com.kibot.binance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinanceClientConfigTest {
    @Test
    fun `default public market endpoints point to spot`() {
        val config = BinanceClientConfig()

        assertEquals("https://api.binance.com/api/v3/ping", config.publicRestUrl("ping"))
        assertEquals("https://api.binance.com/api/v3/ticker/24hr", config.publicRestUrl("ticker/24hr"))
        assertEquals("wss://stream.binance.com:9443/ws/btcusdt@ticker", config.publicWebSocketStreamUrl("btcusdt@ticker"))
    }

    @Test
    fun `private endpoints remain configurable and normalized`() {
        val config = BinanceClientConfig(
            privateBaseUrl = "https://api.binance.com/",
            privateRestPathPrefix = "/api/v3/",
        )

        assertEquals("https://api.binance.com/api/v3/order", config.privateRestUrl("/order"))
        assertEquals("https://api.binance.com/api/v3/openOrders", config.privateRestUrl("openOrders"))
    }

    @Test
    fun `public rest normalization avoids double slash bugs`() {
        val config = BinanceClientConfig(
            publicBaseUrl = "https://fapi.binance.com/",
            publicRestPathPrefix = "/fapi/v1/",
        )

        val url = config.publicRestUrl("/ticker/24hr")
        assertEquals("https://fapi.binance.com/fapi/v1/ticker/24hr", url)
        assertTrue("fapi.binance.com//" !in url)
    }
}
