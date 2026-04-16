# KiCryp Contract (Trinity) V6.9.8

Dokumen ini adalah sumber kebenaran utama untuk sistem **KiCryp (Manager)**, **KiDax (Indodax Executor)**, dan **Kinance (Binance Radar)**.

Tujuan utama: mesin trading 24/7 yang agresif, adaptif, dan tetap punya guardrail eksekusi.

## 1) Peran Tiap Bot

- **KiCryp (Manager/Intel)**
  - Membaca sinyal lintas sistem.
  - Menjalankan evaluasi veto (viability: potensi profit vs biaya/slippage).
  - Menjalankan job intel korelasi dinamis AI (background).
  - Broadcast sinyal veto/correlation matrix via UDP.
  - Mencatat laporan/telemetry manajerial.

- **Kinance (Detector Binance)**
  - Radar utama market cepat (Binance Spot).
  - Deteksi momentum, detector hit, sell-wall/reversal.
  - Broadcast sinyal via UDP ke KiCryp + KiDax.
  - Menjadi sumber “early warning” untuk rotasi cepat.

- **KiDax (Executor Indodax)**
  - Eksekutor order utama di market IDR.
  - Menjalankan entry/exit agresif sesuai sinyal valid.
  - Menjalankan baseline active-trading saat tidak ada anomali.
  - Tidak membiarkan wallet idle terlalu lama.

## 2) Mode Operasi

- Mode strategis utama: **AGRESIF_CUAN**
  - Fokus pada entry cepat + rotasi modal.
  - Memakai market order saat urgency/momentum tinggi.
  - Limit order dipakai untuk baseline lambat/tebak dasar.
  - Ada mekanisme chase (cancel limit lama lalu fire market).

## 3) Komunikasi & Sinkronisasi

- Protokol internal utama: **UDP mesh/signal path**.
- Sinyal penting:
  - `DETECTOR_HIT`
  - `SELL_WALL_SURGE`
  - `VETO_APPROVED`
  - `VETO_SELL_CONFIRMED`
  - `VETO_REJECTED`
  - `CORRELATION_MATRIX`
- TTL sinyal dipakai agar sinyal basi tidak dieksekusi.
- KiDax mengeksekusi saat sinyal valid + kondisi local-execution memungkinkan.

## 4) Eksekusi Hybrid (V4.2)

- **Prioritas market/taker** untuk momentum mendesak.
- **Limit->Market chase**:
  - Jika limit entry antre terlalu lama dan harga menjauh, order dibatalkan.
  - Dilanjutkan market entry instan.
  - Log wajib:  
    `"[ORDER_CHASE] pair=<pair> action=CANCELED_LIMIT_AND_FIRED_MARKET"`

## 5) Dynamic AI Correlation (Intel Division)

- Tidak mengandalkan hardcode sektor statis sebagai sumber utama.
- KiCryp menjalankan background fetch korelasi dari LLM periodik.
- Hasil format korelasi disiarkan sebagai `CORRELATION_MATRIX`.
- Kinance/KiDax menyimpan matrix ini di RAM lokal untuk keputusan cepat.

## 6) Guardrail Eksekusi

- **No Static Coin List (Adaptive Selection):**
  - Dilarang hard whitelist/hard blacklist koin untuk entry rutin.
  - Keputusan beli wajib berbasis `chart + historis pergerakan + likuiditas + estimasi fee/slippage`.
  - Koin hanya diblokir jika gagal guardrail objektif (mis. chart pendek/flat, spread/slippage berbahaya, volume tidak sehat, atau net projection negatif).
- Slippage guard aktif untuk mencegah entry jebakan likuiditas tipis.
- Anti-koin-mahal: pair dengan harga satuan > budget efektif dapat diblok.
- Liquidity guard: kedalaman bid/ask diperiksa sebelum entry agresif.
- Trailing exit dinamis untuk menjaga profit saat reversal.
- **Zombie Asset Trap Guard (TTL Posisi):**
  - Jika posisi dipegang `> 1 jam` dan PnL berada di rentang `-0.5% s.d +0.5%`, posisi dianggap stagnan.
  - Wajib `FORCE_ROTATE` ke kandidat lain yang lebih aktif (modal tidak boleh tidur).
