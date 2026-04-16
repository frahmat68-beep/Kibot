package com.kibot.kicom

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory

fun main() {
    val logger = LoggerFactory.getLogger("KiComDaemon")
    logger.info("Starting KiCom Daemon v7.0 (Global Lead-Lag Scanner)")

    val symbolMap = mapOf(
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

    val httpClient = HttpClient(CIO) {
        install(WebSockets)
        install(ContentNegotiation) {
            json()
        }
    }

    val reverseMap = symbolMap.entries.associate { it.value to it.key }
    val wsClient = CryptoComWsClient(httpClient, symbolMap.values.toList())
    val analyzer = MomentumAnalyzer(thresholdPct = 0.5, windowSeconds = 60)
    val emitter = SignalUdpEmitter()

    runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        
        // Start WebSocket loop
        wsClient.start(scope)

        // Ticker -> Analyzer -> Emitter pipeline
        val analyzerJob = scope.launch {
            wsClient.tickerFlow
                .onEach { ticker -> analyzer.processTick(ticker) }
                .collect()
        }

        val emitterJob = scope.launch {
            analyzer.momentumFlow
                .onEach { momentum ->
                    val indodaxPair = reverseMap[momentum.symbol]
                    if (indodaxPair != null) {
                        emitter.emit(momentum, indodaxPair)
                    }
                }
                .collect()
        }

        logger.info("KiCom Daemon Mesh Active: Monitoring ${symbolMap.size} pairs on Crypto.com")

        // Keep main alive
        while (true) {
            delay(10000)
        }
    }
}
