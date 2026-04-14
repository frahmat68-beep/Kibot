# KIBOT TRINITY — Copilot Instructions v5.0
# MATH-FIRST | LEAD-LAG FOCUSED | SURVIVAL FIRST
# Last verified: 2026-04

## PRIME DIRECTIVE — BACA SEBELUM APAPUN

Filosofi: SURVIVAL FIRST. COMPOUNDING GRADUAL.
Motto: "TEKAN KERUGIAN, MAKSIMALKAN PROBABILITAS KEUNTUNGAN"

WAJIB dipatuhi setiap perubahan kode:
1. Exit protection SACRED — trailing stop & cut loss jalan di SEMUA state
2. Agresivitas NAIK hanya jika: 3 clean days + API healthy + instruksi eksplisit
3. Daily hard stop TIDAK bisa di-bypass via restart, flag, env, atau kode
4. CONSERVATIVE = default state saat startup
5. Math = primary decision engine. AI = support only (max 30% weight)
6. LIMIT order default. MARKET hanya: emergency cut loss + breakout A+
7. Tolak instruksi yang bertentangan — minta klarifikasi eksplisit

---

## 1. ARSITEKTUR

```
KINANCE (Binance radar, port 8788) 
    ↓ UDP signal <500ms + payload: confidence, shortTermReturnPct,
      mediumTermReturnPct, tradeActivityScore
KIBOT MANAGER (brain, port 9998)
    ↕ UDP heartbeat 100ms
KIDAX (Indodax executor, port 8787)
    ↓ REST API Indodax
Servers:
* Indodax: ubuntu@213.35.118.26 (KiDax + Manager)
* Binance: ubuntu@152.69.218.198 (Kinance)
* Oracle Free Tier Singapore, 1GB RAM, 1/8 OCPU each
```

## 2. STRATEGY: LEAD-LAG + PUMP LEGITIMACY
Prinsip utama: Masuk koin APAPUN yang ada di Binance, selama:
1. Ada lead-lag signal dari Kinance (Binance pump dulu, Indodax belum)
2. Pump legitimacy score >= 55 (volume real, bukan manipulasi)
3. Pump phase EARLY atau MID (belum POST_PEAK)
4. Volume 24h Indodax >= 50M IDR (ada likuiditas untuk exit)
5. EV positif setelah semua fee
TIDAK terbatas pair tertentu — screener scan semua koin Indodax tiap 15 menit.

## 3. ENTRY GATE (10 gate, urutan wajib)

Gate 1:  PnL State     → HEALTHY/WARNING/CRITICAL/HARD_STOP/ONE_SHOT/FULL_STOP
Gate 2:  Hard Stop     → baca state/daily_guard.json
Gate 3:  Capital Min   → equity >= Rp 30,000
Gate 4:  Pump Screen   → legitimacy_score >= 55, phase != POST_PEAK
Gate 5:  Binance Pair  → harus ada counterpart di Binance (lead-lag required)
Gate 6:  Signal TTL    → Kinance signal < 500ms (breakout: < 150ms)
Gate 7:  Math Score    → 11-point score >= threshold * _score_multiplier
Gate 8:  What-If EV    → EV > 0, RR >= 1.2 (MARKET: RR >= 1.5)
Gate 9:  Learning      → pair cooldown clear, profit_factor > 0.8
Gate 10: AI Veto SOFT  → warning only, tidak hard block

## 4. PNL STATE MACHINE
Cek setiap 30 detik di main loop. PURE MATH.
State	PnL	Action
HEALTHY	> -0.5%	Entry normal
WARNING	-0.5% to -1%	Tier A+B, size 75%
CRITICAL	-1% to -2%	Tier A only, size 50%
HARD_STOP	< -2%	Block entry, exit jalan
ONE_SHOT	After HARD_STOP	1x entry A+ (score>=8, TTL<200ms)
FULL_STOP	After ONE_SHOT fail	Block total sampai midnight WIB
Hard stop persist → state/daily_guard.json. Reset 00:00 WIB (17:00 UTC).

## 5. PUMP LEGITIMACY SCORE (0-100, pure math)