- **AI Hallucination Guard:**
  - Sebelum broadcast `CORRELATION_MATRIX`, KiCryp wajib melakukan sanity-check terhadap daftar ticker resmi exchange tujuan.
  - Untuk eksekusi KiDax, simbol yang tidak ada di ticker resmi Indodax wajib dibuang dari payload.
- **Partial Take Profit (Scaling Out):**
  - Saat posisi mencapai `PnL > +10%`, jual sebagian (`50%`) untuk mengunci profit/modal.
  - Sisa posisi (`50%`) tetap dibiarkan berjalan dengan trailing stop dinamis.
- **Max Spread Cap untuk Market Order:**
  - Market order hanya boleh dieksekusi jika spread bid-ask `<= 1.5%`.
  - Jika spread `> 1.5%`, eksekusi otomatis fallback ke limit order di sekitar mid-price.

## 7) Logging Policy (Hemat Egress)

Log runtime dipangkas supaya tidak spam.

Prioritas log:
- `EXECUTION_BUY`
- `EXECUTION_SELL`
- `WHY_NOT_BUY` (alasan spesifik tidak entry)
- `ORDER_CHASE` (saat chase aktif)

Semua log lain non-kritis dibatasi/seminimal mungkin.

## 8) Data Source & Integrasi

- Engine trading: Kotlin/JVM (`mac-engine`).
- Manager intel: Python (`scripts/kicryp_manager.py`).
- Control plane: Supabase (dipakai hemat; update non-kritis dibatasi interval).
- UI App/Web: view monitoring dari agregasi KiCryp/KiDax/Kinance.

## 9) Secrets & Credential Policy

**Dilarang hardcode API key/secret di kode atau dokumen ini.**

Gunakan environment variable, contoh:
- `GROQ_API_KEY`
- `GEMINI_API_KEY`
- `COHERE_API_KEY`
- `OPENROUTER_API_KEY`
- `BLACKBOX_API_KEY`
- `KIBOT_CORRELATION_API_URL`
- `KIBOT_CORRELATION_API_KEY`
- `KIBOT_CORRELATION_MODEL`

Catatan keamanan:
- Jika key pernah terekspos di chat/log, lakukan rotate key.

## 10) Alur Kerja Ringkas (End-to-End)

1. Kinance scan market Binance (live).
2. Kinance kirim sinyal UDP (`DETECTOR_HIT` / `SELL_WALL_SURGE`).
3. KiCryp evaluasi viability + intel + veto.
4. KiCryp broadcast hasil (`VETO_APPROVED` / `VETO_REJECTED` / `VETO_SELL_CONFIRMED`) + `CORRELATION_MATRIX` periodik.
5. KiDax eksekusi buy/sell/rotasi sesuai sinyal valid dan guardrail.
6. KiCryp/KiDax/Kinance kirim data operasional untuk monitoring.

## 11) Batasan Realita Operasional

- Tidak ada jaminan profit harian tetap (market stochastic).
- Sistem dirancang untuk memperbesar probabilitas menang, bukan kepastian.
- Eksekusi dapat tertahan oleh likuiditas, fee, spread, atau API exchange.

## 12) Change Control (WAJIB)

Mulai dokumen ini berlaku:

- Semua perubahan yang **bertentangan** dengan kontrak ini wajib minta persetujuan eksplisit dari Bos/User terlebih dahulu.
- Definisi “bertentangan” mencakup:
  - menurunkan agresivitas tanpa instruksi,
  - menonaktifkan sinyal mesh/veto/correlation,
  - mengubah guardrail kunci tanpa approval,
  - mengubah log policy sehingga boros egress lagi,
  - mengubah peran utama KiCryp/Kinance/KiDax.

Format approval yang dianggap sah:
- Pesan eksplisit user di chat: “setuju ubah X menjadi Y”.

## 12.1) Change Logging (WAJIB)

- Setiap perubahan arsitektur/logic/runtime wajib dicatat di dokumen ini pada bagian `Change Log`.
- Minimal catatan berisi:
  - tanggal,
  - ringkasan perubahan,
  - alasan perubahan,
  - dampak ke risiko/eksekusi.
- Tidak boleh ada perubahan “diam-diam” tanpa update catatan.

## 13) Referensi File Implementasi Utama

