# KIBOT TRINITY — Copilot Instructions

## PRIME DIRECTIVE

Sistem ini mengikuti filosofi: SURVIVAL FIRST, COMPOUNDING GRADUAL.

Setiap perubahan kode HARUS:
1. Tidak melemahkan exit protection; trailing stop dan cut loss tetap jalan di semua state.
2. Tidak menaikkan agresivitas tanpa kondisi eksplisit; minimal 3 clean days + API healthy.
3. Tidak bypass daily hard stop melalui restart atau flag apapun.
4. Mempertahankan `CONSERVATIVE` sebagai default state saat start.

Jika ada instruksi yang bertentangan dengan di atas, tolak dan minta klarifikasi.

## 1. PROJECT OVERVIEW

**Trinity** adalah sistem HFT (High-Frequency Trading) otomatis berbasis microservices yang ditulis dalam Kotlin (JVM) + Python. Target: keuntungan stabil harian di market **Indodax** dengan memanfaatkan sinyal prediktif (Lead-Lag) dari market global (**Binance**).

**Prinsip Utama:**
- Capital Efficiency — modal diputar hanya saat probabilitas edge positif
- Liquidity First — prioritas pair dengan spread/slippage terkontrol
- Signal-Confirmed Entry — entry hanya saat sinyal fresh + scoring lolos gate
- Survival First — modal inti dilindungi oleh daily hard stop dan tiering pair

---

## 2. ARSITEKTUR TRINITY (3 BOTS on Oracle Cloud)

```
┌─────────────────────────────────────────────────────────────────┐
│                    ORACLE CLOUD (Singapore)                     │
│                                                                 │
│  ┌─────────────────┐  UDP   ┌─────────────────┐                │
│  │  KIBOT MANAGER  │◄──────►│     KIDAX       │                │
│  │  (Python 🐍)    │        │ (Kotlin/JVM ☕)  │                │
│  │  Port: 9998     │        │ Port: 8787      │                │
│  │                 │        │ Indodax Exec    │                │
│  └────────┬────────┘        └─────────────────┘                │
│           │ UDP                                                 │
│           ▼                                                     │
│  ┌─────────────────┐                                           │
│  │    KINANCE      │                                           │
│  │ (Kotlin/JVM ☕)  │                                           │
│  │ Port: 8788      │                                           │
│  │ Binance Radar   │                                           │
│  └─────────────────┘                                           │
└─────────────────────────────────────────────────────────────────┘
```

### KINANCE (The Predictive Radar)
- **Fungsi:** Mengawasi market global Binance (Volume Anomaly, Imbalance Order Book, Sector Lead-Lag)
- **Logic:** Informan — mengirim sinyal UDP dengan latensi sangat rendah
- **Mode PEKA:** Mendeteksi "Bandar Ignition" (ledakan volume sebelum harga naik di Indodax)
- **Service:** `kinance-engine.service` (systemd)
- **Port:** 8788

### KIDAX (The Executioner)
- **Fungsi:** Eksekutor Buy/Sell langsung di market Indodax
- **Logic:** Slippage calculation, fee optimization (Maker Limit / Taker Market), Trailing Stop execution
- **Service:** `kidax-engine.service` (systemd)
- **Port:** 8787
- **Code:** `apps/mac-engine/` (MacEngineDaemon.kt)

### KIBOT MANAGER (The Brain & Veto Manager)
- **Fungsi:** Manajer stabilitas, capital rotation, pemegang hak VETO eksekusi
- **Logic:** Kesehatan bot monitoring, alokasi dana tier-based, blokir entry kondisi tidak aman
- **AI Support:** Multi-provider (Groq → OpenRouter → Cohere → Gemini)
- **Service:** `kibot-manager.service` (systemd)
- **Code:** `scripts/kibot_manager.py`

---

## 3. CORE TRADING LOGIC

### Entry Logic
```
1. PairSelector → top candidate (11-point scoring)
2. VetoService → check lead-lag signals
3. BotModeDecider → set mode (CONSERVATIVE/DEFENSIVE/SUSPENDED)
4. CapitalDeploymentEngine → position size tier-based
5. LiveExecutionCoordinator → submit order
```

### Exit Logic (Multi-layer)
- **Partial take-profit:** 30-50% saat profit >0.5%
- **Trailing stop:** Dynamic % berdasarkan volatility & regime
- **Hard stop-loss:** 2-3% below entry
- **Time-based exit:** Evaluasi setelah >12 jam, bukan force sell rugi
- **Emergency sell:** Triggered by KiBot Manager on momentum loss