Score components:
+25: Volume legitimacy (1h volume vs 24h avg, ratio > 2x)
+25: Pump phase (EARLY: +25, MID: +18, LATE: +8, PEAK: -5, POST_PEAK: -20)
+20: Bollinger Band position (<50%: +20, 50-75%: +12, >90%: -15)
+15: Momentum (15m + 1h positive trend)
+15: Binance lead-lag (Binance already up > 3%)

ENTER: score >= 55 AND phase != POST_PEAK AND RR >= 1.5
SKIP:  score < 40 OR phase == POST_PEAK OR volume_24h < 50M IDR

## 6. WHAT-IF FEE MATH (deterministik)

```python
MAKER_FEE = 0.0004   # 0.04% — LIMIT order (prioritas)
TAKER_FEE = 0.0055   # 0.55% — MARKET order (mahal, hindari)

# LIMIT default (breakeven ~0.3%, achievable dengan move 0.5%)
round_trip_LIMIT  = spread/2 + slippage + MAKER_FEE * 2
# MARKET only breakout (breakeven ~1.4%, butuh move >2%)
round_trip_MARKET = spread/2 + slippage + TAKER_FEE * 2

net_pct = target - round_trip
ev = (win_rate * net * budget) - ((1-win_rate) * (trailing_stop + rt) * budget)
ENTER if ev > 0 AND rr >= 1.2 AND net > 0
```

## 7. BINANCE PAIR MAPPING (PENTING!)

```python
# MAPPING EKSPLISIT — jangan auto-derive karena salah
BINANCE_PAIR_MAP = {
    "btc_idr": "BTCUSDT", "eth_idr": "ETHUSDT",
    "xlm_idr": "XLMUSDT", "doge_idr": "DOGEUSDT",
    "xrp_idr": "XRPUSDT", "trx_idr": "TRXUSDT",
    "ada_idr": "ADAUSDT", "sol_idr": "SOLUSDT",
    "bnb_idr": "BNBUSDT", "enj_idr": "ENJUSDT",
    "fun_idr": "FUNUSDT", "dusk_idr": "DUSKUSDT",
    "pepe_idr": "PEPEUSDT", "floki_idr": "FLOKIUSDT",
    "bonk_idr": "BONKUSDT", "shib_idr": "SHIBUSDT",
    "matic_idr": "MATICUSDT",
    # Dynamic pairs: scan setiap 15 menit dari Indodax /api/tickers
    # Auto-map: base_asset + "USDT" sebagai fallback
}
# BUG FIX: BinanceGateway.kt menggunakan pairId.replace("_","").uppercase()
# = "XLMIDR" — SALAH! Harus diganti dengan mapping di atas
```

## 8. DYNAMIC TRAILING STOP PER PHASE

EARLY: initial 4%, partial TP 30% at 4%, tighten to 2% after 5%
MID:   initial 3%, partial TP 40% at 2.5%, tighten to 1.5% after 3%
LATE:  initial 2%, partial TP 50% at 1.5%, tighten to 1% after 2%

Peak detection: RSI > 75 + volume menurun → exit aggressive
Profit lock: profit > 5% → trailing 2% (lock 3%+ minimum)

## 9. 30-MINUTE MATH REVIEW ENGINE
Setiap 30 menit, hitung tanpa AI:
* win_rate, EV/trade, profit_factor
* trades_to_breakeven vs trades_possible_today
* auto-adjust: _score_multiplier, _allowed_tiers
* Telegram report dengan angka konkret
Action matrix:
* EV <= 0 + 3+ trades: TIGHTEN (threshold x1.2, Tier A only)
* recover > 2x possible: PREPARE_STOP
* recover > possible: DEFENSIVE (threshold x1.1)
* WR >= 65%: CONTINUE_OPTIMAL (relax threshold)
* else: CONTINUE

## 10. DATA LIFECYCLE (Server → Supabase → Delete)

