# KIBOT TRINITY v7.0
# Dual Bucket: 50% Global Lead-Lag + 50% Local Indodax-Only
# Modal: adaptive dari equity aktual setiap hari
# Filosofi: Profit sedikit demi sedikit lama lama jadi bukit
# Motto: Minimalisir kerugian, maksimalkan probabilitas keuntungan

## PRIME DIRECTIVE (tidak bisa diubah)

1. Exit protection SACRED — trailing stop dan cut loss jalan di SEMUA state
2. Bucket A: entry hanya jika Kinance AND KiCom KEDUANYA setuju
3. Bucket B: entry hanya jika ConvictionScore >= threshold (lihat cascade mode)
4. SETIAP entry → TradeLogger.record_entry() sebelum order
5. SETIAP exit → TradeLogger.record_exit() setelah order
6. Math = engine utama. AI = support soft-veto saja
7. LIMIT selalu. MARKET hanya hard stop emergency
8. Cash reserve minimum 20% selalu

---

## ARCHITECTURE
KINANCE (port 8788, Binance) ─┐
├─ AND gate → KIBOT MANAGER (port 9998) → KIDAX (port 8787) → Indodax
KICOM (Crypto.com REST API)  ─┘
Bucket A (50%): Global Lead-Lag

Kinance + KiCom KEDUANYA harus bullish
Max 3 posisi simultan
Target +1-3% | Stop -1.5% trailing 2%

Bucket B (50%): Local Indodax-Only

ConvictionScore 7-layer (0-1.0 pure math)
Default HOLD CASH, entry hanya 0.85+
Max 2 posisi simultan, cash reserve 40%
Target +3-8% | Stop -3% trailing 5%


---

## CONVICTION SCORE — 7 LAYER SAFETY

```python
score = (
    0.25 * vol_spike_score      # vol_24h / avg_vol_7d, cap 1.0, curiga jika >10x
    0.20 * bb_breakout_score    # (price - lower_BB) / (upper_BB - lower_BB)
    0.20 * orderbook_score      # bid_depth / (bid + ask depth)
    0.20 * momentum_rsi_score   # (75 - RSI) / 75
    0.15 * vol_trend_score      # increasing=1.0, stable=0.6, decreasing=0.2
) + spread_penalty              # -0.15 jika spread >5%, -0.05 jika >2%

HARD BLOCKS (langsung skip):
  - price_change_24h > +50%
  - price > BB upper
  - RSI > 80
  - vol_24h < 500_000_000 IDR
  - pair dalam cooldown
  - BTC dump 1h > -4%

MIN THRESHOLD PER PHASE:
  - EARLY/MID: 0.85
  - LATE: 0.88
  - PEAK: 0.92 (sangat ketat)
```

---

## CASCADE LOSS INTELLIGENCE

| Mode | Kelly | ConvMin | Bucket B | MaxA | MaxB |
|------|-------|---------|----------|------|------|
| GROWTH | 1.0 | 0.85 | Active | 3 | 2 |
| CAUTION | 0.8 | 0.88 | Active | 2 | 1 |
| DEFENSIVE | 0.5 | 0.90 | CASH | 2 | 0 |
| RESTRICTED | 0.3 | 0.92 | CASH | 1 | 0 |
| HARD_STOP | 0.0 | 1.00 | CASH | 0 | 0 |

Trigger: 1 loss → CAUTION, 2 berturut → DEFENSIVE, 3 → RESTRICTED, daily -2% → HARD_STOP
Reset: 1 win → CAUTION, 2 → DEFENSIVE, 3 → GROWTH

---

## EXIT LADDER

| Kondisi | Action | Order |
|---------|--------|-------|
| TP +3% | Exit 30% | LIMIT |
| TP +6% | Exit 30% lagi | LIMIT |
| TP +10% | Exit 20% lagi | LIMIT |
| TP +15% | Exit 70% | LIMIT |
| Peak (3/4 sinyal) | Exit 70% | LIMIT |
| Volume collapse | Exit 100% | LIMIT |
| Loss -3% hard | Exit 100% | LIMIT→MARKET |
| Hold >12 jam | Exit 100% | LIMIT |
| BTC -5% 1h | Exit 100% profit | LIMIT |
| Conviction <0.60 (B) | Exit 100% | LIMIT |

---

## FEE MATH

LIMIT: 0.04% + 0.21% PPh + 0.04% = 0.69% → breakeven ~0.83%
MARKET: 0.55% + 0.21% + 0.55% = 1.31% → breakeven ~1.57%

---

## BLIND SPOTS YANG SUDAH DIPERBAIKI

1. Order fill verification — cek ke Indodax sebelum catat posisi
2. Position persistence — open_positions.json survive restart
3. API rate limiting — jangan spam Indodax (bisa ke-ban)
4. Decimal parsing — safe_float() handle koma Indodax
5. Min lot size per pair — get_min_order() per pair
6. Graceful shutdown — SIGTERM save posisi sebelum exit
7. Memory cap — _today_trades max 200 entries
8. Thread-safe cascade state — dengan lock
9. KiCom cache — 2 menit TTL (rate limit CDC)
10. BB/RSI calculation — check if closes list cukup panjang

---

## PAIR UNIVERSE

Bucket A — Lead-Lag (Kinance + KiCom):
btc, eth, xrp, sol, doge, bnb, ada, shib, xlm, trx, dot, pepe,
bonk, link, avax, near, apt, sui, floki, enj, dusk, fun, atom,
uni, pol, matic, ltc, hbar, arb (29 pairs)

Binance map WAJIB explicit — bukan auto-derive!
xlm_idr → XLMUSDT (BUKAN XLMIDR — ini bug lama)

Bucket B — Indodax-Only (7-layer ConvictionScore):
whitewhale, br, drx, bio, pippin, myx, jellyjelly, aster,
hype, gravity, trollsol, mubarak, xpl, fanc, nova, mrs,
islm, vanry + auto-discovery

---

## SERVICES

kidax-engine (port 8787) — Indodax executor
kinance-engine (port 8788) — Binance radar
kicryp-manager (port 9998) — Python brain
oracle-keepalive — CPU keepalive dd loop

---

## GUARDRAILS (14 rules, tidak bisa diubah)

1. NO BUY MARKET untuk entry
2. NO entry jika pump >50%
3. NO entry pair dalam cooldown
4. NO entry Bucket B jika score < threshold
5. NO entry jika cash < 20% equity
6. NO entry jika daily PnL < -2%
7. NO Bucket A entry tanpa kedua scanner setuju
8. NO SELL MARKET kecuali emergency
9. NO sell saat masih dalam trailing stop range
10. WAJIB record_entry() sebelum order
11. WAJIB record_exit() setelah close
12. WAJIB post_mortem() untuk setiap loss > Rp 200
13. BTC update setiap 1 menit
14. AI = soft veto saja, tidak boleh hard block execution

---

## STATE FILES

state/trade_log.jsonl      — semua trade history
state/cascade_mode.json    — cascade state persisten
state/open_positions.json  — posisi terbuka (survive restart)
state/daily_guard.json     — hard stop flag
state/daily_summary.json   — ringkasan harian

---

## SUPABASE TABLES

trade_history, pair_memory, performance_snapshots,
post_mortem_log, daily_summary
Project: vptlelbgyxwieyfdpuja.supabase.co
