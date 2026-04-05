# CAPITAL ALLOCATION - INTEGRATION TODO

## ✅ DONE (Phase 1)
- CapitalAllocationManager initialized in syncOnce()
- 70/30 split configured
- Manager tracks current allocations

## ⚠️ TODO (Phase 2 - Critical for Full 20% Impact)

### 1. Add Allocation Call Before Entry
**Location:** Find entry execution logic (where budget is calculated)

Search for methods like:
```bash
grep -n "executeMarketBuy\|submitBuyOrder" MacEngineDaemon.kt
```

Add before position creation:
```kotlin
val isAnomalyCoin = // Detect if pump/anomaly based on signal
val allocResult = capitalAllocationManager?.allocate(
    isAnomalyCoin = isAnomalyCoin,
    requestedAmountIdr = budgetIdr
)

if (allocResult != null) {
    val actualBudget = allocResult.allocatedIdr
    val bucketType = allocResult.bucketType  // "STABLE" or "AGGRESSIVE"
    
    // Use actualBudget instead of budgetIdr
    // Store bucketType in position metadata
    
    if (allocResult.requiresRebalance) {
        repository.noteStatus(allocResult.rebalanceMessage ?: "Rebalance needed")
    }
}
```

### 2. Add Profit Deposit on Exit
**Location:** Find exit/sell execution logic

Add after position closed:
```kotlin
val profit = exitValue - entryValue  // Net after fees
val wasAggressive = position.bucketType == "AGGRESSIVE"

capitalAllocationManager?.depositProfit(
    profitIdr = profit,
    wasAggressiveTrade = wasAggressive
)
```

### 3. Detect Anomaly Coins
Add logic to determine if coin is pump/anomaly:

```kotlin
fun isAnomalyCoin(
    pairId: String,
    signals: List<Signal>,
    pumpConfidence: Double
): Boolean {
    // From VetoService lead-lag signals
    val hasLeadLagSignal = signals.any { it.type == "LEAD_LAG" }
    
    // From PumpDetector
    val isPumpDetected = pumpConfidence > 0.75
    
    // From anomaly detection
    val hasVolumeAnomaly = signals.any { it.type == "VOLUME_ANOMALY" }
    
    return hasLeadLagSignal || isPumpDetected || hasVolumeAnomaly
}
```

### 4. Store Bucket Type in Position
Modify position creation to include:

```kotlin
data class ManagedPosition(
    // existing fields...
    val bucketType: String = "STABLE",  // "STABLE" or "AGGRESSIVE"
)
```

### 5. Test Integration
After implementing:

```kotlin
// Check allocation status
val status = capitalAllocationManager?.getStatus()
logger.info("Capital Status: Stable=${status.stablePercent}%, Aggressive=${status.aggressivePercent}%")

// Verify drift detection
if (status.requiresRebalance) {
    logger.warn("Drift detected: ${status.driftPercent}%")
    capitalAllocationManager?.rebalance()
}
```

## 📊 Expected Results After Full Integration

### Allocation Tracking
```
[CAPITAL ALLOCATION] Stable: Rp33,250 (70.1%) | Aggressive: Rp14,250 (29.9%)
[ENTRY] BTC/IDR allocated Rp8,000 from STABLE bucket
[ENTRY] DOGE/IDR allocated Rp3,000 from AGGRESSIVE bucket (pump detected)
[EXIT] BTC/IDR profit +Rp144 deposited to STABLE bucket
[REBALANCE] Drift 6.2% detected, rebalancing to 70/30...
```

### Position Metadata
```json
{
  "pairId": "btc_idr",
  "entry": 1450000,
  "budget": 8000,
  "bucketType": "STABLE",
  "targetProfit": 0.018
}
```

## 🎯 Integration Points to Find

Run these searches to locate integration points:

```bash
# Find entry logic
grep -n "quoteBudget\|budgetIdr.*=" MacEngineDaemon.kt | head -20

# Find exit logic  
grep -n "closePosition\|sellOrder\|exitPrice" MacEngineDaemon.kt | head -20

# Find position creation
grep -n "ManagedPosition\|LocalManagedPositionState" MacEngineDaemon.kt | head -20
```

## ⏱️ Estimated Time
- Find integration points: 15 min
- Implement allocation calls: 30 min
- Test & verify: 15 min
- **Total: 1 hour**

## 🚨 Critical
WITHOUT Phase 2, bot has manager but doesn't USE it.  
WITH Phase 2, full 20% efficiency gain unlocked.

Deploy Phase 1 now, complete Phase 2 ASAP.
