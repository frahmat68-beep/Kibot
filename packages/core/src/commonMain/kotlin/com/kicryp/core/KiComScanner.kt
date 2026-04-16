package com.kicryp.core

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Serializable
data class CryptoComTickerResponse(
    val result: CryptoComTickerResult
)

@Serializable
data class CryptoComTickerResult(
    val data: List<CryptoComTickerData>
)

@Serializable
data class CryptoComTickerData(
    val i: String, // instrument_name
    val b: Double?, // best_bid
    val k: Double?, // best_ask
    val a: Double?, // last_price
    val h: Double?, // high
    val l: Double?, // low
    val v: Double?, // volume
    val c: Double?  // change
)

data class KiComSignal(
    val pairId: String,
    val cryptocomSymbol: String,
    val priceChange24hPct: Double,
    val isBullish: Boolean,
    val confidence: Double,
    val timestampMs: Long
)

/**
 * KiComScanner - Trinity v7.0 Crypto.com Consensus Scanner
 * Provides an AND-gate confirmation for Bucket A (Global Lead-Lag) trades.
 */
class KiComScanner(
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }
) {
    private val mutex = Mutex()
    private val symbolMap = mapOf(
        "btc_idr" to "BTC_USDT",
        "eth_idr" to "ETH_USDT",
        "xrp_idr" to "XRP_USDT",
        "sol_idr" to "SOL_USDT",
        "doge_idr" to "DOGE_USDT",
        "bnb_idr" to "BNB_USDT",
        "ada_idr" to "ADA_USDT",
        "xlm_idr" to "XLM_USDT",
        "trx_idr" to "TRX_USDT",
        "dot_idr" to "DOT_USDT",
        "shib_idr" to "SHIB_USDT",
        "avax_idr" to "AVAX_USDT",
        "link_idr" to "LINK_USDT",
        "uni_idr" to "UNI_USDT",
        "atom_idr" to "ATOM_USDT",
        "near_idr" to "NEAR_USDT",
        "apt_idr" to "APT_USDT",
        "sui_idr" to "SUI_USDT",
        "pepe_idr" to "PEPE_USDT",
        "bonk_idr" to "BONK_USDT",
        "floki_idr" to "FLOKI_USDT",
        "enj_idr" to "ENJ_USDT",
        "matic_idr" to "MATIC_USDT",
        "pol_idr" to "POL_USDT",
        "dusk_idr" to "DUSK_USDT",
        "fun_idr" to "FUN_USDT"
    )

    suspend fun fetchSignal(indodaxPair: String): KiComSignal? = mutex.withLock {
        val cdcSymbol = symbolMap[indodaxPair] ?: return null
        val url = "https://api.crypto.com/exchange/v1/public/get-ticker?instrument_name=$cdcSymbol"

        return try {
            val response: CryptoComTickerResponse = httpClient.get(url).body()
            val ticker = response.result.data.firstOrNull() ?: return null

            val change24h = ticker.c ?: 0.0
            val isBullish = change24h > 0.005 // > 0.5% growth

            // Confidence based on price change intensity (5% = 1.0 confidence)
            val confidence = (Math.abs(change24h) / 0.05).coerceIn(0.0, 1.0)

            KiComSignal(
                pairId = indodaxPair,
                cryptocomSymbol = cdcSymbol,
                priceChange24hPct = change24h * 100.0,
                isBullish = isBullish,
                confidence = confidence,
                timestampMs = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        httpClient.close()
    }
}
