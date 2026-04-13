# 🛠️ SONNET IMPLEMENTATION BRIEF — Trinity Bot Fixes
**Date:** 2026-04-06 | **Priority:** CRITICAL  
**Auditor:** Opus 4.5 | **Executor:** Sonnet  

---

## 🎯 MISI

Bot trading Trinity **PASIF** dan **MELEWATKAN MARKET** karena threshold terlalu ketat. Tugas Sonnet: FIX semua masalah agar bot AKTIF dan OTONOM.

---

## 📋 TASK LIST (URUTAN EKSEKUSI)

### ✅ SELESAI (Opus Session)
- [x] Fix Market Quote Fetch (ContentEncoding gzip)
- [x] Deploy ke server dan verifikasi log

### 🔴 TASK 1: Lower AI Confidence Thresholds
**File:** `scripts/kibot_manager.py`

| Line | Parameter | Current | Target |
|------|-----------|---------|--------|
| 73 | `STALE_SIGNAL_ABORT_MS` | 1500 | **3500** |
| 74 | `FOMO_GUARD_PCT` | 15.0 | (dynamic, see Task 2) |
| 91 | `AI_APPROVAL_MIN_SCORE` | 0.62 | **0.62** |
| 92 | `AI_APPROVAL_MIN_EXPECTED_NET_PCT` | 0.18 | **0.08** |

**Caranya:**
```python
# Line 73
STALE_SIGNAL_ABORT_MS = 3500  # was 1500

# Line 91-92
AI_APPROVAL_MIN_SCORE = 0.62  # standard mode
AI_APPROVAL_MIN_EXPECTED_NET_PCT = 0.08  # was 0.18
```

---

### 🔴 TASK 2: Dynamic FOMO_GUARD by Price Tier
**File:** `scripts/kibot_manager.py` (around line 1176 in `_process_signal`)

**Logic baru:**
```python
def _get_dynamic_fomo_guard(price_idr: float) -> float:
    """
    Micro-cap (< Rp50): Boleh pump sampai 35% karena masih early
    Mid-cap (Rp50-500): Standard 22%
    Big-cap (> Rp500): Ketat 15%
    """
    if price_idr < 50.0:
        return 35.0
    elif price_idr < 500.0:
        return 22.0
    else:
        return 15.0

# Ganti pengecekan FOMO_GUARD yang static menjadi:
fomo_limit = _get_dynamic_fomo_guard(current_price)
if gain_pct > fomo_limit:
    print(f"[FOMO_GUARD] Skipping {pair}: gain {gain_pct:.1f}% > limit {fomo_limit:.1f}%")
    return
```

---

### 🔴 TASK 3: Add KINANCE Heartbeat Monitoring
**File:** `scripts/kibot_manager.py`

**Tambah di global variables (sekitar line 100):**
```python
# === KINANCE HEALTH MONITORING ===
KINANCE_HEARTBEAT_TIMEOUT_SEC = 10.0
_last_kinance_heartbeat_at: float = 0.0
_kinance_healthy: bool = True
```

**Tambah function baru:**
```python
def _on_kinance_heartbeat_received():
    """Called when heartbeat UDP packet received from Kinance"""
    global _last_kinance_heartbeat_at, _kinance_healthy
    _last_kinance_heartbeat_at = time.time()
    if not _kinance_healthy:
        print("[KIBOT][RECOVERY] KINANCE heartbeat restored!", flush=True)
    _kinance_healthy = True

def _check_kinance_health() -> bool:
    """Returns True if Kinance is healthy (heartbeat within timeout)"""
    global _kinance_healthy
    now = time.time()
    if _last_kinance_heartbeat_at == 0.0:
        return True  # First run, assume healthy
    
    if (now - _last_kinance_heartbeat_at) > KINANCE_HEARTBEAT_TIMEOUT_SEC:
        if _kinance_healthy:
            print(f"[KIBOT][CRITICAL] KINANCE HEARTBEAT LOST! Last seen {now - _last_kinance_heartbeat_at:.1f}s ago", flush=True)
            _kinance_healthy = False
        return False
    return True
```