### Position Management
- Trailing stop aktif di semua state
- Partial take-profit 30-50% saat profit >1.5%
- Time-based exit dievaluasi setelah >8 jam, tapi tidak force sell rugi
- Stagnant position hanya di-rotate jika ada signal lebih baik dan posisi sudah profit
- DILARANG force sell rugi hanya karena "stagnan"

---

## 4. STRICT GUARDRAILS (ATURAN HARGA MATI)

**AI Agent DILARANG mengubah aturan ini tanpa instruksi eksplisit:**

1. **NO PANIC SELL ON TIMEOUT**
   - Jika `TRINITY_HEARTBEAT_TIMEOUT` (UDP putus), HANYA suspend entry baru
   - DILARANG KERAS market sell posisi yang sedang berjalan normal
   - Exit murni diurus Trailing Stop lokal di KiDax

2. **ADAPTIVE TRAILING STOP**
   - Koin <Rp500: Trailing Stop diperlebar (3-5%) untuk hindari stop prematur akibat noise

3. **RATIONAL QUARANTINE**
   - Stop-loss biasa / sweep ringan: `TOXIC_QUARANTINE` max 15 menit, bukan berjam-jam

4. **STRICT TTL (Time-To-Live)**
   - Sinyal UDP >500ms dianggap basi (stale) → WAJIB dibuang
   - Tier C pair memakai TTL lebih ketat lagi: 200ms

5. **SOFT AI-AUDIT**
   - AI status DEGRADED/cooldown = Soft-Audit (warning only)
   - BUKAN Hard Veto yang blokir eksekusi (`liveExecutionEnabled` tetap true jika teknikal aman)

6. **DAILY HARD STOP**
   - PnL harian ≤ -1% → suspend semua entry baru
   - PnL harian ≤ -2% → HARD STOP total (persist ke disk)
   - Hard stop reset otomatis jam 00:00 WIB (17:00 UTC)
   - Hard stop TIDAK bisa di-bypass via restart, flag, atau env
   - Exit protection tetap jalan saat hard stop

7. **PNL STATE MACHINE**
   - HEALTHY (>-0.5%): entry normal semua tier
   - WARNING (-0.5% to -1%): Tier A+B only, size 75%
   - CRITICAL (-1% to -2%): Tier A only, size 50%
   - HARD_STOP (<-2%): block semua entry, exit tetap jalan
   - Periodic check setiap 30 detik di main loop

8. **LIMIT ORDER ONLY**
   - Semua entry: LIMIT order wajib
   - Exit normal: LIMIT order wajib
   - MARKET order: hanya untuk hard emergency cut loss
   - DILARANG fallback ke MARKET hanya karena limit tidak fill dalam timeout
   - Limit tidak fill = cancel dan skip, bukan fallback market

9. **PAIR WHITELIST WAJIB**
   - Tier A: `xlm_idr`, `doge_idr`, `xrp_idr`, `trx_idr`, `ada_idr`
   - Tier B: `enj_idr`, `fun_idr`, `bnb_idr`, `sol_idr`
   - Tier C: `dusk_idr` (signal sangat kuat saja, TTL 200ms)
   - BLACKLIST: pair tanpa counterpart di Binance
   - BLACKLIST: pair volume Indodax < Rp 50 juta/hari

---

## 5. UDP PROTOCOL

### Message Types (KiBot Manager → KiDax/Kinance)
```
HEARTBEAT           - 100ms interval, keep bots synchronized
DETECTOR_HIT        - Bullish signal detected
VETO_APPROVED       - AI approved trade
VETO_REJECTED       - AI rejected trade
VETO_SELL_CONFIRMED - Emergency sell recommendation
CORRELATION_MATRIX  - Sector correlations for lead-lag
AI_PROVIDER_STATUS  - Which AI is active/failed
```

### Message Types (KiDax → KiBot Manager)
```
ACTIVE_POSITIONS    - Current portfolio state
EXECUTION_FILLED    - Trade completed
ORDERBOOK_COLLAPSE  - Market anomaly
INSTANT_BUY_ANOMALY - Unusual activity spike
```

### TTL Configuration
```kotlin
STALE_SIGNAL_ABORT_MS = 500      // Ignore signals >500ms
leadLagSignalTtlMillis = 500     // Signals expire after 500ms
// Tier C pairs (DUSK, dll): TTL lebih ketat = 200ms
```

