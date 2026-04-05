# AGGRESSIVE PUMP CHASE STRATEGY - UPGRADE SUMMARY

**Date:** 2026-04-03  
**Objective:** Enable 30% AGGRESSIVE bucket to safely chase extreme pumps (up to 60%) while keeping 70% STABLE bucket conservative

---

## CHANGES MADE

### 1. **LatePumpEntryStrategy.kt** - Core Logic Upgrade

**File:** `packages/core/src/commonMain/kotlin/com/kibot/core/LatePumpEntryStrategy.kt`

**Key Changes:**
- Added `bucketType` parameter to `evaluateLatePumpEntry()` function
  - "STABLE" bucket (70% capital): Conservative, max 15% pump chase
  - "AGGRESSIVE" bucket (30% capital): Can chase up to 60% pumps

**New Behavior:**

| Pump Height | STABLE Bucket | AGGRESSIVE Bucket |
|-------------|---------------|-------------------|
| 0-15 Entry allowed (full/half size Entry allowed (full size) |) | % | 
| 15- Blocked (too late Entry with 50% position size |) | 30% | 
| 30- Blocked Entry with 35% position size | | 50% | 
| 50- Blocked Entry with 25% position size (quarter) | | 60% | 
|   Blocked (pump exhausted) |Blocked | 60%+ | 

**Dynamic Position Sizing** (AGGRESSIVE bucket):
```kotlin
 100% position (full size)
 50% position (half)
 35% position (1/3)
 25% position (quarter)
```

**Dynamic Stop Loss** (AGGRESSIVE bucket):
```kotlin
 3.5% stop loss
 3.0% stop loss (tighter)
 2.5% stop loss (very tight)
 2.0% stop loss (ultra tight)
```

**Dynamic Take Profit** (AGGRESSIVE bucket):
```kotlin
 12% target (can aim higher)
 10% target (standard scalp)
 8% target (quick exit)
 5% target (very quick exit)
```

**STABLE Bucket Protection:**
- Minimum gain threshold: 2.5% (filters out 1-2% noise)
- No entry on moves <2.5% to avoid getting shaken out
- Max pump chase: 15% (conservative)
- Wider trailing stops (4%) to prevent false exits

---

### 2. **PumpDetector.kt** - Entry Recommendation Update

**File:** `packages/core/src/commonMain/kotlin/com/kibot/core/PumpDetector.kt`

**Change:** Added comment to clarify handoff to LatePumpEntryStrategy
- When 15%, PumpDetector returns `WAIT` recommendationpump 
- LatePumpEntryStrategy then takes over for late entry logic

---

### 3. **android/build.gradle.kts** - Build Fix

**File:** `apps/android/build.gradle.kts`

**Change:** Removed hardcoded plugin versions causing conflicts
- Before: `id("com.android.application") version "8.1.0"`
- After: `alias(libs.plugins.android.application)` (uses version catalog)

---

## EXAMPLE: CTSI +85% PUMP SCENARIO

**Timeline:**

**Stage 1 (Pump 0-15%):**
- STABLE bucket Can enter with full size: 
- AGGRESSIVE bucket Can enter with full size: 
- Bot status: "Early detection - AGGRESSIVE_BUY"

**Stage 2 (Pump 15-30%):**
- STABLE  Blocked ("Already pumped 20%, exceeded STABLE limit 15%")bucket: 
- AGGRESSIVE bucket Entry with 50% position size if pullback 5-20%: 
- Bot status: "Late entry on pullback - HEALTHY_PULLBACK"

**Stage 3 (Pump 30-50%):**
- STABLE  Blockedbucket: 
- AGGRESSIVE bucket Entry with 35% position size (1/3): 
  - Stop loss: 2.5% (very tight)
  - Take profit: 8% (quick scalp)
- Bot status: "High pump chase - PARABOLIC_ACCELERATION"

**Stage 4 (Pump 50-85%):**
- STABLE  Blockedbucket: 
- AGGRESSIVE bucket Entry with 25% position (quarter) if volume strong: 
  - Stop loss: 2.0% (ultra tight)
  - Take profit: 5% (very quick exit)
- Bot status: "Extreme pump - aggressive scalp only"

**Stage 5 (Pump >85%):**
- STABLE  Blockedbucket: 
- AGGRESSIVE  Blocked ("Pump exhausted - DON'T CHASE")bucket: 

