package com.kicryp.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * SelfHealingSystem - Automatic recovery from failures
 * 
 * Features:
 * 1. Connection Recovery - Auto-reconnect UDP with exponential backoff + circuit breaker
 * 2. State Persistence - Save/restore critical state every 30 seconds
 * 3. Health Monitoring - Heartbeat, memory, CPU, deadlock detection
 * 4. Auto-Restart - Safe mode after repeated crashes
 * 5. Data Integrity - Validate positions match exchange on startup
 * 
 * Philosophy:
 * - Fail gracefully, recover automatically
 * - Never lose position data
 * - Protect capital above all else
 * - Alert humans only when truly necessary
 */
class SelfHealingSystem(
    private val config: SelfHealingConfig = SelfHealingConfig(),
    private val stateDirectory: String = "state",
    private val onTelegramAlert: suspend (String, AlertSeverity) -> Unit = { _, _ -> },
    private val onComponentRestart: suspend (String) -> Boolean = { false },
    private val onExchangePositionFetch: suspend () -> List<ExchangePosition> = { emptyList() },
) {
    // ==================== STATE ====================
    private val healthStatus = AtomicReference(HealthStatus.HEALTHY)
    private val currentMode = AtomicReference(SystemMode.NORMAL)
    private val isRunning = AtomicBoolean(false)
    
    // Circuit Breaker State
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreaker>()
    
    // Crash Tracking
    private val crashHistory = ConcurrentHashMap<String, MutableList<Instant>>()
    
    // Health Metrics
    private val lastHeartbeats = ConcurrentHashMap<String, Instant>()
    private val componentHealth = ConcurrentHashMap<String, ComponentHealth>()
    
    // State Persistence
    private val stateMutex = Mutex()
    private var lastStateSave: Instant = Instant.DISTANT_PAST
    private var persistedState: PersistedState? = null
    
    // Healing Reports
    private val healingReports = mutableListOf<HealingReport>()
    private val reportsMutex = Mutex()
    
    // Coroutine scope for background tasks
    private var monitoringJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // ==================== PUBLIC API ====================
    
    /**
     * Start the self-healing system
     */
    suspend fun start() {
        if (isRunning.getAndSet(true)) {
            logInfo("SelfHealingSystem already running")
            return
        }
        
        logInfo("🏥 Starting SelfHealingSystem...")
        
        // Restore state from disk
        restoreStateFromDisk()
        
        // Validate positions with exchange
        validatePositionsWithExchange()
        
        // Start monitoring loops
        startMonitoringLoops()
        
        logInfo("✅ SelfHealingSystem started successfully")
        addHealingReport(
            component = "SelfHealingSystem",
            action = HealingAction.NONE,
            success = true,
            message = "System started successfully",
        )
    }
    
    /**
     * Stop the self-healing system
     */
    suspend fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }
        
        logInfo("🛑 Stopping SelfHealingSystem...")
        
        // Save final state
        saveStateToDisk()
        
        // Cancel monitoring jobs
        monitoringJob?.cancel()
        
        logInfo("✅ SelfHealingSystem stopped")
    }
    
    /**
     * Get current health status
     */
    fun getHealthStatus(): HealthStatus = healthStatus.get()
    
    /**
     * Get current system mode
     */
    fun getCurrentMode(): SystemMode = currentMode.get()
    
    /**
     * Check if trading is allowed
     */
    fun isTradingAllowed(): Boolean {
        val mode = currentMode.get()
        val health = healthStatus.get()
        
        return when {
            mode == SystemMode.SAFE_MODE -> false
            mode == SystemMode.EMERGENCY_STOP -> false
            health == HealthStatus.CRITICAL -> false
            else -> true
        }
    }
    
    /**
     * Check if new entries are allowed
     */
    fun isNewEntryAllowed(): Boolean {
        return isTradingAllowed() && currentMode.get() == SystemMode.NORMAL
    }
    
    /**
     * Record heartbeat from a component
     */
    fun recordHeartbeat(componentName: String, extraMetrics: Map<String, Double> = emptyMap()) {
        val now = Clock.System.now()
        lastHeartbeats[componentName] = now
        
        componentHealth.compute(componentName) { _, existing ->
            (existing ?: ComponentHealth(componentName)).apply {
                lastHeartbeat = now
                consecutiveMisses = 0
                metrics.putAll(extraMetrics)
                status = ComponentStatus.HEALTHY
            }
        }
    }
    
    /**
     * Report a component crash
     */
    suspend fun reportCrash(componentName: String, error: String) {
        val now = Clock.System.now()
        
        // Track crash history
        crashHistory.getOrPut(componentName) { mutableListOf() }.apply {
            add(now)
            // Keep only crashes from last 5 minutes
            removeAll { (now - it) > config.crashWindowDuration }
        }
        
        val recentCrashes = crashHistory[componentName]?.size ?: 0
        
        logError("💥 Component $componentName crashed: $error (${recentCrashes} crashes in ${config.crashWindowDuration})")
        
        // Update component health
        componentHealth.compute(componentName) { _, existing ->
            (existing ?: ComponentHealth(componentName)).apply {
                status = ComponentStatus.CRASHED
                lastCrash = now
                crashCount++
            }
        }
        
        // Check if we should enter safe mode
        if (recentCrashes >= config.maxCrashesBeforeSafeMode) {
            enterSafeMode("$componentName crashed $recentCrashes times in ${config.crashWindowDuration}")
        } else {
            // Try to restart the component
            attemptComponentRestart(componentName)
        }
        
        addHealingReport(
            component = componentName,
            action = HealingAction.RESTART_COMPONENT,
            success = false,
            message = "Crash detected: $error",
            error = error,
        )
        
        // Send Telegram alert
        onTelegramAlert(
            "🔴 CRASH: $componentName\n$error\nRecent crashes: $recentCrashes",
            AlertSeverity.HIGH,
        )
    }
    
    /**
     * Attempt to reconnect a UDP socket with exponential backoff
     */
    suspend fun attemptReconnect(
        connectionName: String,
        connectFn: suspend () -> Boolean,
    ): ReconnectResult {
        val circuitBreaker = circuitBreakers.getOrPut(connectionName) {
            CircuitBreaker(connectionName, config)
        }
        
        // Check circuit breaker
        if (circuitBreaker.isOpen()) {
            val timeUntilRetry = circuitBreaker.getTimeUntilRetry()
            logWarn("⚡ Circuit breaker OPEN for $connectionName. Retry in ${timeUntilRetry.inWholeSeconds}s")
            return ReconnectResult(
                success = false,
                connectionName = connectionName,
                reason = "CIRCUIT_BREAKER_OPEN",
                nextRetryIn = timeUntilRetry,
            )
        }
        
        // Calculate backoff delay
        val attempt = circuitBreaker.consecutiveFailures
        val backoffMs = calculateExponentialBackoff(attempt)
        
        logInfo("🔄 Reconnecting $connectionName (attempt ${attempt + 1}, backoff ${backoffMs}ms)")
        
        // Wait for backoff
        if (attempt > 0) {
            delay(backoffMs.milliseconds)
        }
        
        // Attempt connection
        return try {
            val success = connectFn()
            
            if (success) {
                circuitBreaker.recordSuccess()
                logInfo("✅ Reconnected $connectionName successfully")
                
                addHealingReport(
                    component = connectionName,
                    action = HealingAction.RECONNECT,
                    success = true,
                    message = "Reconnected after ${attempt + 1} attempts",
                )
                
                ReconnectResult(
                    success = true,
                    connectionName = connectionName,
                    attemptsUsed = attempt + 1,
                )
            } else {
                circuitBreaker.recordFailure()
                logWarn("❌ Reconnect failed for $connectionName")
                
                ReconnectResult(
                    success = false,
                    connectionName = connectionName,
                    reason = "CONNECTION_FAILED",
                    attemptsUsed = attempt + 1,
                    nextRetryIn = calculateExponentialBackoff(attempt + 1).milliseconds,
                )
            }
        } catch (e: Exception) {
            circuitBreaker.recordFailure()
            logError("❌ Reconnect exception for $connectionName: ${e.message}")
            
            addHealingReport(
                component = connectionName,
                action = HealingAction.RECONNECT,
                success = false,
                message = "Reconnect failed",
                error = e.message,
            )
            
            ReconnectResult(
                success = false,
                connectionName = connectionName,
                reason = "EXCEPTION",
                error = e.message,
                attemptsUsed = attempt + 1,
                nextRetryIn = calculateExponentialBackoff(attempt + 1).milliseconds,
            )
        }
    }
    
    /**
     * Save critical state (positions, orders, trailing stops)
     */
    suspend fun saveState(
        openPositions: List<PersistedPosition>,
        pendingOrders: List<PersistedOrder>,
        trailingStops: List<PersistedTrailingStop>,
        additionalData: Map<String, String> = emptyMap(),
    ) {
        stateMutex.withLock {
            persistedState = PersistedState(
                timestamp = Clock.System.now(),
                openPositions = openPositions,
                pendingOrders = pendingOrders,
                trailingStops = trailingStops,
                additionalData = additionalData,
                systemMode = currentMode.get(),
                healthStatus = healthStatus.get(),
            )
            
            lastStateSave = Clock.System.now()
        }
        
        saveStateToDisk()
    }
    
    /**
     * Get last persisted state
     */
    fun getPersistedState(): PersistedState? = persistedState
    
    /**
     * Get recent healing reports
     */
    suspend fun getHealingReports(limit: Int = 50): List<HealingReport> {
        return reportsMutex.withLock {
            healingReports.takeLast(limit)
        }
    }
    
    /**
     * Get system health summary
     */
    fun getHealthSummary(): HealthSummary {
        val now = Clock.System.now()
        
        val components = componentHealth.values.map { health ->
            val timeSinceHeartbeat = health.lastHeartbeat?.let { now - it }
            
            ComponentHealthSummary(
                name = health.componentName,
                status = health.status,
                lastHeartbeat = health.lastHeartbeat,
                heartbeatAgeSeconds = timeSinceHeartbeat?.inWholeSeconds,
                crashCount = health.crashCount,
                metrics = health.metrics.toMap(),
            )
        }
        
        val openCircuitBreakers = circuitBreakers.filter { it.value.isOpen() }
            .map { it.key }
        
        return HealthSummary(
            overallStatus = healthStatus.get(),
            systemMode = currentMode.get(),
            tradingAllowed = isTradingAllowed(),
            newEntriesAllowed = isNewEntryAllowed(),
            components = components,
            openCircuitBreakers = openCircuitBreakers,
            lastStateSave = lastStateSave,
            totalCrashesLast5Min = crashHistory.values.sumOf { it.size },
            healingReportsCount = healingReports.size,
        )
    }
    
    /**
     * Manually trigger safe mode
     */
    suspend fun triggerSafeMode(reason: String) {
        enterSafeMode(reason)
    }
    
    /**
     * Exit safe mode (manual override)
     */
    suspend fun exitSafeMode(reason: String) {
        if (currentMode.get() != SystemMode.SAFE_MODE) {
            return
        }
        
        logInfo("🟢 Exiting SAFE MODE: $reason")
        currentMode.set(SystemMode.NORMAL)
        healthStatus.set(HealthStatus.RECOVERING)
        
        addHealingReport(
            component = "System",
            action = HealingAction.NONE,
            success = true,
            message = "Exited safe mode: $reason",
        )
        
        onTelegramAlert(
            "🟢 SAFE MODE EXITED\nReason: $reason\nTrading resumed",
            AlertSeverity.MEDIUM,
        )
        
        // Set to healthy after a brief recovery period
        scope.launch {
            delay(30.seconds)
            if (currentMode.get() == SystemMode.NORMAL) {
                healthStatus.set(HealthStatus.HEALTHY)
            }
        }
    }
    
    // ==================== PRIVATE METHODS ====================
    
    private fun startMonitoringLoops() {
        monitoringJob = scope.launch {
            // Heartbeat monitoring (every 10 seconds)
            launch {
                while (isActive && isRunning.get()) {
                    try {
                        checkHeartbeats()
                    } catch (e: Exception) {
                        logError("Heartbeat check failed: ${e.message}")
                    }
                    delay(config.heartbeatIntervalSeconds.seconds)
                }
            }
            
            // Memory monitoring (every 30 seconds)
            launch {
                while (isActive && isRunning.get()) {
                    try {
                        checkMemoryUsage()
                    } catch (e: Exception) {
                        logError("Memory check failed: ${e.message}")
                    }
                    delay(30.seconds)
                }
            }
            
            // State persistence (every 30 seconds)
            launch {
                while (isActive && isRunning.get()) {
                    try {
                        autoSaveState()
                    } catch (e: Exception) {
                        logError("State save failed: ${e.message}")
                    }
                    delay(config.statePersistenceIntervalSeconds.seconds)
                }
            }
            
            // Deadlock detection (every 60 seconds)
            launch {
                while (isActive && isRunning.get()) {
                    try {
                        checkForDeadlocks()
                    } catch (e: Exception) {
                        logError("Deadlock check failed: ${e.message}")
                    }
                    delay(60.seconds)
                }
            }
            
            // Circuit breaker reset check (every 10 seconds)
            launch {
                while (isActive && isRunning.get()) {
                    try {
                        checkCircuitBreakers()
                    } catch (e: Exception) {
                        logError("Circuit breaker check failed: ${e.message}")
                    }
                    delay(10.seconds)
                }
            }
        }
    }
    
    private fun checkHeartbeats() {
        val now = Clock.System.now()
        var degradedCount = 0
        var criticalCount = 0
        
        componentHealth.forEach { (name, health) ->
            val lastHeartbeat = health.lastHeartbeat ?: return@forEach
            val timeSinceHeartbeat = now - lastHeartbeat
            
            when {
                timeSinceHeartbeat > config.criticalHeartbeatTimeout -> {
                    criticalCount++
                    health.consecutiveMisses++
                    health.status = ComponentStatus.CRITICAL
                    
                    if (health.consecutiveMisses == 3) {
                        logError("🚨 CRITICAL: $name no heartbeat for ${timeSinceHeartbeat.inWholeSeconds}s")
                        scope.launch {
                            onTelegramAlert(
                                "🚨 CRITICAL: $name unresponsive for ${timeSinceHeartbeat.inWholeSeconds}s",
                                AlertSeverity.CRITICAL,
                            )
                        }
                    }
                }
                timeSinceHeartbeat > config.degradedHeartbeatTimeout -> {
                    degradedCount++
                    health.consecutiveMisses++
                    health.status = ComponentStatus.DEGRADED
                }
                else -> {
                    health.consecutiveMisses = 0
                    health.status = ComponentStatus.HEALTHY
                }
            }
        }
        
        // Update overall health status
        healthStatus.set(when {
            criticalCount > 0 -> HealthStatus.CRITICAL
            degradedCount > 0 -> HealthStatus.DEGRADED
            healthStatus.get() == HealthStatus.RECOVERING -> HealthStatus.RECOVERING
            else -> HealthStatus.HEALTHY
        })
    }
    
    private suspend fun checkMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val usedPct = (usedMemory.toDouble() / maxMemory.toDouble()) * 100
        
        when {
            usedPct >= config.memoryRestartThresholdPct -> {
                logError("🔴 MEMORY CRITICAL: ${usedPct.toInt()}% used - triggering restart")
                
                addHealingReport(
                    component = "Memory",
                    action = HealingAction.FULL_RESTART,
                    success = false,
                    message = "Memory at ${usedPct.toInt()}% - restart needed",
                )
                
                onTelegramAlert(
                    "🔴 MEMORY CRITICAL: ${usedPct.toInt()}%\nRestart required!",
                    AlertSeverity.CRITICAL,
                )
                
                // Request GC and consider restart
                System.gc()
            }
            usedPct >= config.memoryAlertThresholdPct -> {
                logWarn("🟠 MEMORY HIGH: ${usedPct.toInt()}% used")
                
                // Request GC
                System.gc()
                
                if (healthStatus.get() != HealthStatus.CRITICAL) {
                    healthStatus.set(HealthStatus.DEGRADED)
                }
            }
        }
        
        // Update memory metrics for monitoring component
        componentHealth.compute("Memory") { _, existing ->
            (existing ?: ComponentHealth("Memory")).apply {
                lastHeartbeat = Clock.System.now()
                metrics["usedPct"] = usedPct
                metrics["usedMb"] = usedMemory / (1024.0 * 1024.0)
                metrics["maxMb"] = maxMemory / (1024.0 * 1024.0)
                status = when {
                    usedPct >= config.memoryRestartThresholdPct -> ComponentStatus.CRITICAL
                    usedPct >= config.memoryAlertThresholdPct -> ComponentStatus.DEGRADED
                    else -> ComponentStatus.HEALTHY
                }
            }
        }
    }
    
    private fun checkForDeadlocks() {
        val threadMxBean = java.lang.management.ManagementFactory.getThreadMXBean()
        val deadlockedThreads = threadMxBean.findDeadlockedThreads()
        
        if (deadlockedThreads != null && deadlockedThreads.isNotEmpty()) {
            val threadInfos = threadMxBean.getThreadInfo(deadlockedThreads, true, true)
            val threadNames = threadInfos.mapNotNull { it?.threadName }.joinToString(", ")
            
            logError("🔒 DEADLOCK DETECTED: $threadNames")
            
            scope.launch {
                addHealingReport(
                    component = "ThreadMonitor",
                    action = HealingAction.FULL_RESTART,
                    success = false,
                    message = "Deadlock detected in threads: $threadNames",
                )
                
                onTelegramAlert(
                    "🔒 DEADLOCK DETECTED\nThreads: $threadNames\nRestart required!",
                    AlertSeverity.CRITICAL,
                )
            }
            
            healthStatus.set(HealthStatus.CRITICAL)
        }
    }
    
    private fun checkCircuitBreakers() {
        val now = Clock.System.now()
        
        circuitBreakers.forEach { (name, cb) ->
            if (cb.isOpen() && cb.shouldAttemptReset(now)) {
                cb.attemptReset()
                logInfo("🔌 Circuit breaker for $name moved to HALF_OPEN")
            }
        }
    }
    
    private suspend fun autoSaveState() {
        val state = persistedState ?: return
        
        val timeSinceLastSave = Clock.System.now() - lastStateSave
        if (timeSinceLastSave >= config.statePersistenceIntervalSeconds.seconds) {
            saveStateToDisk()
        }
    }
    
    private suspend fun saveStateToDisk() {
        val state = persistedState ?: return
        
        stateMutex.withLock {
            try {
                val stateDir = File(stateDirectory)
                if (!stateDir.exists()) {
                    stateDir.mkdirs()
                }
                
                val stateFile = File(stateDir, "self_healing_state.json")
                val json = serializeState(state)
                AtomicFileWriter.write(stateFile, json)
                
                // Also save a backup with timestamp
                val backupFile = File(stateDir, "self_healing_state_backup.json")
                AtomicFileWriter.write(backupFile, json)
                
                logDebug("💾 State saved to disk (${state.openPositions.size} positions)")
            } catch (e: Exception) {
                logError("Failed to save state to disk: ${e.message}")
            }
        }
    }
    
    private suspend fun restoreStateFromDisk() {
        stateMutex.withLock {
            try {
                val stateFile = File(stateDirectory, "self_healing_state.json")
                
                if (!stateFile.exists()) {
                    logInfo("📂 No previous state file found")
                    return
                }
                
                val json = stateFile.readText()
                persistedState = deserializeState(json)
                
                persistedState?.let { state ->
                    logInfo("📂 Restored state from disk:")
                    logInfo("   - ${state.openPositions.size} open positions")
                    logInfo("   - ${state.pendingOrders.size} pending orders")
                    logInfo("   - ${state.trailingStops.size} trailing stops")
                    logInfo("   - Previous mode: ${state.systemMode}")
                    
                    // Restore system mode if it was in safe mode
                    if (state.systemMode == SystemMode.SAFE_MODE) {
                        logWarn("⚠️ System was in SAFE MODE when last stopped")
                        currentMode.set(SystemMode.SAFE_MODE)
                        healthStatus.set(HealthStatus.RECOVERING)
                    }
                    
                    addHealingReport(
                        component = "StatePersistence",
                        action = HealingAction.NONE,
                        success = true,
                        message = "Restored ${state.openPositions.size} positions from disk",
                    )
                }
            } catch (e: Exception) {
                logError("Failed to restore state from disk: ${e.message}")
                
                // Try backup file
                try {
                    val backupFile = File(stateDirectory, "self_healing_state_backup.json")
                    if (backupFile.exists()) {
                        val json = backupFile.readText()
                        persistedState = deserializeState(json)
                        logInfo("📂 Restored state from backup file")
                    }
                } catch (e2: Exception) {
                    logError("Failed to restore from backup: ${e2.message}")
                }
            }
        }
    }
    
    private suspend fun validatePositionsWithExchange() {
        try {
            val exchangePositions = onExchangePositionFetch()
            val persistedPositions = persistedState?.openPositions ?: emptyList()
            
            if (exchangePositions.isEmpty() && persistedPositions.isEmpty()) {
                logInfo("✅ No positions to validate")
                return
            }
            
            val discrepancies = mutableListOf<PositionDiscrepancy>()
            
            // Check for positions in our state but not on exchange
            for (persisted in persistedPositions) {
                val exchangePos = exchangePositions.find { it.pair == persisted.pair }
                
                if (exchangePos == null) {
                    discrepancies.add(PositionDiscrepancy(
                        pair = persisted.pair,
                        type = DiscrepancyType.MISSING_ON_EXCHANGE,
                        expectedQuantity = persisted.quantity,
                        actualQuantity = 0.0,
                        message = "Position ${persisted.pair} in state but not on exchange",
                    ))
                } else if (kotlin.math.abs(exchangePos.quantity - persisted.quantity) > 0.0001) {
                    discrepancies.add(PositionDiscrepancy(
                        pair = persisted.pair,
                        type = DiscrepancyType.QUANTITY_MISMATCH,
                        expectedQuantity = persisted.quantity,
                        actualQuantity = exchangePos.quantity,
                        message = "Quantity mismatch for ${persisted.pair}: expected ${persisted.quantity}, actual ${exchangePos.quantity}",
                    ))
                }
            }
            
            // Check for positions on exchange but not in our state
            for (exchange in exchangePositions) {
                val persistedPos = persistedPositions.find { it.pair == exchange.pair }
                
                if (persistedPos == null) {
                    discrepancies.add(PositionDiscrepancy(
                        pair = exchange.pair,
                        type = DiscrepancyType.MISSING_IN_STATE,
                        expectedQuantity = 0.0,
                        actualQuantity = exchange.quantity,
                        message = "Position ${exchange.pair} on exchange but not in state",
                    ))
                }
            }
            
            if (discrepancies.isEmpty()) {
                logInfo("✅ Position validation passed - ${persistedPositions.size} positions match exchange")
            } else {
                logError("⚠️ Position validation found ${discrepancies.size} discrepancies:")
                discrepancies.forEach { d ->
                    logError("   - ${d.message}")
                }
                
                addHealingReport(
                    component = "DataIntegrity",
                    action = HealingAction.NONE,
                    success = false,
                    message = "Found ${discrepancies.size} position discrepancies",
                )
                
                // Alert on critical discrepancies
                val criticalDiscrepancies = discrepancies.filter {
                    it.type == DiscrepancyType.MISSING_ON_EXCHANGE ||
                    it.type == DiscrepancyType.QUANTITY_MISMATCH
                }
                
                if (criticalDiscrepancies.isNotEmpty()) {
                    onTelegramAlert(
                        "⚠️ POSITION DISCREPANCY\n" +
                        criticalDiscrepancies.joinToString("\n") { "• ${it.message}" },
                        AlertSeverity.HIGH,
                    )
                }
            }
        } catch (e: Exception) {
            logError("Position validation failed: ${e.message}")
        }
    }
    
    private suspend fun attemptComponentRestart(componentName: String) {
        logInfo("🔄 Attempting to restart $componentName...")
        
        val success = try {
            onComponentRestart(componentName)
        } catch (e: Exception) {
            logError("Component restart failed: ${e.message}")
            false
        }
        
        if (success) {
            logInfo("✅ $componentName restarted successfully")
            
            componentHealth.compute(componentName) { _, existing ->
                (existing ?: ComponentHealth(componentName)).apply {
                    status = ComponentStatus.RECOVERING
                }
            }
            
            addHealingReport(
                component = componentName,
                action = HealingAction.RESTART_COMPONENT,
                success = true,
                message = "Component restarted successfully",
            )
            
            onTelegramAlert(
                "🟢 RECOVERED: $componentName restarted successfully",
                AlertSeverity.MEDIUM,
            )
        } else {
            logError("❌ Failed to restart $componentName")
            
            addHealingReport(
                component = componentName,
                action = HealingAction.RESTART_COMPONENT,
                success = false,
                message = "Component restart failed",
            )
        }
    }
    
    private suspend fun enterSafeMode(reason: String) {
        if (currentMode.get() == SystemMode.SAFE_MODE) {
            return
        }
        
        logError("🛑 ENTERING SAFE MODE: $reason")
        
        currentMode.set(SystemMode.SAFE_MODE)
        healthStatus.set(HealthStatus.CRITICAL)
        
        addHealingReport(
            component = "System",
            action = HealingAction.SAFE_MODE,
            success = true,
            message = "Entered safe mode: $reason",
        )
        
        onTelegramAlert(
            "🛑 SAFE MODE ACTIVATED\n" +
            "Reason: $reason\n" +
            "• No new trades allowed\n" +
            "• Only managing existing positions\n" +
            "• Manual intervention required",
            AlertSeverity.CRITICAL,
        )
    }
    
    private fun calculateExponentialBackoff(attempt: Int): Long {
        val baseMs = config.reconnectBaseDelayMs
        val maxMs = config.reconnectMaxDelayMs
        val delay = baseMs * (2.0.pow(attempt.toDouble())).toLong()
        return min(delay, maxMs)
    }
    
    private suspend fun addHealingReport(
        component: String,
        action: HealingAction,
        success: Boolean,
        message: String,
        error: String? = null,
    ) {
        val report = HealingReport(
            timestamp = Clock.System.now(),
            component = component,
            action = action,
            success = success,
            message = message,
            error = error,
            healthStatusBefore = healthStatus.get(),
            systemMode = currentMode.get(),
        )
        
        reportsMutex.withLock {
            healingReports.add(report)
            
            // Keep only last 1000 reports
            if (healingReports.size > 1000) {
                healingReports.removeAt(0)
            }
        }
    }
    
    // Simple JSON serialization (in production, use kotlinx.serialization)
    private fun serializeState(state: PersistedState): String {
        return buildString {
            appendLine("{")
            appendLine("  \"timestamp\": \"${state.timestamp}\",")
            appendLine("  \"systemMode\": \"${state.systemMode}\",")
            appendLine("  \"healthStatus\": \"${state.healthStatus}\",")
            appendLine("  \"openPositions\": [")
            state.openPositions.forEachIndexed { index, pos ->
                val comma = if (index < state.openPositions.size - 1) "," else ""
                appendLine("    {\"pair\": \"${pos.pair}\", \"entryPrice\": ${pos.entryPrice}, \"quantity\": ${pos.quantity}, \"entryTime\": \"${pos.entryTime}\", \"strategy\": \"${pos.strategy}\"}$comma")
            }
            appendLine("  ],")
            appendLine("  \"pendingOrders\": [")
            state.pendingOrders.forEachIndexed { index, order ->
                val comma = if (index < state.pendingOrders.size - 1) "," else ""
                appendLine("    {\"orderId\": \"${order.orderId}\", \"pair\": \"${order.pair}\", \"side\": \"${order.side}\", \"price\": ${order.price}, \"quantity\": ${order.quantity}}$comma")
            }
            appendLine("  ],")
            appendLine("  \"trailingStops\": [")
            state.trailingStops.forEachIndexed { index, stop ->
                val comma = if (index < state.trailingStops.size - 1) "," else ""
                appendLine("    {\"pair\": \"${stop.pair}\", \"trailPct\": ${stop.trailPct}, \"highestPrice\": ${stop.highestPrice}, \"currentStopPrice\": ${stop.currentStopPrice}}$comma")
            }
            appendLine("  ]")
            appendLine("}")
        }
    }
    
    private fun deserializeState(json: String): PersistedState {
        // Simple parser - in production use kotlinx.serialization
        val positions = mutableListOf<PersistedPosition>()
        val orders = mutableListOf<PersistedOrder>()
        val stops = mutableListOf<PersistedTrailingStop>()
        
        // Extract system mode
        val modeRegex = "\"systemMode\":\\s*\"(\\w+)\"".toRegex()
        val modeMatch = modeRegex.find(json)
        val systemMode = modeMatch?.groupValues?.get(1)?.let { 
            try { SystemMode.valueOf(it) } catch (e: Exception) { SystemMode.NORMAL }
        } ?: SystemMode.NORMAL
        
        // Extract positions
        val posRegex = "\\{\"pair\":\\s*\"([^\"]+)\",\\s*\"entryPrice\":\\s*([\\d.]+),\\s*\"quantity\":\\s*([\\d.]+),\\s*\"entryTime\":\\s*\"([^\"]+)\",\\s*\"strategy\":\\s*\"([^\"]+)\"\\}".toRegex()
        posRegex.findAll(json).forEach { match ->
            positions.add(PersistedPosition(
                pair = match.groupValues[1],
                entryPrice = match.groupValues[2].toDouble(),
                quantity = match.groupValues[3].toDouble(),
                entryTime = Instant.parse(match.groupValues[4]),
                strategy = match.groupValues[5],
            ))
        }
        
        // Extract orders
        val orderRegex = "\\{\"orderId\":\\s*\"([^\"]+)\",\\s*\"pair\":\\s*\"([^\"]+)\",\\s*\"side\":\\s*\"([^\"]+)\",\\s*\"price\":\\s*([\\d.]+),\\s*\"quantity\":\\s*([\\d.]+)\\}".toRegex()
        orderRegex.findAll(json).forEach { match ->
            orders.add(PersistedOrder(
                orderId = match.groupValues[1],
                pair = match.groupValues[2],
                side = match.groupValues[3],
                price = match.groupValues[4].toDouble(),
                quantity = match.groupValues[5].toDouble(),
            ))
        }
        
        // Extract trailing stops
        val stopRegex = "\\{\"pair\":\\s*\"([^\"]+)\",\\s*\"trailPct\":\\s*([\\d.]+),\\s*\"highestPrice\":\\s*([\\d.]+),\\s*\"currentStopPrice\":\\s*([\\d.]+)\\}".toRegex()
        stopRegex.findAll(json).forEach { match ->
            stops.add(PersistedTrailingStop(
                pair = match.groupValues[1],
                trailPct = match.groupValues[2].toDouble(),
                highestPrice = match.groupValues[3].toDouble(),
                currentStopPrice = match.groupValues[4].toDouble(),
            ))
        }
        
        return PersistedState(
            timestamp = Clock.System.now(),
            openPositions = positions,
            pendingOrders = orders,
            trailingStops = stops,
            systemMode = systemMode,
            healthStatus = HealthStatus.RECOVERING,
        )
    }
    
    // Logging helpers
    private fun logInfo(msg: String) = println("[SelfHealing] INFO: $msg")
    private fun logWarn(msg: String) = println("[SelfHealing] WARN: $msg")
    private fun logError(msg: String) = println("[SelfHealing] ERROR: $msg")
    private fun logDebug(msg: String) = println("[SelfHealing] DEBUG: $msg")
}