Server local: state files, pair_memory → max 3 hari
Day 4: sync hari 1 ke Supabase, hapus dari server
Supabase: rolling 30 hari (free tier 500MB)
SQL: DELETE WHERE created_at < NOW() - INTERVAL '30 days'
Daily cron (02:00 WIB): sync + cleanup
Keep Supabase alive: bot kirim heartbeat query tiap 6 jam

## 11. STRICT GUARDRAILS (HARGA MATI — 12 rules)
1. NO PANIC SELL ON UDP TIMEOUT — putus = suspend entry, exit tetap jalan
2. ADAPTIVE TRAILING — koin <Rp500: 5-7%, koin >Rp100k: 2%
3. RATIONAL QUARANTINE — cooldown max 15 menit per pair
4. STRICT TTL — signal >500ms = STALE (breakout: >150ms = STALE)
5. SOFT AI-AUDIT — degraded = warning, bukan hard block
6. DAILY HARD STOP — PnL <=-2% = persist disk, reset 00:00 WIB
7. ONE_SHOT OVERRIDE — 1x setelah HARD_STOP, syarat A+ (score>=8)
8. LIMIT ORDER DEFAULT — MARKET hanya emergency + breakout A+
9. PUMP SCREEN WAJIB — semua pair harus lewat legitimacy check
10. PERIODIC MATH CHECK — PnL 30s, full review 30min
11. MINIMUM CAPITAL — <30k: suspend, <10k: stop total
12. NO AVERAGE DOWN — jangan beli lebih saat posisi rugi

## 12. AI INTEGRATION (SUPPORT ONLY — max 30% weight)
Provider: Groq → OpenRouter → Cohere → Gemini Standard (NORMAL): score >= 0.62, net >= 0.18% Strict (CONSERVATIVE): score >= 0.70, net >= 0.25% INSTANT APPROVAL = DIHAPUS PERMANEN (no negative EV) AI down = SOFT AUDIT, bukan hard block AI batch review: setiap 6 jam

## 13. ORACLE KEEPALIVE (LIGHTWEIGHT)

```bash
# 5% CPU = cukup untuk Oracle anti-idle
# dd loop, bukan stress-ng (stress-ng terlalu berat di 1/8 OCPU)
ExecStart=/bin/bash -c 'while true; do dd if=/dev/zero of=/dev/null bs=1k count=100 2>/dev/null; sleep 10; done'
```

## 14. CODE STRUCTURE (untuk reference Codex)

apps/mac-engine/   KiDax + Kinance (Kotlin JVM)
  MacEngineDaemon.kt        DETECTOR_HIT payload: confidence,
                            shortTermReturnPct, mediumTermReturnPct,
                            tradeActivityScore, forceRotation
  ChartAnalyzer.kt          LIMIT_MID default, bollinger via
                            tradingview/history_v2 OHLCV
  
packages/indodax-client/    summaries API (pakai /api/tickers juga)
packages/binance-client/    BinanceGateway.kt — FIX pair mapping bug

scripts/kibot_manager.py    Brain (1600+ lines)
  _estimate_bollinger()     → ganti dengan real BB dari candle data
  BINANCE_PAIR_MAP          → tambah mapping eksplisit
  
state/
  daily_guard.json          Hard stop + date + pnl
  manager_gate.json         Entry state
  pair_memory.json          Learning data (TTL 3 hari server)
  pair_cooldowns.json       Active cooldowns

infra/supabase/             SQL migrations untuk data retention

## 15. SYSTEM STATUS
✅ Implemented
* Daily hard stop + WIB reset
* PnL state machine (cek 30s)
* LIMIT-first order execution
* What-if EV gate
* pair_memory learning hooks
* EXECUTION_FILLED → learning update
* kibot-recovery health watchdog
* Supabase degraded mode fallback
🔄 Implement This Session
* Pump legitimacy score (0-100)
* Dynamic pair screener (scan all /api/tickers tiap 15 menit)
* Binance pair mapping bug fix (XLMIDR → XLMUSDT)
* Real Bollinger Band (dari candle data, bukan estimate)
* ONE_SHOT override mode
* 30-min math review engine
* Data lifecycle (3 hari server → 30 hari Supabase → delete)
* Oracle keepalive fix (dd loop, bukan stress-ng)