**Modifikasi `_process_signal` (around line 1081):**
```python
def _process_signal(msg: dict) -> None:
    # === EARLY RETURN IF KINANCE DEAD ===
    if not _check_kinance_health():
        msg_type = msg.get("msgType", "")
        # Only allow EXIT signals when Kinance unhealthy
        if msg_type not in {"SELL_WALL_SURGE", "MOMENTUM_LOSS", "TRAILING_STOP_HIT"}:
            print(f"[KIBOT][BLOCK] Blocking {msg_type} - KINANCE unhealthy", flush=True)
            return
    
    # ... rest of existing code
```

**Handle heartbeat di UDP listener:**
```python
# Di dalam UDP receive loop, tambahkan:
if msg.get("msgType") == "HEARTBEAT" and msg.get("source") == "kinance":
    _on_kinance_heartbeat_received()
    continue  # Don't process heartbeat as signal
```

---

### 🔴 TASK 4: Boost Low Price Bias
**File:** `packages/core/src/commonMain/kotlin/com/kibot/core/PairSelector.kt`

**Lines 185-195, ubah nilai:**
```kotlin
// CURRENT (terlalu kecil)
val lowPriceBias = when {
    urgencyLevel >= 0.75 -> 0.14  // urgent
    else -> 0.10                   // normal
}

// CHANGE TO (2x boost)
val lowPriceBias = when {
    urgencyLevel >= 0.75 -> 0.28  // urgent - was 0.14
    else -> 0.18                   // normal - was 0.10
}
```

---

### 🟡 TASK 5: Implement AlwaysInvestedPolicy (NEW FILE)
**File:** `packages/core/src/commonMain/kotlin/com/kibot/core/AlwaysInvestedPolicy.kt`

```kotlin
package com.kibot.core

import kotlin.time.Duration.Companion.minutes

/**
 * AlwaysInvestedPolicy — "Pantang Nganggur & Anti-Penakut"
 * 
 * Filosofi: Saldo menganggur = kerugian waktu
 * Bot WAJIB entry jika perhitungan matematik positif
 */
class AlwaysInvestedPolicy(
    private val indodaxFeePercent: Double = 0.51, // maker + taker average
    private val maxIdleCapitalPercent: Double = 0.15,
    private val maxIdleMinutes: Int = 30,
) {
    data class EntryDecision(
        val allowed: Boolean,
        val breakEvenPercent: Double,
        val expectedNetPercent: Double,
        val rationale: String,
    )
    
    /**
     * Hitung apakah entry mathematically profitable
     * Entry HANYA diblokir jika GUARANTEED LOSS
     */
    fun shouldEnter(
        expectedMovePercent: Double,
        spreadPercent: Double = 0.1,
        slippagePercent: Double = 0.05,
    ): EntryDecision {
        val totalEntryCost = indodaxFeePercent + (slippagePercent / 2)
        val totalExitCost = indodaxFeePercent + (slippagePercent / 2)
        val breakEven = totalEntryCost + totalExitCost + spreadPercent
        val expectedNet = expectedMovePercent - breakEven
        
        return EntryDecision(
            allowed = expectedNet >= 0.0, // ANY positive = GO
            breakEvenPercent = breakEven,
            expectedNetPercent = expectedNet,
            rationale = if (expectedNet >= 0) {
                "ENTER: Expected +${String.format("%.2f", expectedNet)}% after fees"
            } else {
                "BLOCK: Guaranteed loss of ${String.format("%.2f", -expectedNet)}%"
            }
        )
    }
    
    /**
     * Force rotation jika cash idle terlalu lama
     */
    fun shouldForceEntry(
        freeCapitalPercent: Double,
        idleMinutes: Int,
    ): Boolean {
        return freeCapitalPercent > maxIdleCapitalPercent && idleMinutes > maxIdleMinutes
    }
}
```

---

### 🟡 TASK 6: Create Supabase `dynamic_params` Table