// ==================== CONFIGURATION ====================

/**
 * Configuration for the self-healing system
 */
data class SelfHealingConfig(
    // Connection Recovery
    val reconnectBaseDelayMs: Long = 100,
    val reconnectMaxDelayMs: Long = 10_000,
    val circuitBreakerFailureThreshold: Int = 5,
    val circuitBreakerResetTimeoutSeconds: Long = 60,
    
    // Health Monitoring
    val heartbeatIntervalSeconds: Int = 10,
    val degradedHeartbeatTimeout: Duration = 30.seconds,
    val criticalHeartbeatTimeout: Duration = 60.seconds,
    
    // Memory Monitoring
    val memoryAlertThresholdPct: Double = 80.0,
    val memoryRestartThresholdPct: Double = 95.0,
    
    // Crash Recovery
    val maxCrashesBeforeSafeMode: Int = 3,
    val crashWindowDuration: Duration = 5.minutes,
    
    // State Persistence
    val statePersistenceIntervalSeconds: Int = 30,
)

// ==================== ENUMS ====================

/**
 * Overall health status of the system
 */
enum class HealthStatus {
    HEALTHY,      // All systems nominal
    DEGRADED,     // Some issues, but operational
    CRITICAL,     // Severe issues, limited operation
    RECOVERING,   // Recovering from issues
}