- `apps/mac-engine/src/main/kotlin/com/kicryp/macengine/runtime/MacEngineDaemon.kt`
- `apps/mac-engine/src/main/kotlin/com/kicryp/macengine/config/MacRuntimeConfig.kt`
- `packages/core/src/commonMain/kotlin/com/kicryp/core/LiveRolloutGuard.kt`
- `scripts/kicryp_manager.py`

## 14) Status Versi Dokumen

- Version: `V7.0.0`
- Date: `2026-03-31`
- Owner: `Bos/User`
- Maintainer: `Codex`

## 15) Change Log

- `2026-03-31` — Multi-Provider AI Integration (`V7.0.0`):
  - **KiCryp AI Router** (`scripts/kicryp_manager.py`) kini aktif failover berurutan lintas provider: `Blackbox -> Groq -> OpenRouter -> Cohere -> Gemini` (urutan bisa diatur via env `KIBOT_AI_PROVIDER_ORDER`).
  - **Post-mortem AI** dan **Correlation Matrix AI** sudah memakai router provider yang sama, tidak lagi single endpoint statis.
  - Tambah dukungan provider baru **Blackbox** (`BLACKBOX_API_KEY`, `BLACKBOX_MODEL`, `BLACKBOX_API_URL`) dengan fallback aman ke jalur legacy jika router gagal.
  - KiCryp broadcast status provider aktif via UDP (`AI_PROVIDER_STATUS`) agar KiDax/Kinance dapat visibilitas runtime AI yang sedang dipakai.
  - **AI auditor script** (`scripts/audit_trading_6h_ai.py`) ditingkatkan untuk mengenali provider `blackbox` pada auto-chain/override.

- `2026-03-31` — Adaptive Coin Selection Revision (`V6.9.8`):
  - Hapus gate whitelist/list statis pada jalur entry runtime (`a_list_tunnel` hard-skip dibypass dari pipeline sinyal + eksekusi).
  - Entry dipaksa adaptif berbasis chart/historis, likuiditas, spread/slippage, dan net projection (fee-aware), bukan daftar koin tetap.
  - Jalur fallback `WHY_NOT_BUY` diperbarui agar tidak lagi berhenti di alasan `a_list_tunnel_no_candidate`.
  - Protective brake diperbaiki: status `QUARANTINED_DUST` tidak lagi memblokir jalur BUY, sehingga dust kecil tidak mengunci rotasi modal utama.

- `2026-03-31` — Stagnation TTL Revision (`V6.9.7`):
  - Rule stagnan direvisi dari `>3 jam` menjadi `>1 jam` sesuai instruksi Bos.
  - Sinkronisasi implementasi:
    - `TradeAutomationConfig.staleRotationMinAgeHours` -> `1.0`,
    - stale candidate gate di `CapitalDeploymentEngine` -> `>= 1 jam`,
    - zombie-ttl rotate di runtime daemon -> `>= 1 jam`,
    - floor tuning `tacticalStaleMaxAgeHours` diturunkan agar adaptasi AI/runtime tidak mengunci kembali di atas 1 jam.

- `2026-03-31` — Final Live Check Patch (`V6.9.5` hardening):
  - **Stagnation Rule diselaraskan ke kontrak**:
    - ambang rotasi stagnan di core dinaikkan menjadi `>= 3 jam`,
    - window stagnan PnL diselaraskan ke `abs(PnL) <= 0.5%` untuk mencegah rotasi prematur.
  - **Emergency Sell Trace TTL**:
    - sinyal force-sell dari jalur UDP tidak lagi hidup tanpa batas,
    - trace force-sell kini disimpan dengan `expiresAt` dan otomatis dipruning saat kadaluarsa.
  - **Runtime Ops Guard (server live)**:
    - `.env.kidax` dipulihkan ke konfigurasi lengkap (supabase + exchange + runtime flags),
    - ditambahkan rate-limit guard interval polling agar lebih tahan terhadap throttle exchange.

- `2026-03-28` — Upgrade ke `V4.3`:
  - Tambah TTL posisi stagnan (Zombie Asset Trap Guard).
  - Tambah AI Hallucination Guard untuk validasi ticker resmi sebelum broadcast korelasi.
  - Tambah Partial Take Profit (scale out 50% di >+10%).
  - Tambah Max Spread Cap (market order hanya saat spread <=1.5%, selain itu fallback limit mid-price).
  - Tambah aturan wajib pencatatan setiap perubahan (`Change Logging`).
