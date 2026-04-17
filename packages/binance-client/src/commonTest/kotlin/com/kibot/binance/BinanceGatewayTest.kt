package com.kibot.binance

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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

    @Test
    fun `ticker payload batches stay selective and bounded`() {
        val batches = buildTicker24hSymbolBatches(batchSize = 8)

        assertTrue(batches.isNotEmpty())
        assertTrue(batches.all { it.startsWith("[") && it.endsWith("]") })
        assertTrue(batches.first().contains("BTCUSDT"))
        assertTrue(batches.none { it.contains("USDTUSDT") })
        assertEquals(buildTicker24hSymbolsPayload(), buildTicker24hSymbolBatches(batchSize = 999).single())
    }

    @Test
    fun `invalid ticker response falls back to affected batch symbols`() {
        val batch = """["BTCUSDT","FUNUSDT"]"""

        val result = extractInvalidSymbols(
            BinanceTickerBatchException(
                message = """{"code":-1121,"msg":"Invalid symbol."}""",
                symbols = decodeTickerSymbols(batch),
                code = -1121,
            ),
            batch,
        )

        assertContentEquals(listOf("BTCUSDT", "FUNUSDT"), result)
    }
}
