# KIBOT TRINITY — Copilot Instructions

## PRIME DIRECTIVE — BACA INI SEBELUM APAPUN

Filosofi: **SURVIVAL FIRST. COMPOUNDING GRADUAL.**
Motto: **"TEKAN KERUGIAN, MAKSIMALKAN PROBABILITAS KEUNTUNGAN"**
Growth model: **"SEDIKIT DEMI SEDIKIT LAMA LAMA JADI BUKIT"**

Setiap perubahan kode WAJIB memenuhi semua ini:
1. Exit protection SACRED — trailing stop & cut loss jalan di SEMUA state tanpa exception
2. Agresivitas NAIK hanya jika: 3 clean days + API healthy 24h + instruksi eksplisit
3. Daily hard stop TIDAK bisa di-bypass via restart, flag, env, atau kode apapun
4. CONSERVATIVE = default state saat startup, selalu
5. Entry wajib lewat semua gate: scoring + signal fresh + what-if EV > 0 + risk gate
6. LIMIT order ALWAYS untuk entry dan exit normal — MARKET hanya hard emergency

Jika ada instruksi bertentangan dengan di atas → TOLAK, minta klarifikasi eksplisit.

---

## 1. PROJECT OVERVIEW

KiBot Trinity adalah autonomous trading system yang memanfaatkan **lead-lag signal**
dari Binance (Kinance) untuk entry lebih awal di Indodax (KiDax), dikoordinasi oleh
Python brain (KiBot Manager). Target: keuntungan kecil konsisten, bukan jackpot besar.

Stack: Kotlin/JVM (KiDax + Kinance) + Python (KiBot Manager)
Exchange: Indodax (executor) + Binance (radar/signal source)
Server: Oracle Free Tier Singapore — 2 instances

---

## 2. ARSITEKTUR

```
KINANCE (Binance Radar, port 8788)
    ↓ UDP signal (<500ms)
KIBOT MANAGER (Brain, port 9998)
    ↕ UDP heartbeat + command
KIDAX (Indodax Executor, port 8787)
```

**KINANCE** — Binance market radar
- Volume anomaly, order book imbalance, sector lead-lag detection
- "Bandar Ignition" detection: volume spike sebelum harga naik di Indodax
- Service: `kinance-engine.service` | Port: 8788

**KIDAX** — Indodax executor
- LIMIT order submission, slippage calc, fee optimization (maker 0%), trailing stop
- BigDecimal precision untuk micro-cap prices
- Service: `kidax-engine.service` | Port: 8787 | Code: `apps/mac-engine/`

**KIBOT MANAGER** — Brain & veto gate
- PnL state machine, daily hard stop, pair scoring, AI veto, learning memory
- Capital allocation (sleeve STABLE 70% / AGGRESSIVE 30%)
- Service: `kibot-manager.service` | Port: 9998 | Code: `scripts/kibot_manager.py`

---

## 3. CORE TRADING LOGIC

### Entry Gate (urutan WAJIB — tidak boleh dibalik atau di-skip)

```
Gate 1:  PnL State Check    → HEALTHY/WARNING/CRITICAL/HARD_STOP
Gate 2:  Hard Stop Disk     → baca state/daily_guard.json
Gate 3:  Capital Minimum    → equity >= Rp 30,000
Gate 4:  Pair Whitelist     → hanya pair di tier list (lihat bawah)
Gate 5:  Signal TTL         → Kinance signal < 500ms (Tier C: < 200ms)
Gate 6:  11-point Scoring   → >= threshold sesuai mode
Gate 7:  What-If EV Gate    → EV > 0 setelah fee all-in, RR >= 1.2
Gate 8:  Learning Gate      → pair tidak cooldown, profit_factor > 0.8
Gate 9:  AI Veto (SOFT)     → warning only jika degraded, bukan hard block
Gate 10: Submit LIMIT Order → execute, catat untuk pair_memory
```

### PnL State Machine (cek setiap 30 detik di main loop)