- `2026-03-28` — Implementasi teknis V4.3 ke codebase:
  - Kotlin: TTL stagnan `>3 jam` + PnL `-0.5..+0.5` memicu `Zombie TTL rotate`.
  - Kotlin: partial take-profit `50%` saat gain `>10%`, sisanya tetap trailing.
  - Kotlin: market buy spread cap `1.5%` (fallback otomatis ke limit mid-price).
  - Python (KiCryp): sanity-check korelasi AI terhadap ticker resmi Indodax sebelum `CORRELATION_MATRIX` broadcast.
- `2026-03-28` — Patch live V4.3 lanjutan:
  - Hapus blokir **False Entry Mingguan** dari `LiveRolloutGuard` (gate usang dibypass penuh).
  - Tambah deteksi **GRADUAL_UPTREND** pada radar Kinance (tracker 5-15 menit, non-spike volume).
  - KiDax eksekusi sinyal `GRADUAL_UP` dengan **LIMIT mid-price** (bukan market chase).
  - Tambah **force post-mortem** untuk loss terbaru saat startup KiCryp Manager.
  - Jika insert ledger gagal, evaluasi AI loss tetap jalan (`fail-open` untuk pembelajaran).
- `2026-03-28` — Upgrade `V4.4` (Absolute Unleash):
  - Bypass total seluruh gate historis `LiveRolloutGuard` pada jalur entry runtime (`MacEngineDaemon`).
  - Perbaikan ledger Supabase: jika tabel `trade_history` tidak tersedia (404), payload otomatis fallback ke tabel `logs` kategori `BOOK_ENTRY`.
- `2026-03-29` — Upgrade `V5.0` (Integrasi 8 Kasus Alurasi):
  - Tambah hard abort sinyal stale `>1500ms` pada jalur double-confirmation (KiDax + KiCryp).
  - Tambah FOMO guard: jika sinyal sudah terbang tinggi (`>=15%`), veto diarahkan ke mode `LIMIT_PULLBACK` (koreksi sekitar `-4%`) alih-alih market chase.
  - Tambah crash guard absolut di KiDax: hard stop-loss `-3.5%` atau panic BTC/ETH `<= -2%` memicu `MARKET SELL` langsung tanpa tunggu evaluasi AI.
  - Tambah fail-open control-plane pada engine runtime: jika `BotState` Supabase gagal diambil, engine lanjut trading dengan fallback state lokal (degraded fail-open), tidak mati total.
  - Logging & kontrak diperbarui untuk menjaga jejak perubahan sesuai mandat Bos.
- `2026-03-30` — Upgrade `V5.2` (Autopilot Stability Patch):
  - Tambah **Global Monotonic Nonce Manager** di Indodax Gateway (Atomic global floor + parse server nonce floor).
  - Tambah serialisasi private call Indodax menggunakan **Mutex** untuk mencegah tabrakan nonce antar-coroutine.
  - Tambah retry nonce hingga 3x pada private API call dengan auto-adjust floor nonce dari respons exchange.
  - Tambah **Autonomous Resolver** di KiDax runtime (interval ~45 detik):
    - batalkan order aktif yang stale (`>=60 detik`),
    - bersihkan active persisted order yang tidak lagi ada di exchange,
    - release lease otomatis setelah sweep untuk rekonsiliasi cepat.
  - Turunkan noise runtime: lease-conflict transient tidak lagi di-log sebagai error keras.
- `2026-03-30` — Upgrade `V6.0` (Trinity Sell Protocol):
  - **Dust Liberation**: aset dust tidak dikunci permanen; jika value naik menembus ambang release (>= Rp11.000), otomatis keluar dari karantina dan kembali bisa dijual.
  - **Sell Signal Expansion**: tambah sinyal `MOMENTUM_LOSS` selain `SELL_WALL_SURGE` pada jalur Kinance -> KiCryp -> KiDax.
  - **KiCryp Veto Update** (`scripts/kicryp_manager.py`): `MOMENTUM_LOSS` diperlakukan sebagai jalur reversal dan memicu `VETO_SELL_CONFIRMED`.
  - **Smart Sell Routing** (`MacEngineDaemon.kt`):
    - reversal gradual -> prefer `LIMIT SELL` maker di best ask,
    - crash-style exit (`CRASH_GUARD`/panic) -> paksa `MARKET SELL` taker.
