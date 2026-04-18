package com.kibot.macengine.runtime

import com.kibot.core.*
import com.kibot.core.managers.*
import com.kibot.shared.models.*
import com.kibot.shared.utils.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MacEngineDaemon(
    private val config: EngineConfig,
    private val controlPlane: ControlPlaneClient,
    private val exchange: ExchangeClient,
    private val repository: EngineRepository,
    private val clock: Clock = Clock.System,
) {
    private val logger = LoggerFactory.getLogger(MacEngineDaemon::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val daemonScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    
    private val partialTpManager = PartialTakeProfitManager()
    private val profitLockManager = ProfitLockManager()
    private val capitalAllocationManager = CapitalAllocationManager()
    private val entryPolicy = AlwaysInvestedPolicy()
    private val chartAnalyzer = ChartAnalyzer()
    
    // Safety & Risk State
    @Volatile private var dailyRisk = DailyRiskSnapshot()
    @Volatile private var lastRiskAuditAt: Instant? = null
    @Volatile private var isHardStopActive = false
    
    // Whitelist & Pair State
    private val pairWhitelistManager = PairWhitelistManager(daemonScope)
    private val activePositions = java.util.concurrent.ConcurrentHashMap<String, ActivePositionWire>()
    private val symbolConfigByPair = java.util.concurrent.ConcurrentHashMap<String, SymbolConfig>()
    
    // System Heartbeat
    @Volatile private var lastHeartbeatAt: Instant? = null
    @Volatile private var registered = false
    @Volatile private var lastObservedLeaseTerm: LeaseTerm? = null
    
    // UDP Signaling (Lead-Lag)
    private val kinanceAckPort = 8789
    private var udpSocket: DatagramSocket? = null
    
    // Non-critical Telemetry Buffer
    private var nonCriticalControlPlaneBuffer = NonCriticalControlPlaneBufferSnapshot("", "")

    suspend fun start() {
        logger.info("[BOOT] Initializing KiBot Trinity Ultimate Daemon...")
        
        // 1. Initial Data Load (Single Bootstrap)
        loadFromSupabase()
        
        // 2. Start Sub-systems
        startUdpListener()
        startRiskAuditor()
        startHeartbeat()
        startWhatIfSimulationPolling()
        scheduleDailyReset()
        
        // 3. Main Execution Loop
        runLiveLoop()
    }

    private suspend fun loadFromSupabase() {
        logger.info("[BOOT] Loading initial state from Supabase...")
        runCatching {
            val whitelist = controlPlane.fetchWhitelist()
            pairWhitelistManager.updateWhitelist(whitelist)
            
            val risk = controlPlane.fetchDailyRisk(config.controlPlane.botId, LocalDate.now())
            dailyRisk = risk ?: DailyRiskSnapshot(openingEquityIdr = config.initialEquityIdr.toString())
            
            logger.info("[BOOT] Whitelist loaded (${whitelist.size} pairs), Daily Risk synced.")
        }.onFailure {
            logger.error("[BOOT] Failed to sync with Supabase at startup", it)
        }
    }

    private fun startUdpListener() {
        scope.launch(Dispatchers.IO) {
            try {
                udpSocket = DatagramSocket(config.leadLagUdpLocalPort)
                val buffer = ByteArray(65535)
                logger.info("[UDP] Listening for Kinance signals on port ${config.leadLagUdpLocalPort}")
                
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    handleIncomingSignal(data, packet.address, packet.port)
                }
            } catch (e: Exception) {
                logger.error("[UDP] Listener error", e)
            }
        }
    }

    private suspend fun handleIncomingSignal(data: String, fromAddress: InetAddress, fromPort: Int) {
        val now = clock.now()
        val signal = runCatching { json.decodeFromString<KinanceSignal>(data) }.getOrNull() ?: return
        
        // Send ACK on dedicated port 8789
        sendUdpAck(signal.id, fromAddress)
        
        if (isHardStopActive) {
            logger.warn("[SIGNAL] Ignored: Hard-stop active")
            return
        }

        logger.info("[SIGNAL] Received ${signal.type} for ${signal.pairId}")
        processEntrySignal(signal, now)
    }

    private fun sendUdpAck(signalId: String, address: InetAddress) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val ack = json.encodeToString(KinanceAck(id = signalId, botId = config.controlPlane.botId.value))
                val bytes = ack.toByteArray()
                val packet = DatagramPacket(bytes, bytes.size, address, kinanceAckPort)
                udpSocket?.send(packet)
            }
        }
    }

    private suspend fun runLiveLoop() {
        while (isActive) {
            val now = clock.now()
            try {
                // 1. Risk Audit
                if (lastRiskAuditAt == null || (now - lastRiskAuditAt!!).inWholeSeconds > 60) {
                    performRiskAudit(now)
                }

                if (!isHardStopActive) {
                    // 2. Market Scan & Position Management
                    managePositions(now)
                }

                delay(config.executionIntervalMs)
            } catch (e: Exception) {
                logger.error("[LOOP] Critical loop error", e)
                delay(5000)
            }
        }
    }

    private suspend fun performRiskAudit(now: Instant) {
        val pnlToday = calculateDailyPnlPct()
        
        // Enforce -3% hard stop
        if (pnlToday <= -3.0) {
            if (!isHardStopActive) {
                logger.error("[RISK] DAILY HARD-STOP TRIGGERED! PnL Today: ${String.format("%.2f", pnlToday)}%")
                isHardStopActive = true
                cancelAllOpenOrders()
            }
        } else {
            isHardStopActive = false
        }
        
        lastRiskAuditAt = now
    }

    private fun calculateDailyPnlPct(): Double {
        val opening = dailyRisk.openingEquityIdr.toDoubleOrNull() ?: return 0.0
        val current = dailyRisk.currentEquityIdr.toDoubleOrNull() ?: opening
        if (opening == 0.0) return 0.0
        return ((current - opening) / opening) * 100.0
    }

    private suspend fun managePositions(now: Instant) {
        val quotes = exchange.fetchMarketQuotes()
        
        // Update Bucket Allocations
        capitalAllocationManager.allocate(dailyRisk.currentEquityIdr.toDoubleOrNull() ?: 0.0)
        
        activePositions.forEach { (pair, pos) ->
            val quote = quotes.find { it.pairId.value == pair } ?: return@forEach
            
            // 1. Partial TP Check
            val tpResult = partialTpManager.checkTpLevels(pos, quote.lastPrice.toDoubleOrZero())
            if (tpResult.shouldExecute) {
                executePartialSell(pos, tpResult.quantity, tpResult.label)
            }
            
            // 2. Trailing Stop / Emergency Exit
            checkPositionExit(pos, quote)
        }
    }

    private suspend fun processEntrySignal(signal: KinanceSignal, now: Instant) {
        val quote = exchange.fetchQuote(signal.pairId)
        
        // AlwaysInvested Fee Gate
        val decision = entryPolicy.shouldEnter(
            pairId = signal.pairId,
            quote = quote,
            config = config,
            now = now
        )
        
        if (!decision.granted) {
            logger.info("[ENTRY] Rejected ${signal.pairId}: ${decision.reason}")
            return
        }
        
        // Minimum Order Guard (10k IDR)
        val budget = calculateEntryBudget(signal)
        if (budget < 10000.0) {
            logger.warn("[ENTRY] Rejected ${signal.pairId}: Budget Rp$budget below Rp10,000 floor")
            return
        }
        
        executeBuy(signal.pairId, budget, quote.lastPrice.toDoubleOrZero())
    }

    private suspend fun executeBuy(pairId: PairId, budget: Double, price: Double) {
        val quantity = budget / price
        logger.info("[EXEC] Buying $quantity $pairId @ Rp$price")
        
        runCatching {
            val order = exchange.submitOrder(pairId, OrderSide.BUY, OrderType.MARKET, quantity)
            if (order.status == OrderStatus.FILLED) {
                registerPosition(pairId, order)
            }
        }.onFailure {
            logger.error("[EXEC] Buy failed for $pairId", it)
        }
    }

    private suspend fun executePartialSell(pos: ActivePositionWire, quantity: Double, label: String) {
        logger.info("[EXEC] Partial TP ($label): Selling $quantity ${pos.pairId}")
        runCatching {
            val order = exchange.submitOrder(pos.pairId, OrderSide.SELL, OrderType.MARKET, quantity)
            if (order.status == OrderStatus.FILLED) {
                // Profit Locking Logic (30% locked, 70% re-deployed)
                val profit = (order.price - pos.entryPrice) * quantity
                profitLockManager.onProfitRealized(profit)
                
                // Update local position tracking
                updatePositionAfterPartial(pos, quantity)
            }
        }
    }

    // --- Placeholder / Utility Methods ---
    private fun calculateEntryBudget(signal: KinanceSignal): Double = 15000.0 // Simplified for brevity
    private fun registerPosition(pairId: PairId, order: OrderResponse) {}
    private fun updatePositionAfterPartial(pos: ActivePositionWire, qty: Double) {}
    private suspend fun cancelAllOpenOrders() {}
    private suspend fun checkPositionExit(pos: ActivePositionWire, quote: MarketQuote) {}
    private fun startRiskAuditor() {}
    private fun startHeartbeat() {}
    private fun startWhatIfSimulationPolling() {}
    private fun scheduleDailyReset() {}
    private suspend fun performDailyReset() {
        profitLockManager.resetDaily()
    }

    companion object {
        private val hiddenStablePairs = setOf("usdt_idr", "usdc_idr", "indr_idr")
    }
}
