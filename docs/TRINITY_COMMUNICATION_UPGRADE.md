# 🚀 TRINITY INTER-BOT COMMUNICATION UPGRADE

## 📋 SUMMARY (SIMPLE INDONESIAN)

Gw udah implement 4 sistem baru untuk 3 bot lu (Kinance, KiDax, KiCryp):

### ✅ 1. SHARED POSITION TRACKER
**Apa ini?** Semua bot tau KiDax pegang koin apa, beli diharga berapa, fee berapa

**Cara kerja:**
```
KiDax beli BTC:
  - Entry price: Rp150
  - Fee: 0.3%
  - Target profit: 3%
  
BROADCAST UDP → Kinance & KiCryp langsung tau!

Kinance:  "OK, BTC position open, gw pantau Binance untuk exit signal"
KiCryp:    "OK, BTC entry Rp150, gw hitung profit/loss real-time"
```

### ✅ 2. LATE PUMP ENTRY STRATEGY  
**Apa ini?** Masuk ke pump yang udah jalan (seperti BR/IDR yg pump 50%+)

**Cara kerja:**
```
BR/IDR udah pump 50%:
  ❌ Jangan FOMO beli di puncak
  ✅ Tunggu pullback 5-15% 
  ✅ Entry dengan stop loss ketat
  ✅ Target profit realistis 10%
  
Jika parabolic pump (80%+):
  ⚠️ DON'T CHASE - pump exhausted!
```

### ✅ 3. TRINITY HEARTBEAT MONITOR
**Apa ini?** Bot saling check siapa masih hidup

**Cara kerja:**
```
Setiap 10 detik:
  Kinance: "I'm alive!"
  KiDax:   "I'm alive!"
  KiCryp:   "I'm alive!"

Jika Kinance tidak respon 30 detik:
  Status: DEGRADED → Warning
  
Jika Kinance tidak respon 60 detik:
  Status: DEAD → RESTART NOW!
  KiCryp: "Gw restart Kinance!" → ssh restart command
```

### ✅ 4. TRADE LEDGER (LEARNING SYSTEM)
**Apa ini?** Catat SEMUA trade dengan fee, slippage, profit/loss

**Cara kerja:**
```
Trade #1: 
  Pair: BTC
  Entry: 100, Exit: 105
  Fee: 0.6%
  Profit: +4.4% ✅

Trade #2:
  Pair: DRX
  Entry: 50, Exit: 48
  Fee: 0.6%
  Loss: -4.6% ❌
  
LEARNING: DRX punya high slippage → DON'T TRADE!

Trade #3:
  Pair: STO
  Entry: 20, Exit: 25
  Fee: 0.6%
  Profit: +24.4% ✅ ✅ ✅
  
LEARNING: STO bagus untuk anomaly strategy!
```

### ✅ 5. CAPITAL ALLOCATION (20/80 SPLIT)
**Apa ini?** Bagi modal: 20% untuk pump, 80% untuk stable

**Cara kerja:**
```
Total modal: Rp1,000,000

ANOMALY POOL (20% = Rp200,000):
  - Chase pumps kayak BR/IDR
  - High risk, high reward
  - Target profit 15%+
  
STABLE POOL (80% = Rp800,000):
  - Keluar masuk koin kecil
  - Lower risk, steady profit
  - Target profit 3%+

Bot BLOCK entry jika:
  - Anomaly pool udah full (>20%)
  - Stable pool udah full (>80%)
```

---

## 📦 NEW FILES CREATED

### 1. `/packages/core/src/commonMain/kotlin/com/kicryp/core/SharedPositionTracker.kt`
- All bots know what KiDax is holding
- Broadcast position open/close via UDP
- Validate 20/80 capital allocation

### 2. `/packages/core/src/commonMain/kotlin/com/kicryp/core/LatePumpEntryStrategy.kt`
- Enter pumps that are ALREADY running
- Wait for healthy pullback (5-15%)
- Don't chase exhausted pumps (80%+)

### 3. `/packages/core/src/commonMain/kotlin/com/kicryp/core/TrinityHeartbeatMonitor.kt`
- Monitor all 3 bots health
- Detect dead bots (> 60s no heartbeat)
- Generate restart commands

### 4. `/packages/core/src/commonMain/kotlin/com/kicryp/core/TradeLedger.kt`
- Track every trade with fees and slippage
- Learn which pairs are profitable
- Learn which strategies lose money

### 5. `/packages/core/src/commonMain/kotlin/com/kicryp/core/PositionStrategy.kt` (enum)
- `ANOMALY`: 20% capital, chase pumps
- `STABLE`: 80% capital, steady trading

---

## 🔌 HOW TO WIRE INTO MacEngineDaemon