- `2026-03-30` — Upgrade `V6.2.1` (Retroactive Peak Tracking + Local Autonomy Sell):
  - **Retroactive Peak Tracking** (`MacEngineDaemon.kt`): saat inisialisasi trailing posisi hold, engine mengambil historical candle high Indodax (`tradingview/history_v2`) sejak waktu buy terakhir untuk memperbaiki peak pasca-restart/deploy (anti amnesia state).
  - **Observability Sell**: log wajib dipertegas untuk keputusan jual: `TRAILING_FLOOR_UPDATED`, `SELL_SIGNAL_RECEIVED`, `SELL_DECISION_REASON`.
  - **Local Autonomy Override**: KiDax boleh mengeksekusi trailing sell lokal tanpa menunggu veto eksternal ketika floor lokal ditembus.
  - **Exit Priority Fix**: saat banyak pair menembus floor bersamaan, prioritas sell dipilih berdasarkan severity breach + notional exposure (mencegah pair penting kalah antre dari pair kecil).
  - **/api/state**: `trailingFloors` tetap diekspos untuk transparansi harga floor aktif per pair.
- `2026-03-30` — Upgrade `V6.3` (CoinGecko Global Oracle):
  - **Trending Radar** (`kicryp_manager.py`): integrasi CoinGecko `search/trending` dengan scheduler 3-5 menit + cache internal untuk jaga rate limit.
  - **AI Prompt Enrichment**: data trending CoinGecko diinjeksikan ke prompt korelasi LLM sebelum pembentukan `CORRELATION_MATRIX`.
  - **Cross Validation**: saat menerima sinyal Kinance, KiCryp melakukan boost confidence jika pair juga masuk daftar trending global CoinGecko.
- `2026-03-30` — Upgrade `V6.5` (Garbage Coin Nuke + Technical Armor):
  - **Blue Chip Volume Guard** (`MacEngineDaemon.kt`): blokir BUY pada pair Indodax dengan volume harian `< Rp200.000.000`.
  - **Emergency Garbage Liquidation**: pair di daftar nuke diprioritaskan sebagai exit darurat sebelum exit lain.
  - **Partial Fill TTL**: order BUY/SELL `PARTIALLY_FILLED` dibatalkan jika menggantung >15 detik.
  - **Depth Impact Guard**: jika notional MARKET order melebihi 30% top-book depth, rute dialihkan otomatis ke LIMIT (mid-price).
  - **Indodax API Backoff** (`IndodaxGateway.kt`): private call kini memiliki exponential backoff untuk HTTP 429 (1s, 2s, 4s).
- `2026-03-30` — Upgrade `V6.6` (Hive Mind Active Overwatch):
  - **Garbage Nuke List Update** (`MacEngineDaemon.kt`): daftar force-liquidation diperluas ke `mpro_idr`, `dusk_idr`, `fet_idr`, `wlfi_idr`, `kaito_idr`, `plpa_idr` (+ kompatibilitas `xpr_idr`/`xrp_idr`).
  - **KiDax Active Position Broadcast**: KiDax broadcast UDP `ACTIVE_POSITIONS` tiap ~3 detik (entry/current/PnL/notional) untuk telemetri intervensi real-time.
  - **Kinance Watchlist Priority**: Kinance mengutamakan pair yang sedang di-hold KiDax pada radar entry dan memantau gejala longsor depth/momentum.
  - **KiCryp Active Overwatch** (`scripts/kicryp_manager.py`): KiCryp menyerap `ACTIVE_POSITIONS`, cross-check CoinGecko + matrix AI, lalu dapat menembakkan `EMERGENCY_VETO_SELL`.
  - **KiDax Emergency Sell Bypass**: saat menerima `EMERGENCY_VETO_SELL`, KiDax bypass trailing lokal dan memaksa jalur force-sell prioritas.