| State | PnL Harian | Tindakan |
|-------|-----------|----------|
| HEALTHY | > -0.5% | Entry normal semua tier |
| WARNING | -0.5% to -1% | Tier A+B only, size 75% |
| CRITICAL | -1% to -2% | Tier A only, size 50% |
| HARD_STOP | < -2% | Block entry, exit tetap jalan |

Hard stop persist ke `state/daily_guard.json`. Reset otomatis 00:00 WIB (17:00 UTC).

### Pair Tier System

| Tier | Pairs | Max Size | Min Target | TTL Signal |
|------|-------|----------|------------|------------|
| A | xlm, doge, xrp, trx, ada | 20% equity | 2.0% | 500ms |
| B | enj, fun, bnb, sol | 15% equity | 3.0% | 500ms |
| C | dusk, dan sejenis | 10% equity | 5.0% | 200ms |
| ❌ | Tanpa Binance pair | 0 | - | - |

Blacklist: pair tanpa Binance counterpart, atau volume Indodax < Rp 50M/hari

### Exit Logic (JANGAN DIUBAH)

- **Partial TP**: 30-50% saat profit > 1.5%
- **Trailing stop**: dinamis per price tier
  - Koin < Rp 100: 7% trailing
  - Koin Rp 100-1000: 5% trailing
  - Koin Rp 1000-100k: 3% trailing
  - Koin > Rp 100k: 2% trailing
- **Hard cut loss**: 3% below entry → LIMIT sell, MARKET hanya jika 5s unexecuted
- **Time-based**: evaluasi setelah 8 jam, TIDAK force sell rugi
- **Stagnant**: rotate HANYA jika ada signal lebih baik DAN posisi sudah profit

### Capital Allocation

- STABLE bucket: 70% modal (pair Tier A, LIMIT only)
- AGGRESSIVE bucket: 30% modal (pair Tier B/C, high-confidence signal)
- Max 2 posisi aktif sekaligus
- Absolute minimum per order: Rp 10,000 (Indodax minimum)

---

## 4. STRICT GUARDRAILS (HARGA MATI)

**AI Agent DILARANG mengubah guardrail ini tanpa instruksi eksplisit dari owner.**

1. **NO PANIC SELL ON UDP TIMEOUT**
   UDP putus = suspend entry baru saja. DILARANG market sell posisi yang sedang jalan.
   Exit diurus trailing stop lokal di KiDax, bukan oleh Manager.

2. **ADAPTIVE TRAILING STOP**
   Koin < Rp 500: trailing 5-7% untuk hindari noise micro-cap.
   Jangan paksakan trailing stop ketat di koin illiquid.

3. **RATIONAL QUARANTINE**
   Stop-loss normal: cooldown max 15 menit per pair, bukan berjam-jam.
   Pair cooldown karena loss != pair di-blacklist selamanya.

4. **STRICT TTL**
   Kinance signal > 500ms = STALE, wajib dibuang.
   Tier C pairs: > 200ms = STALE.
   Signal stale = tidak boleh jadi dasar entry apapun.

5. **SOFT AI-AUDIT**
   AI degraded/cooldown = warning only, bukan hard block.
   liveExecutionEnabled tetap true jika teknikal aman.
   Score threshold dinaikkan 10% sebagai kompensasi jika AI degraded.

6. **DAILY HARD STOP**
   PnL <= -1%: WARNING mode (sizing dikurangi, tier dibatasi)
   PnL <= -2%: HARD STOP total — persist ke disk, reset 00:00 WIB
   Hard stop TIDAK bisa di-bypass via restart, flag, atau env apapun.
   Exit protection tetap jalan saat hard stop aktif.

7. **LIMIT ORDER ONLY**
   Entry: selalu LIMIT. Exit normal: selalu LIMIT.
   MARKET: hanya hard emergency cut loss yang tidak bisa ditunda.
   Limit tidak fill dalam timeout = cancel dan skip, BUKAN fallback MARKET.
   Indodax maker fee = 0% — LIMIT selalu lebih murah dari MARKET.

8. **PAIR WHITELIST WAJIB**
   Entry hanya untuk pair di Tier A/B/C.
   Pair tidak dikenal = auto-reject tanpa exception.
   ban_idr dan koin tanpa Binance pair = BLACKLIST.