/**
 * System operation mode
 */
enum class SystemMode {
    NORMAL,         // Full trading capability
    SAFE_MODE,      // No new trades, manage existing only
    EMERGENCY_STOP, // All trading halted
}

/**
 * Recovery action taken by self-healing system
 */
enum class HealingAction {
    NONE,
    RECONNECT,
    RESTART_COMPONENT,
    SAFE_MODE,
    FULL_RESTART,
}

/**
 * Individual component status
 */
enum class ComponentStatus {
    HEALTHY,
    DEGRADED,
    CRITICAL,
    CRASHED,
    RECOVERING,
    UNKNOWN,
}

/**
 * Alert severity for Telegram notifications
 */
enum class AlertSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

/**
 * Type of position discrepancy
 */
enum class DiscrepancyType {
    MISSING_ON_EXCHANGE,
    MISSING_IN_STATE,
    QUANTITY_MISMATCH,
    PRICE_MISMATCH,
}

// ==================== DATA CLASSES ====================

/**
 * Report of a healing action taken
 */
data class HealingReport(
    val timestamp: Instant,
    val component: String,
    val action: HealingAction,
    val success: Boolean,
    val message: String,
    val error: String? = null,
    val healthStatusBefore: HealthStatus,
    val systemMode: SystemMode,
)