- `2026-03-30` — Upgrade `V6.7` (Android UI Nuke + Zero-Egress Monitor Stabilization):
  - **UI Nuke Enforcement** (`KiCrypRoot.kt`): dashboard monitor dipaksa minimalis (Hero Card + pills + Live Pair chips), tanpa mengandalkan kontainer detail lama.
  - **Mode Pair/PnL Isolation** (`KiCrypRoot.kt`): lookup pair + PnL dipisah ketat per mode (`_idr` untuk KiDax, `_usdt/_btc/_eth/_bnb` untuk Kinance) agar chip tidak tercampur lintas exchange.
  - **Unified Wealth Stabilization** (`AppRepository.kt`): total saldo KiCryp menjaga akumulasi `KiDax + Kinance` dan tidak drop ke nilai parsial saat salah satu feed sementara telat.
  - **Zero-Egress Path** (`AppRepository.kt`): jalur monitor Android tetap polling langsung endpoint Ktor `/api/state` (KiDax/Kinance) dan tidak memakai Supabase Realtime untuk telemetry rutin.
- `2026-03-30` — Runtime Ops Note `V6.7.1` (Self-Audit Command Readiness):
  - **KiDax identity override** di systemd ditetapkan ke `DEVICE_ID=kidax-oracle-sg` agar konsisten dengan env server dan mengurangi konflik lease lintas runtime.
  - **Self-audit wajib** dijalankan: verifikasi feed `ACTIVE_POSITIONS` UDP, validasi `Blue Chip Guard` (`>= Rp200 Juta`), dan verifikasi jalur AI `CoinGecko + AI_CORRELATION_FETCH` aktif periodik.
  - **Deployment monitor app**: APK Android debug dipasang ulang via ADB untuk memastikan Bos memantau state terbaru dari endpoint monitor.
- `2026-03-30` — Upgrade `V6.9` (Ruthless Canceler + Sweet Spot + True Dust Quarantine):
  - **Ruthless Canceler 10 detik** (`MacEngineDaemon.kt`): order aktif buy/sell yang menggantung >10 detik dipaksa cancel via API agar IDR tidak terkunci, termasuk cleanup order persisted tanpa state exchange.
  - **Resolver dipercepat**: loop rekonsiliasi stale-order diturunkan jadi interval ~5 detik untuk unlock saldo lebih cepat.
  - **Sweet Spot Guard**: batas Blue Chip volume harian dilonggarkan dari `Rp200 Juta` menjadi `Rp80 Juta` agar mid-cap agresif tetap bisa ditangkap.
  - **True Dust Quarantine UI/Telemetry**: aset bernilai `< Rp1.000` disembunyikan dari `heldAssets/holdingsDetailed/trailingFloors` di `/api/state`, tidak dibroadcast sebagai posisi aktif, dan dikeluarkan dari kalkulasi rotasi praktis.
- `2026-03-30` — Upgrade `V6.9.1` (APEX Predator Override):
  - **Zero Idle Cash Directive** (`MacEngineDaemon.kt`):
    - setelah sell non-partial di KiDax, engine dipaksa lanjut entry cycle di siklus yang sama (re-entry tanpa jeda),
    - budget baseline diubah menjadi nyaris full deploy (`IDR_FREE - fee buffer`) agar cash tidak nganggur.
  - **Hyper-Sensitive Anomaly Radar** (`MacEngineDaemon.kt`):
    - tambah filter `passesKinanceInstantAnomalyFilter` (jendela 15-30 detik) khusus pair A-List + volume ekuivalen `>= Rp80 Juta`,
    - Kinance kini dapat kirim `msgType=INSTANT_BUY_ANOMALY` dengan cooldown khusus lebih pendek.
  - **A-List Priority Queue** (`MacEngineDaemon.kt`):
    - tambah whitelist dinamis basis koin ganas (`DOGE/PEPE/SHIB/TRX/XLM/ONDO/XRP/ADA/MATIC/SOL/LINK`) untuk ranking radar + fallback entry,
    - scoring target anomaly dan fallback baseline diberi bobot A-List agar pair likuid-volatile diprioritaskan.
  - **KiDax Fast Response** (`MacEngineDaemon.kt`):
    - `INSTANT_BUY_ANOMALY` dari Kinance diproses langsung (tanpa menunggu double-confirm veto AI), namun tetap melewati guard spread/slippage.
  - **KiCryp Awareness Update** (`scripts/kicryp_manager.py`):
    - KiCryp kini mengenali `INSTANT_BUY_ANOMALY` untuk evaluasi cepat dan tetap menyiarkan veto/insight ke mesh.