Gw udah tambahkan inisialisasi di line 258-261:
```kotlin
private val sharedPositionTracker = SharedPositionTracker()
private val tradeLedger = TradeLedger()
private val heartbeatMonitor = TrinityHeartbeatMonitor()
private val latePumpEntry = LatePumpEntryStrategy()
```

**Next steps (yang perlu lu lakukan):**

### Step 1: Wire Position Broadcast saat BUY
Cari fungsi `submitBuyOrder` atau `EXECUTION_BUY`, tambahkan:
```kotlin
// After successful buy
val broadcast = sharedPositionTracker.broadcastPositionOpened(
    pair = pairId,
    entryPrice = executedPrice,
    quantity = quantity,
    entryFeeIdr = feeIdr,
    capitalUsedIdr = totalCostIdr,
    strategy = if (pumpConfidence > 0.7) PositionStrategy.ANOMALY else PositionStrategy.STABLE,
)

// Send UDP to all bots
val udpPayload = json.encodeToString(broadcast)
sendLeadLagUdp(udpPayload)
```

### Step 2: Wire Position Broadcast saat SELL
Cari fungsi `submitSellOrder` atau `EXECUTION_SELL`, tambahkan:
```kotlin
// After successful sell
val broadcast = sharedPositionTracker.broadcastPositionClosed(
    pair = pairId,
    exitPrice = executedPrice,
    exitFeeIdr = feeIdr,
    netProfitIdr = profitIdr,
    netProfitPct = profitPct,
    holdMinutes = holdMinutes,
    reason = exitReason,
)

// Record to ledger for learning
tradeLedger.recordTrade(
    pair = pairId,
    strategy = position.strategy,
    entryPrice = position.entryPrice,
    exitPrice = executedPrice,
    quantity = quantity,
    entryFeeIdr = position.entryFeeIdr,
    exitFeeIdr = feeIdr,
    slippageIdr = slippageIdr,
    netProfitIdr = profitIdr,
    netProfitPct = profitPct,
    holdMinutes = holdMinutes,
    exitReason = exitReason,
)

// Send UDP to all bots
val udpPayload = json.encodeToString(broadcast)
sendLeadLagUdp(udpPayload)
```

### Step 3: Wire Late Pump Entry
Cari fungsi `routeByChartAnalyzer` atau dimana bot decide entry, tambahkan:
```kotlin
// If pump detected
val latePumpEval = latePumpEntry.evaluateLatePumpEntry(quote)

if (latePumpEval.canEnter) {
    logger.info("[LATE_PUMP_ENTRY] pair=$pairId reason=${latePumpEval.reason} analysis=${latePumpEval.analysis}")
    
    // Entry with adjusted size
    val adjustedSize = normalSize * latePumpEval.positionSizePct
    
    // Submit buy with tighter stop loss
    submitBuyOrder(
        pair = pairId,
        size = adjustedSize,
        stopLossPct = latePumpEval.stopLossPct,
        takeProfitPct = latePumpEval.takeProfitPct,
    )
}
```

### Step 4: Wire Heartbeat Check
Cari main loop (`suspend fun run()`), tambahkan:
```kotlin
// Every 10 seconds, check dead bots
if ((now - lastHeartbeatCheckAt) > 10.seconds) {
    val deadBots = heartbeatMonitor.checkDeadBots(now)
    
    deadBots.forEach { alert ->
        when (alert.action) {
            RestartAction.IMMEDIATE_RESTART -> {
                logger.error("[TRINITY_DEAD_BOT] ${alert.message}")
                
                // Generate restart command
                val serverHost = when (alert.botName) {
                    "kinance" -> "152.69.218.198"
                    "kidax", "kicryp" -> "213.35.118.26"
                    else -> return@forEach
                }
                
                val restartCmd = heartbeatMonitor.generateRestartCommand(alert.botName, serverHost)
                logger.warn("[TRINITY_RESTART] Attempting: $restartCmd")
                
                // TODO: Execute restart via ProcessBuilder or SSH
            }
            RestartAction.WARN_ONLY -> {
                logger.warn("[TRINITY_DEGRADED] ${alert.message}")
            }
            else -> {}
        }
    }
    
    lastHeartbeatCheckAt = now
}
```

### Step 5: Wire Capital Allocation Check
Cari sebelum submit buy, tambahkan:
```kotlin
// Before buy, check capital limits
val capitalCheck = sharedPositionTracker.validateCapitalAllocation(
    totalCash = freeIdr,
    proposedStrategy = if (pumpConfidence > 0.7) PositionStrategy.ANOMALY else PositionStrategy.STABLE,
    proposedAmount = orderCostIdr,
)

if (!capitalCheck.allowed) {
    logger.warn("[WHY_NOT_BUY] pair=$pairId reason=${capitalCheck.reason} anomaly=${capitalCheck.currentAnomalyPct}% stable=${capitalCheck.currentStablePct}%")
    return  // BLOCK entry
}
```