/**
 * Health of an individual component
 */
data class ComponentHealth(
    val componentName: String,
    var status: ComponentStatus = ComponentStatus.UNKNOWN,
    var lastHeartbeat: Instant? = null,
    var lastCrash: Instant? = null,
    var crashCount: Int = 0,
    var consecutiveMisses: Int = 0,
    val metrics: MutableMap<String, Double> = mutableMapOf(),
)

/**
 * Summary of component health for external reporting
 */
data class ComponentHealthSummary(
    val name: String,
    val status: ComponentStatus,
    val lastHeartbeat: Instant?,
    val heartbeatAgeSeconds: Long?,
    val crashCount: Int,
    val metrics: Map<String, Double>,
)

/**
 * Overall health summary
 */
data class HealthSummary(
    val overallStatus: HealthStatus,
    val systemMode: SystemMode,
    val tradingAllowed: Boolean,
    val newEntriesAllowed: Boolean,
    val components: List<ComponentHealthSummary>,
    val openCircuitBreakers: List<String>,
    val lastStateSave: Instant,
    val totalCrashesLast5Min: Int,
    val healingReportsCount: Int,
)

/**
 * Persisted state for recovery
 */
data class PersistedState(
    val timestamp: Instant,
    val openPositions: List<PersistedPosition>,
    val pendingOrders: List<PersistedOrder>,
    val trailingStops: List<PersistedTrailingStop>,
    val additionalData: Map<String, String> = emptyMap(),
    val systemMode: SystemMode = SystemMode.NORMAL,
    val healthStatus: HealthStatus = HealthStatus.HEALTHY,
)