- `2026-03-30` — Upgrade `V6.9.2` (A-List Tunnel Vision Hard-Skip):
  - **Hard-skip pipeline awal** (`MacEngineDaemon.kt`): seluruh jalur signal/entry Kinance + KiDax kini memproses hanya pair A-List/tunnel (`A-List statis + pair volume stabil >= Rp80 Juta`), sehingga pair receh tidak lagi memakan CPU cycle.
  - **Lead-lag dispatch filter**: `maybeDispatchLeadLagCallout` sekarang drop kandidat non A-List sebelum scoring/anomaly check; fallback quote juga hanya A-List tunnel.
  - **KiDax signal intake filter**: `DETECTOR_HIT/INSTANT_BUY_ANOMALY` non A-List ditolak di gerbang UDP intake.
  - **Entry pipeline guard**: plan entry utama difilter A-List sebelum evaluasi guardrail untuk mencegah loop `bluechip_volume_blocked` pada pair non target.
  - **Radar pulse reduction**: snapshot pulse hyper-aggressive dibatasi ke A-List tunnel pair agar scan rate fokus ke kolam likuid.
- `2026-03-30` — Upgrade `V6.9.3` (Lease Lockdown + Indodax Panopticon + Holdings Phalanx):
  - **Lease Lockdown Single Holder** (`MacEngineDaemon.kt`, `LiveExecutionCoordinator.kt`):
    - KiDax (`BOT_ID=main`) memaksa pre-trade lease reclaim saat drift holder terdeteksi.
    - Reservasi execution action kini auto-retry dengan term lease terbaru milik device aktif jika term awal stale/conflicted.
  - **Indodax Panopticon Radar** (`MacEngineDaemon.kt`):
    - Kinance mengambil universe pair dari `https://indodax.com/api/summaries` (refresh periodik) agar deteksi anomali meliputi seluruh listing Indodax.
    - Eksekusi tetap diproteksi guardrail spread/slippage/fee dan jalur owner KiDax.
  - **Phalanx Formation** (`MacEngineDaemon.kt`, `scripts/kicryp_manager.py`):
    - Fokus holdings diprioritaskan (toggle fokus pair hold aktif untuk callout/reversal) dengan emergency warning depth/momentum collapse.
    - Jalur `ACTIVE_POSITIONS` -> KiCryp/Kinance dipertahankan sebagai prioritas intervensi sell cepat (`MOMENTUM_LOSS/ORDERBOOK_COLLAPSE/EMERGENCY_VETO_SELL`).
- `2026-03-30` — Upgrade `V6.9.4` (Lease Lockdown Hardening + Full Indodax Universe Tracking):
  - **Lease Lockdown Hardening** (`MacEngineDaemon.kt`):
    - Tambah `ensureLeaseLockdownOwnership()` agar KiDax (`BOT_ID=main`) memverifikasi kepemilikan lease di beberapa titik siklus (`pre-command`, `pre-trade`, `post-trade`) bukan hanya saat error.
    - Reclaim lease otomatis sekarang dilakukan sebagai guard proaktif, bukan hanya recovery reaktif.
  - **Panopticon Expansion** (`MacEngineDaemon.kt`):
    - `refreshIndodaxFocusUniverse()` tidak lagi terbatas pada profile Binance; universe pair Indodax kini di-refresh lintas engine sehingga radar lintas bot tetap sinkron.
    - Filter tunnel pair dilonggarkan dari hard volume-first ke basis `universe listing Indodax + A-List + volume guard` agar monitoring semua listing tetap aktif, eksekusi tetap dijaga guardrail.
  - **Holdings-First Enforcement** (`MacEngineDaemon.kt`):
    - Pada Kinance, emergency warning holdings (`depth/momentum collapse`) sekarang diproses dan diprioritaskan sebelum dispatch sinyal entry baru.
    - Jika ada sinyal bahaya holdings, jalur callout entry ditunda agar VETO SELL intervensi jadi prioritas #1.
- `2026-03-30` — Upgrade `V6.9.5` (Dynamic VIP Entry Tuning + Stale BUY Lock Cleanup):
  - **Dynamic VIP Entry Lane** (`MacEngineDaemon.kt`):
    - Entry tidak lagi bergantung penuh pada hardcoded A-List lama; pair yang lolos momentum/volume/sentimen global kini bisa dipromosikan ke `Dynamic VIP` dengan TTL aktif.
    - Saat tidak ada kandidat tunnel utama, KiDax akan mencoba jalur `maybeSubmitDynamicVipEntry()` dulu sebelum fallback baseline.
  - **Threshold Relax (Fee-Aware)**:
    - Validasi entry dilonggarkan terukur (momentum/trade-activity/spread/slippage), tetapi tetap wajib lolos proyeksi net profit setelah fee + dampak slippage.
    - Jalur EXIT tidak diubah oleh patch ini.
  - **Ghost BUY Lock Mitigation**:
    - `active_buy_order_exists` kini divalidasi terhadap order aktif nyata di exchange (`cachedOpenOrders`) + persisted order yang masih fresh, sehingga order hantu tidak lagi mengunci entry.
