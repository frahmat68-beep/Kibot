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
- **Logic:** Kesehatan bot monitoring, alokasi dana max 25%, blokir entry kondisi tidak aman
- **AI Support:** Multi-provider (Groq → OpenRouter → Cohere → Gemini)
- **Service:** `kibot-manager.service` (systemd)
- **Code:** `scripts/kibot_manager.py`

---

## 3. CORE TRADING LOGIC

### Entry Logic
```
1. PairSelector → top candidate (11-point scoring)
2. VetoService → check lead-lag signals
3. BotModeDecider → set aggression (SAFE/DEFENSIVE/GROWTH/ATTACK)
4. CapitalDeploymentEngine → position size (max 25% per coin)
5. LiveExecutionCoordinator → submit order
```

### Exit Logic (Multi-layer)
- **Partial take-profit:** 30-50% saat profit >0.5%
- **Trailing stop:** Dynamic % berdasarkan volatility & regime
- **Hard stop-loss:** 2-3% below entry
- **Time-based exit:** Force close jika held >12 jam
- **Emergency sell:** Triggered by KiBot Manager on momentum loss

### Force Rotate
- Jika koin ditahan >2 jam dan profit stagnan (<1%)
- Sistem wajib jual (rugi fee) untuk buka peluang ke koin lain yang lebih aktif

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

5. **SOFT AI-AUDIT**
   - AI status DEGRADED/cooldown = Soft-Audit (warning only)
   - BUKAN Hard Veto yang blokir eksekusi (`liveExecutionEnabled` tetap true jika teknikal aman)

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
STALE_SIGNAL_ABORT_MS = 1500     // Ignore signals >1.5s
leadLagSignalTtlMillis = 4000    // Signals expire after 4s
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
AI_APPROVAL_MIN_SCORE = 0.62
AI_APPROVAL_MIN_EXPECTED_NET_PCT = 0.18
AI_APPROVAL_INSTANT_MIN_SCORE = 0.48
AI_APPROVAL_INSTANT_MIN_EXPECTED_NET_PCT = -0.02
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

## 10. KNOWN LIMITATIONS (TO FIX)

- [ ] UDP ACK Protocol — belum implemented, signals bisa hilang
- [ ] Chart Pattern Detection — framework ada, logic belum
- [ ] True Multi-AI Consensus — hanya single AI per request, bukan parallel
- [ ] Lag Failsafe — >500ms latency belum trigger auto stop-loss
- [ ] Android app — DEAD CODE, tidak digunakan