/**
 * Persisted position data
 */
data class PersistedPosition(
    val pair: String,
    val entryPrice: Double,
    val quantity: Double,
    val entryTime: Instant,
    val strategy: String,
    val capitalUsedIdr: Double = 0.0,
    val entryFeeIdr: Double = 0.0,
)

/**
 * Persisted order data
 */
data class PersistedOrder(
    val orderId: String,
    val pair: String,
    val side: String,  // "BUY" or "SELL"
    val price: Double,
    val quantity: Double,
    val createdAt: Instant = Clock.System.now(),
)

/**
 * Persisted trailing stop data
 */
data class PersistedTrailingStop(
    val pair: String,
    val trailPct: Double,
    val highestPrice: Double,
    val currentStopPrice: Double,
    val activatedAt: Instant = Clock.System.now(),
)

/**
 * Exchange position for validation
 */
data class ExchangePosition(
    val pair: String,
    val quantity: Double,
    val averagePrice: Double = 0.0,
)

/**
 * Position discrepancy found during validation
 */
data class PositionDiscrepancy(
    val pair: String,
    val type: DiscrepancyType,
    val expectedQuantity: Double,
    val actualQuantity: Double,
    val message: String,
)

/**
 * Result of a reconnection attempt
 */
data class ReconnectResult(
    val success: Boolean,
    val connectionName: String,
    val reason: String? = null,
    val error: String? = null,
    val attemptsUsed: Int = 0,
    val nextRetryIn: Duration? = null,
)