### Step 6: Wire Learning Insights
Cari dimana bot log performance (setelah trade selesai), tambahkan:
```kotlin
// Every hour, get learning insights
val insights = tradeLedger.getLearningInsights()

insights.forEach { insight ->
    when (insight.type) {
        InsightType.BAD_PAIR -> {
            logger.error("[LEARNING] ${insight.message}")
            // TODO: Add pair to blocklist
        }
        InsightType.HIGH_SLIPPAGE -> {
            logger.warn("[LEARNING] ${insight.message}")
        }
        InsightType.GOOD_PAIR -> {
            logger.info("[LEARNING] ${insight.message}")
        }
        else -> {}
    }
}

// Log overall performance
val performance = tradeLedger.getOverallPerformance()
logger.info("""
    [LEARNING_SUMMARY]
    Total trades: ${performance.totalTrades}
    Win rate: ${performance.winRate.format(1)}%
    Total profit: Rp${performance.totalProfitIdr.format(0)}
    Total fees: Rp${performance.totalFeesIdr.format(0)}
""".trimIndent())
```

---

## 🎯 EXAMPLE USE CASE: BR/IDR PUMP

Dari screenshot lu, BR/IDR lagi pump. Begini bot harus handle:

```
1. Kinance detect volume explosion di Binance:
   "BR/USDT volume +500%, price +30%"
   → Send UDP signal

2. KiDax terima signal, check BR/IDR di Indodax:
   - BR/IDR udah pump 50%
   - latePumpEntry.evaluateLatePumpEntry():
     - canEnter: false
     - reason: "WAIT_FOR_PULLBACK"
     - analysis: "Pump running but wait for 5-15% pullback"
   
3. BR/IDR pullback 10% dari puncak:
   - latePumpEntry.evaluateLatePumpEntry():
     - canEnter: true ✅
     - reason: "HEALTHY_PULLBACK"
     - positionSizePct: 0.5 (half size)
     - stopLossPct: 4% (tight stop)
     - takeProfitPct: 10% (quick profit)

4. KiDax submit buy:
   - Strategy: ANOMALY (dari 20% pool)
   - Size: 50% of normal (safer)
   - Stop loss: 4%
   - Target: 10%
   
5. Broadcast ke semua bot:
   "KiDax bought BR at 5500, target 6050, stop 5280"
   
6. Kinance & KiCryp monitor:
   - Kinance: Watch Binance BR/USDT for dump signal
   - KiCryp: Track profit/loss real-time
   
7. BR/IDR naik 10% → Hit target:
   - KiDax sell
   - tradeLedger.recordTrade():
     - Entry: 5500, Exit: 6050
     - Fee: 0.6%
     - Profit: +9.4% ✅
     - Strategy: ANOMALY
   
8. Learning:
   - "BR responded well to pullback entry"
   - "ANOMALY strategy worked, repeat!"
```

---

## 🚦 DEPLOYMENT CHECKLIST

Sebelum deploy, pastikan:

- [ ] Wire position broadcast di BUY logic
- [ ] Wire position broadcast di SELL logic
- [ ] Wire late pump entry di entry decision
- [ ] Wire heartbeat monitoring di main loop
- [ ] Wire capital allocation check sebelum entry
- [ ] Wire learning insights setelah trades
- [ ] Update .env untuk enable UDP broadcast
- [ ] Test UDP connectivity antar server
- [ ] Build & deploy ke Indodax server
- [ ] Build & deploy ke Binance server
- [ ] Monitor logs untuk inter-bot communication

---

## 📝 CATATAN PENTING

1. **Jangan panic sell**: Bot tetap respect strict guardrail - NO PANIC SELL ON TIMEOUT
2. **Learning harus cepat**: TradeLedger track semua trade, bot belajar dari kesalahan
3. **Communication is CRITICAL**: Semua bot harus sync state via UDP
4. **20/80 capital split**: WAJIB enforce, jangan sampai anomaly pool habis
5. **Late pump entry**: Jangan takut masuk, tapi harus SMART timing

---

## 🔥 NEXT ACTIONS

Lu perlu:
1. Wire 6 integrations di atas ke MacEngineDaemon
2. Build shadow JAR: `./gradlew :apps:mac-engine:shadowJar`
3. Deploy ke both servers
4. Restart services
5. Monitor logs untuk inter-bot communication
6. Analyze trading performance dengan learning insights

Gw udah bikin foundation nya, tinggal wire ke trading logic!