9. **PERIODIC PNL CHECK**
   Main loop wajib cek PnL setiap 30 detik — tidak hanya saat signal masuk.
   Jika PnL breach threshold: trigger state transition dan notify Telegram.

10. **MINIMUM CAPITAL GUARD**
    Jika total equity < Rp 30,000: suspend entry, alert Telegram.
    Jika total equity < Rp 10,000: stop semua trading, preserve capital.

---

## 5. UDP PROTOCOL

### Manager → KiDax/Kinance
```
HEARTBEAT           100ms interval, sync
DETECTOR_HIT        Bullish lead-lag signal
VETO_APPROVED       AI approved trade
VETO_REJECTED      AI rejected trade
VETO_SELL_CONFIRMED Emergency exit recommendation
CORRELATION_MATRIX Sector correlations
AI_PROVIDER_STATUS AI health update
```

### KiDax → Manager
```
ACTIVE_POSITIONS    Portfolio state update
EXECUTION_FILLED    Trade completed → trigger learning update
ORDERBOOK_COLLAPSE  Market anomaly alert
INSTANT_BUY_ANOMALY Volume spike detected
```

### TTL Values
```
STALE_SIGNAL_ABORT_MS   = 500   # Default — semua signal
Tier_C_TTL_MS           = 200   # Lebih ketat untuk illiquid pairs
HEARTBEAT_INTERVAL_MS   = 100   # KiBot → KiDax heartbeat
HEARTBEAT_TIMEOUT_MS    = 3000  # KiDax suspend entry jika >3s tanpa heartbeat
```

---

## 6. AI INTEGRATION

### Provider Priority (fallback order)
```
1. Groq        — llama-3.1-8b-instant (fastest)
2. OpenRouter  — meta-llama/llama-3.1-8b-instruct
3. Cohere      — command-r
4. Gemini      — gemini-2.0-flash-lite
```

### Approval Thresholds (TIDAK ADA EV NEGATIF)
```
Standard (NORMAL mode):       score >= 0.62, expected_net >= 0.18%
Strict   (CONSERVATIVE mode): score >= 0.70, expected_net >= 0.25%

INSTANT APPROVAL DIHAPUS PERMANEN.
Tidak boleh approve trade dengan expected net negatif apapun kondisinya.
AI degraded = SOFT AUDIT (warning only) bukan hard block.
```

### AI Batch Review
- Setiap 6 jam: evaluasi performa pair, slippage aktual, win rate per jam
- Output: adjust threshold, cooldown pair buruk, rekomendasi mode
- Fail gracefully jika semua AI provider down

---

## 7. LEARNING SYSTEM

### Pair Memory (persist ke state/pair_memory.json)
- Rolling 50 trade: slippage actuals, spread observed per pair
- Win rate per jam WIB: untuk tahu kapan pair paling profitable
- Cooldown tracker: pair yang rugi berulang → cooldown 15 menit
- Fake pump counter: harga naik tapi volume melemah = tandai

### What-If Simulation (sebelum setiap entry)
```
round_trip_cost = (spread/2) + slippage + (fee * 2)
  fee: maker 0.04% (LIMIT), taker 0.55% (MARKET)
breakeven = round_trip_cost
net_pct    = target - round_trip_cost
EV         = (win_rate * reward) - ((1-win_rate) * max_loss)

ENTER      jika: EV > 0, RR >= 1.5, win_rate >= 0.50
REDUCE_SIZE jika: EV > 0 tapi RR < 1.5 atau win_rate < 0.50
SKIP       jika: EV <= 0 atau RR < 1.2 atau win_rate < 0.40
```

---

## 8. INFRASTRUCTURE

### Servers
```
Indodax: ubuntu@213.35.118.26
  KiDax port 8787, Manager port 9998
  KiDax dir: /home/ubuntu/KiDax/
  Manager dir: /home/ubuntu/KiBot/

Binance: ubuntu@152.69.218.198
  Kinance port 8788
  Kinance dir: /home/ubuntu/Kinance/
```