**SQL untuk dijalankan di Supabase:**
```sql
CREATE TABLE IF NOT EXISTS dynamic_params (
    id SERIAL PRIMARY KEY,
    param_key VARCHAR(100) UNIQUE NOT NULL,
    param_value JSONB NOT NULL,
    updated_by VARCHAR(50) DEFAULT 'manual',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    description TEXT
);

-- Insert default values
INSERT INTO dynamic_params (param_key, param_value, description) VALUES
    ('trailing_stop_pct', '{"value": 1.5}', 'Trailing stop percentage'),
    ('volatility_threshold', '{"value": 8.0}', 'Max volatility threshold for stable bucket'),
    ('cooldown_minutes', '{"value": 5}', 'Cooldown after trade execution'),
    ('fomo_guard_micro', '{"value": 35.0}', 'FOMO guard for micro-cap coins'),
    ('fomo_guard_mid', '{"value": 22.0}', 'FOMO guard for mid-cap coins'),
    ('fomo_guard_big', '{"value": 15.0}', 'FOMO guard for big-cap coins'),
    ('ai_approval_min_score', '{"value": 0.62}', 'Minimum AI approval score'),
    ('ai_approval_min_net_pct', '{"value": 0.08}', 'Minimum expected net profit %');

-- Create index for fast lookups
CREATE INDEX idx_dynamic_params_key ON dynamic_params(param_key);
```

---

### 🟡 TASK 7: Implement DynamicConfigReloader (NEW FILE)
**File:** `packages/core/src/commonMain/kotlin/com/kibot/core/DynamicConfigReloader.kt`

```kotlin
package com.kibot.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration.Companion.seconds

/**
 * DynamicConfigReloader — Hot-Reload tanpa restart
 * 
 * Poll Supabase setiap 60 detik untuk config baru
 * Apply changes tanpa downtime
 */
class DynamicConfigReloader(
    private val controlPlane: ControlPlaneGateway,
    private val pollIntervalSeconds: Int = 60,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastConfigHash: Int = 0
    
    private val _currentParams = MutableStateFlow(DynamicParams())
    val currentParams: StateFlow<DynamicParams> = _currentParams
    
    data class DynamicParams(
        val trailingStopPct: Double = 1.5,
        val volatilityThreshold: Double = 8.0,
        val cooldownMinutes: Int = 5,
        val fomoGuardMicro: Double = 35.0,
        val fomoGuardMid: Double = 22.0,
        val fomoGuardBig: Double = 15.0,
        val aiApprovalMinScore: Double = 0.62,
        val aiApprovalMinNetPct: Double = 0.08,
    )
    
    fun startPolling(onConfigChange: (DynamicParams) -> Unit) {
        scope.launch {
            while (isActive) {
                try {
                    val params = fetchParams()
                    val hash = params.hashCode()
                    
                    if (hash != lastConfigHash && lastConfigHash != 0) {
                        println("[CONFIG_RELOAD] New params detected!")
                        println("[CONFIG_RELOAD] $params")
                        onConfigChange(params)
                    }
                    
                    _currentParams.value = params
                    lastConfigHash = hash
                    
                } catch (e: Exception) {
                    println("[CONFIG_RELOAD] Poll failed: ${e.message}")
                }
                
                delay(pollIntervalSeconds.seconds)
            }
        }
    }
    
    private suspend fun fetchParams(): DynamicParams {
        val rows = controlPlane.fetchTable("dynamic_params")
        return DynamicParams(
            trailingStopPct = rows.getDouble("trailing_stop_pct"),
            volatilityThreshold = rows.getDouble("volatility_threshold"),
            cooldownMinutes = rows.getInt("cooldown_minutes"),
            fomoGuardMicro = rows.getDouble("fomo_guard_micro"),
            fomoGuardMid = rows.getDouble("fomo_guard_mid"),
            fomoGuardBig = rows.getDouble("fomo_guard_big"),
            aiApprovalMinScore = rows.getDouble("ai_approval_min_score"),
            aiApprovalMinNetPct = rows.getDouble("ai_approval_min_net_pct"),
        )
    }
    
    fun stop() {
        scope.cancel()
    }
}
```

---