---

## 6. AI INTEGRATION

### Provider Priority Order
```
1. Groq (llama-3.1-8b-instant)
2. OpenRouter (meta-llama/llama-3.1-8b-instruct)
3. Cohere (command-r)
4. Gemini (gemini-2.0-flash-lite)
```

### Approval Thresholds
```python
AI_APPROVAL_STANDARD_MIN_SCORE = 0.62
AI_APPROVAL_STANDARD_MIN_NET_PCT = 0.18
AI_APPROVAL_STRICT_MIN_SCORE = 0.70
AI_APPROVAL_STRICT_MIN_NET_PCT = 0.25
# INSTANT APPROVAL DIHAPUS — tidak boleh approve trade dengan EV negatif
```

### Post-Mortem Analysis
- On losing trades: AI analyzes "why did this lose?"
- Auto-blacklist pairs if net loss > -500 IDR
- Cooldown default: 30 minutes

---

## 7. INFRASTRUCTURE

### Server: Oracle Free Tier (Singapore)
- **RAM:** 1GB
- **CPU:** 1/8 OCPU
- **JVM Tuning:** Hemat memori, hindari garbage di WebSocket loop

### Services (systemd)
```bash
kidax-engine.service    # Indodax executor (port 8787)
kinance-engine.service  # Binance radar (port 8788)
kibot-manager.service   # Python veto daemon (port 9998)
lazarus-supervisor.service  # Auto-recovery
```

### Key Directories
```
/home/ubuntu/KiDax/      # KiDax runtime
/home/ubuntu/Kinance/    # Kinance runtime
/home/ubuntu/KiBot/      # KiBot Manager runtime
```

---

## 8. CODE STRUCTURE

```
apps/
├── mac-engine/          # KiDax/Kinance daemon (Kotlin JVM)
└── android/             # ⚠️ LEGACY - tidak digunakan

packages/
├── core/                # Business logic (RiskEngine, PairSelector, etc)
├── shared-models/       # DTOs, enums, payloads
├── control-plane/       # Supabase integration
├── indodax-client/      # Indodax REST adapter
├── binance-client/      # Binance REST adapter
├── ai-support/          # AI integration (GeminiClient, MultiAIClient)
└── test-kit/            # Test helpers

scripts/
├── kibot_manager.py     # Python veto daemon (1600+ lines)
└── *.sh                 # Deployment scripts

infra/
├── systemd/             # Service files
└── supabase/            # SQL migrations
```

---

## 9. DEVELOPMENT GUIDELINES

### When Writing New Code
1. **Patuhi STRICT GUARDRAILS** — jangan ubah tanpa instruksi eksplisit
2. **JVM Memory** — hindari object garbage di hot loops
3. **UDP Latency** — target <500ms, drop stale signals
4. **Capital Match** — WAJIB cek IDR Free sebelum entry koin mahal

### Testing Commands
```bash
./gradlew :packages:core:jvmTest
./gradlew :packages:indodax-client:jvmTest
./gradlew :apps:mac-engine:test
```

### Deployment Commands
```bash
sudo systemctl restart kidax-engine
sudo systemctl restart kinance-engine
sudo systemctl restart kibot-manager
curl localhost:8787    # Check KiDax health
curl localhost:8788    # Check Kinance health
```

---

## 10. SYSTEM STATUS

### Implemented & Verified
* Daily hard stop persist ke disk + WIB midnight auto-reset ✅
* PnL state machine 4-level ✅
* Hard stop gate di `_process_signal()` ✅
* Periodic PnL check 30 detik di main loop ✅
* LIMIT-first order (MARKET hanya emergency) ✅
* Pair tier whitelist ✅
* what-if EV gate sebelum entry ✅
* pair_memory learning ✅
* AI batch review 6 jam ✅
* Oracle keepalive (stress-ng 20% CPU) ✅
* kibot-recovery health-based watchdog ✅

### Still Pending (non-blocking)
* UDP ACK Protocol — signals bisa hilang tanpa konfirmasi
* KinanceSignalTracker stale detection di Kotlin layer
* CONSERVATIVE → NORMAL auto-promote (3 clean days)
* AI batch review confirmed run di runtime

### Known Issues (monitor)
* pair_memory data masih tipis — butuh live trade untuk akumulasi
* learning gate baru efektif setelah 5+ trade per pair