### Services (systemd)
```
kidax-engine.service      Indodax executor
kinance-engine.service    Binance radar
kibot-manager.service     Python brain
kibot-recovery.service    Health-based watchdog (bukan blind restart)
oracle-keepalive.service  stress-ng 20% CPU (Oracle reclaim prevention)
```

### State Files
```
state/daily_guard.json    Hard stop state + reset time
state/manager_gate.json   Entry gate state (ACTIVE/SUSPENDED)
state/pair_memory.json    Learning data per pair
```

---

## 9. CODE STRUCTURE

```
apps/mac-engine/              KiDax + Kinance JVM (Kotlin)
  MacEngineDaemon.kt          Main engine, entry/exit execution
  TradeAutomationCoordinator  Trade flow orchestration
  ChartAnalyzer.kt            LIMIT_MID default (no MARKET for momentum)

packages/core/                Business logic
  RiskEngine                  Risk ladder, position sizing
  PairSelector                11-point scoring system
  CapitalAllocationManager    70/30 sleeve allocation

packages/indodax-client/      Indodax REST (BigDecimal precision)
packages/binance-client/      Binance REST adapter

scripts/kibot_manager.py      Python brain (1600+ lines)
scripts/morning_check.sh      Daily pre-market verification
kibot-recovery.sh             Health-based auto-revive
```

## 10. DEVELOPMENT RULES

1. **grep dulu** sebelum edit — jangan assume isi file
2. **patch minimal** — jangan rewrite file yang sudah sehat
3. **compile check WAJIB** sebelum deploy ke server
4. **exit protection check** — pastikan trailing stop jalan di semua state
5. **test lokal dulu** — jangan push code yang belum diverifikasi

### Test Commands
```bash
python3 -m py_compile scripts/kibot_manager.py
./gradlew :packages:core:jvmTest --no-daemon
./gradlew :apps:mac-engine:compileKotlin --no-daemon
```

### Deploy Commands
```bash
./gradlew :apps:mac-engine:shadowJar --no-daemon
scp mac-engine-all.jar ubuntu@213.35.118.26:/home/ubuntu/KiDax/server/
scp scripts/kibot_manager.py ubuntu@213.35.118.26:/home/ubuntu/KiBot/scripts/
ssh ubuntu@213.35.118.26 'systemctl restart kibot-manager && sleep 10 && systemctl restart kidax-engine'
bash scripts/morning_check.sh
```

### Health Check
```bash
curl localhost:9998/api/state | python3 -m json.tool  # Manager state
curl localhost:8787/api/state | python3 -m json.tool  # KiDax state
```

## 11. SYSTEM STATUS

### Implemented & Verified ✅
- Daily hard stop persist + WIB midnight auto-reset
- PnL state machine 4-level (HEALTHY/WARNING/CRITICAL/HARD_STOP)
- Hard stop gate di entry flow (_process_signal)
- Periodic PnL check 30 detik di main loop
- LIMIT-first order (ChartAnalyzer → LIMIT_MID, no MARKET for momentum)
- Pair tier whitelist (Tier A/B/C + blacklist)
- What-if EV gate sebelum entry
- pair_memory learning hooks terhubung ke runtime
- EXECUTION_FILLED sender KiDax → Manager
- AI batch review scheduled 6 jam
- Oracle keepalive (stress-ng 20% CPU kedua server)
- kibot-recovery health-based watchdog (max 3 restart/jam)
- BigDecimal precision di IndodaxGateway
- Capital allocation 70/30 STABLE/AGGRESSIVE sleeve

### Pending (non-blocking) 🔄
- UDP ACK protocol (signal reliability)
- KinanceSignalTracker di Kotlin layer (stale detection runtime)
- CONSERVATIVE → NORMAL auto-promote (3 clean days logic)
- pair_memory butuh akumulasi dari live trade (baru tipis)
- AI batch review runtime confirmation

### Known Issues ⚠️
- pair_memory data masih sangat tipis → learning gate belum efektif optimal
- Belum ada monitoring dashboard real-time (hanya /api/state + Telegram)