// ==================== CIRCUIT BREAKER ====================

/**
 * Circuit breaker for connection management
 * 
 * States:
 * - CLOSED: Normal operation, requests go through
 * - OPEN: Too many failures, reject immediately
 * - HALF_OPEN: Testing if service recovered
 */
class CircuitBreaker(
    private val name: String,
    private val config: SelfHealingConfig,
) {
    private var state = CircuitBreakerState.CLOSED
    var consecutiveFailures = 0
        private set
    private var lastFailureTime: Instant? = null
    private var openedAt: Instant? = null
    
    enum class CircuitBreakerState {
        CLOSED,
        OPEN,
        HALF_OPEN,
    }
    
    fun isOpen(): Boolean = state == CircuitBreakerState.OPEN
    
    fun isClosed(): Boolean = state == CircuitBreakerState.CLOSED
    
    fun isHalfOpen(): Boolean = state == CircuitBreakerState.HALF_OPEN
    
    fun recordFailure() {
        consecutiveFailures++
        lastFailureTime = Clock.System.now()
        
        if (consecutiveFailures >= config.circuitBreakerFailureThreshold) {
            if (state != CircuitBreakerState.OPEN) {
                state = CircuitBreakerState.OPEN
                openedAt = Clock.System.now()
                println("[CircuitBreaker] $name: OPENED after $consecutiveFailures failures")
            }
        }
    }
    
    fun recordSuccess() {
        consecutiveFailures = 0
        state = CircuitBreakerState.CLOSED
        openedAt = null
        println("[CircuitBreaker] $name: CLOSED (success)")
    }
    
    fun shouldAttemptReset(now: Instant): Boolean {
        if (state != CircuitBreakerState.OPEN) return false
        
        val openedTime = openedAt ?: return true
        val elapsed = now - openedTime
        
        return elapsed.inWholeSeconds >= config.circuitBreakerResetTimeoutSeconds
    }
    
    fun attemptReset() {
        if (state == CircuitBreakerState.OPEN) {
            state = CircuitBreakerState.HALF_OPEN
            println("[CircuitBreaker] $name: HALF_OPEN (attempting reset)")
        }
    }
    
    fun getTimeUntilRetry(): Duration {
        val openedTime = openedAt ?: return Duration.ZERO
        val elapsed = Clock.System.now() - openedTime
        val remaining = config.circuitBreakerResetTimeoutSeconds.seconds - elapsed
        return if (remaining.isNegative()) Duration.ZERO else remaining
    }
}

// ==================== ATOMIC REFERENCE (simple impl) ====================

/**
 * Simple atomic reference for thread-safe state
 */
class AtomicReference<T>(initial: T) {
    @Volatile
    private var value: T = initial
    
    fun get(): T = value
    
    fun set(newValue: T) {
        value = newValue
    }
    
    fun getAndSet(newValue: T): T {
        val old = value
        value = newValue
        return old
    }
}