- `2026-03-30` — Hotfix `V6.9.5a` (Lease Continuity + Reservation Anti-Stall):
  - **Lease Continuity** (`MacEngineDaemon.kt`):
    - Autonomous stale-order resolver tidak lagi me-release lease setelah cleanup; lease dipertahankan agar submit berikutnya tidak kehilangan kunci eksekusi.
    - Sebelum submit entry, engine melakukan re-check ownership lease lockdown sekali lagi; jika holder belum valid, submit dibatalkan aman.
  - **Reservation Intent Nonce Window** (`LiveExecutionCoordinator.kt`):
    - `orderIntentId` ditambah time-window nonce (5 detik) untuk mencegah deadlock reservation akibat intent statis yang berulang pada pair sama.
  - **Verifikasi Runtime**:
    - Deploy berhasil dengan hash `fcf77d344d4b4a0d90deda102f6f92e813becf1edb89347226369d3c6a4dfa24`.
    - Bukti eksekusi pasca patch: log `EXECUTION_BUY pair=ont_idr reason=dynamic_vip mode=MARKET`.
- `2026-03-31` — Hotfix `V6.9.6` (Anti Short/Flat Chart + Emergency Mesh Hardening):
  - **Chart Quality Guard diperketat** (`MacEngineDaemon.kt`):
    - `entryBlockedByShortFlatChart()` sekarang tidak hanya cek `candleCount/range`, tapi juga:
      - `activeCandleCount` minimum (candle hidup),
      - `distinctCloseBuckets` minimum (variasi harga nyata),
      - guard khusus nominal murah (`cheap_nominal_chart_blocked`) agar pair seperti chart pendek/flat tidak masuk.
    - Data guard tetap memakai `history_v2` Indodax dengan cache lokal 60 detik untuk menahan beban request.
  - **Lead-Lag stale signal tuning**:
    - batas `leadLagHardStaleAbortMs` dinaikkan `300ms -> 900ms` supaya sinyal valid tidak keburu dibuang saat jitter jaringan normal.
  - **Sell-wall anti-spoof jadi adaptif**:
    - konfirmasi reversal tidak lagi fix 3 detik; sekarang adaptif (`fast` untuk momentum loss/reversal kuat, `normal` untuk kondisi biasa).
  - **Holdings Focus dipaksa konsisten**:
    - saat ada posisi aktif KiDax, mode fokus holdings di Kinance tidak lagi toggle ON/OFF per siklus.
  - **Emergency dispatch fallback**:
    - jalur reversal/emergency warning Kinance kini punya fallback command queue saat UDP gagal (tidak lagi single-channel UDP only).
- `2026-03-31` — Tuning `V6.9.7` (Entry Throughput Stabilization on Live Oracle):
  - **Latency guard dilonggarkan terukur** (`MacEngineDaemon.kt`):
    - `aggressiveLimitFallbackLatencyMs` dinaikkan `850ms -> 1500ms`,
    - `entryBlockLatencyMs` dinaikkan `1200ms -> 2300ms`,
    - tujuan: mengurangi false-block saat jitter jaringan, tetap fee-aware dengan fallback limit sebelum block total.
  - **Chart variation threshold dikalibrasi**:
    - `chartGuardMinDistinctCloseBuckets` diturunkan `6 -> 4`,
    - `chartGuardMinRangePct` diturunkan `1.20% -> 0.80%`,
    - tujuan: tidak melewatkan uptrend gradual yang valid sambil tetap memblok chart pendek/flat ekstrem.
  - **Validation status**:
    - skenario inti koordinasi UDP + guard chart/historis tetap lulus (`story1/2/3 + hyper story2`).
  - **Deployment status**:
    - redeploy fat-jar ke `kidax-engine` dan `kinance-engine` selesai; kedua service kembali `active`.