### 🟡 TASK 8: Integrate 70/30 Bucket with PairSelector
**File:** `packages/core/src/commonMain/kotlin/com/kibot/core/PairSelector.kt`

**Add enum:**
```kotlin
enum class BucketType {
    STABLE,      // 70% bucket — low volatility, steady growth
    AGGRESSIVE,  // 30% bucket — anomaly/pump targets
}
```

**Modify PairScore data class:**
```kotlin
data class PairScore(
    val pair: String,
    val score: Double,
    val components: ScoreComponents,
    val bucketType: BucketType,  // ADD THIS
)
```

**Add classification function:**
```kotlin
private fun classifyBucket(
    quote: MarketQuote,
    finalScore: Double,
): BucketType {
    return when {
        // Aggressive indicators
        quote.shortTermReturnPct > 10.0 -> BucketType.AGGRESSIVE
        quote.realizedVolatilityPct > 8.0 -> BucketType.AGGRESSIVE
        quote.anomalyScore > 0.7 -> BucketType.AGGRESSIVE
        
        // Stable indicators
        finalScore >= 0.65 && quote.realizedVolatilityPct < 5.0 -> BucketType.STABLE
        
        // Default to stable (safer)
        else -> BucketType.STABLE
    }
}
```

---

## 🖥️ SERVER INFO

| Node | IP | Key Path | Deploy Path |
|------|-----|----------|-------------|
| KiDax | 213.35.118.26 | `SSH_INDODAX/ssh-key-2026-03-22.key` | `/home/ubuntu/KiDax/` |
| Kinance | 152.69.218.198 | `SSH_BINANCE/ssh-key-2026-03-27.key` | `/home/ubuntu/Kinance/` |

**SSH Commands:**
```bash
# KiDax
ssh -i SSH_INDODAX/ssh-key-2026-03-22.key ubuntu@213.35.118.26

# Kinance
ssh -i SSH_BINANCE/ssh-key-2026-03-27.key ubuntu@152.69.218.198
```

**Restart Services:**
```bash
# After kibot_manager.py changes:
sudo systemctl restart kibot-manager

# After Kotlin changes (rebuild JAR first):
./gradlew :apps:mac-engine:shadowJar
scp apps/mac-engine/build/libs/mac-engine-all.jar ubuntu@213.35.118.26:/home/ubuntu/KiDax/server/
ssh ubuntu@213.35.118.26 "sudo systemctl restart kidax-engine"
```

---

## ✅ VERIFICATION CHECKLIST

Setelah setiap task, **WAJIB** verifikasi dengan log:

```bash
# Check kibot_manager logs
ssh ubuntu@213.35.118.26 "journalctl -u kibot-manager -f --no-pager -n 50"

# Check kidax logs
ssh ubuntu@213.35.118.26 "journalctl -u kidax-engine -f --no-pager -n 50"

# Check kinance logs
ssh ubuntu@152.69.218.198 "journalctl -u kinance-engine -f --no-pager -n 50"
```

**Expected After Fix:**
- Signal approval rate: **>45%** (was ~15%)
- Entry attempts per hour: **>10** (was ~2)
- Log: `[ENTRY] Approved: expected net +X.XX%`
- NO MORE: `[REJECTED] AI confidence too low`

---

## 📊 SUCCESS METRICS

| Metric | Before | Target |
|--------|--------|--------|
| Signal approval rate | 12-18% | **>45%** |
| Daily micro-cap entries | 2-4 | **>12** |
| Manual restarts needed | Yes | **Zero** |
| Config change downtime | Minutes | **Zero** |
| KINANCE crash detection | Never | **<10 sec** |

---

## ⚠️ RULES FOR SONNET

1. **JANGAN ASSUME** — Selalu verifikasi dengan log setelah deploy
2. **BACKUP DULU** — `cp file file.bak` sebelum edit
3. **TEST LOKAL DULU** — `./gradlew build` harus pass
4. **SATU TASK SATU COMMIT** — Jangan campur changes
5. **LOG PROOF REQUIRED** — Task belum selesai tanpa bukti log dari server

---

**END OF BRIEF — Selamat Mengerjakan! 🚀**