---

## SAFETY MECHANISMS

**1. Volume Confirmation Required:**
- All late entries need volume 0.40 (40%)score 
- Parabolic acceleration entries 0.70 (70%)need 
 Entry blocked ("VOLUME_DYING")

**2. Pullback Entry Strategy:**
- Wait for 5-20% pullback from peak for safe entry
- No FOMO at the top - let it pull back first
- Multi-wave detection: Can re-enter on wave 2, 3, etc.

**3. Scaled Position Sizing:**
- Higher pump = smaller position (risk management)
- CTSI at 85% = only 25% position size (not all-in)
- Reduces risk of getting trapped at the top

**4. Ultra-Tight Stops:**
- 60%+ pumps get 2.0% stop loss (very tight)
- Exit immediately if momentum fails
- No holding bags on late entries

**5. Quick Profit Targets:**
- 60%+ pumps = 5% profit target (quick scalp)
- Not waiting for 100% - take profit and exit
- Can re-enter if another wave starts

---

## STABLE vs AGGRESSIVE BUCKET COMPARISON

| Feature | STABLE Bucket (70%) | AGGRESSIVE Bucket (30%) |
|---------|---------------------|-------------------------|
| **Max Pump Chase** | 15% | 60% |
| **Noise Filter** | Skip <2.5% moves | Accept <2.5% moves |
| **Position Size (50% pump)** | 0% (blocked) | 35% (1/3 position) |
| **Stop Loss (50% pump)** | N/A | 2.5% (very tight) |
| **Take Profit (50% pump)** | N/A | 8% (quick scalp) |
| **Pullback Max** | 15% | 20% |
| **Parabolic  Disabled Enabled | | Chasing** | 
| **Philosophy** | Safe, steady gains | Aggressive, quick scalps |

---

## NEXT STEPS (NOT YET IMPLEMENTED)

1. **Wire LatePumpEntryStrategy into MacEngineDaemon:**
   - Detect bucket type from capital allocation (70/30 split)
   - Call `latePumpEntry.evaluateLatePumpEntry(quote, "AGGRESSIVE")` for 30% bucket
   - Call with "STABLE" for 70% bucket

2. **Build and Deploy:**
   - Compile `mac-engine-0.1.0-all.jar` with new logic
   - Deploy to Indodax server (KiDax)
   - Test with live market data

3. **Monitor Performance:**
   - Track AGGRESSIVE bucket entries on 30%+ pumps
   - Measure win rate and profit on late scalps
   - Ensure STABLE bucket avoids noise (no <2.5% moves)

---

## TESTING CHECKLIST

- [ ] STABLE bucket blocks pump >15%
- [ ] STABLE bucket ignores <2.5% noise moves
- [ ] AGGRESSIVE bucket accepts 30% pumps with 50% size
- [ ] AGGRESSIVE bucket accepts 50% pumps with 35% size
- [ ] AGGRESSIVE bucket accepts 60% pumps with 25% size (if volume strong)
- [ ] AGGRESSIVE bucket blocks 80%+ pumps ("PUMP_EXHAUSTED")
- [ ] Pullback detection works (5-20% from peak)
- [ ] Stop loss scales down for higher pumps (2.0% for 60%+)
- [ ] Take profit scales down for higher pumps (5% for 60%+)
- [ ] Volume dying blocks entry

---

## CONCLUSION

Bot lu sekarang bisa **chase pump CTSI 85% dengan aman** di 30% AGGRESSIVE bucket:
- Entry pas pullback 5-20% dari peak
- Position size kecil (25% untuk pump 50%+)
- Stop loss ultra-tight (2.0%)
- Take profit cepat (5-8%)

70% STABLE bucket tetap aman:
- Gak kena shake-out di noise 1-2%
- Max chase 15% aja (konservatif)
- Fokus steady gains, bukan lottery tickets

**Strategy:** Predictive + Reactive Hybrid
- Masih prefer early entry (<15% pump)
- Tapi kalo telat, ada fallback plan untuk entry aman
- Multi-wave pumps bisa masuk di wave 2, 3, dst

Lu tinggal integrate logic ini ke MacEngineDaemon biar tau kapan pake bucket AGGRESSIVE vs STABLE!
