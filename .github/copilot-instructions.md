# KIBOT TRINITY — Copilot Instructions v4.0
# SURVIVAL FIRST | MATH-DRIVEN | OPPORTUNITY-AWARE

## PRIME DIRECTIVE
Filosofi: SURVIVAL FIRST. COMPOUNDING GRADUAL.
Motto: "TEKAN KERUGIAN, MAKSIMALKAN PROBABILITAS KEUNTUNGAN"

Setiap perubahan kode WAJIB:
1. Exit protection SACRED — trailing stop & cut loss jalan di SEMUA state
2. Agresivitas NAIK hanya jika: 3 clean days + API healthy + explicit instruction
3. Daily hard stop TIDAK bisa di-bypass kecuali ONE_SHOT_OVERRIDE (1x/hari)
4. CONSERVATIVE = default state saat startup, selalu
5. Math = primary decision engine. AI = support only.
6. LIMIT order default. MARKET hanya untuk: emergency cut loss + breakout catch (sinyal A+)

---

## 1. ARSITEKTUR
```
KINANCE (port 8788) → UDP signal <500ms → KIBOT MANAGER (port 9998)
                                                ↕ UDP heartbeat 100ms
                                          KIDAX (port 8787) → Indodax API
```
Server: ubuntu@213.35.118.26 (Indodax) | ubuntu@152.69.218.198 (Binance)

---

## 2. ENTRY GATE (10 gate, urutan wajib)
```
Gate 1:  PnL State      → HEALTHY/WARNING/CRITICAL/HARD_STOP/ONE_SHOT
Gate 2:  Hard Stop Disk → state/daily_guard.json
Gate 3:  Capital Min    → equity >= Rp 30,000
Gate 4:  Pair Whitelist → Tier A/B/C only
Gate 5:  Signal TTL     → < 500ms (Tier C: < 200ms)
Gate 6:  Math Score     → 11-point >= threshold
Gate 7:  What-If EV     → EV > 0, RR >= 1.2
Gate 8:  Learning Gate  → cooldown clear, PF > 0.8
Gate 9:  AI Veto (SOFT) → warning only jika degraded
Gate 10: Order Submit   → LIMIT default, MARKET jika breakout_urgent=True
```

---

## 3. PNL STATE MACHINE + WHAT-IF DECISION

### States (cek setiap 30 detik)
| State | PnL | Action |
|-------|-----|--------|
| HEALTHY | > -0.5% | Entry normal semua tier |
| WARNING | -0.5% to -1% | Tier A+B, size 75% |
| CRITICAL | -1% to -2% | Tier A only, size 50% |
| HARD_STOP | < -2% | Block entry. Exit jalan. |
| ONE_SHOT | setelah HARD_STOP | 1x kesempatan signal A+. Gagal → FULL_STOP |
| FULL_STOP | setelah ONE_SHOT gagal | Block total sampai midnight WIB |

### 30-Minute Math Review
Setiap 30 menit hitung:
- win_rate, avg_win_idr, avg_loss_idr, EV/trade
- trades_to_breakeven vs trades_possible
- action: CONTINUE/DEFENSIVE/TIGHTEN/PREPARE_STOP
Telegram report setiap review. Pure math, tidak butuh AI.

---

## 4. WHAT-IF SCENARIOS

### Scenario A: "Ketinggalan entry koin yang lagi naik"
- Jika Kinance signal masih fresh (<500ms) → boleh entry MARKET (breakout_urgent=True)
- Jika signal sudah >500ms tapi volume spike masih aktif → LIMIT aggressive (mid+0.3%)
- Jika signal sudah >2s → SKIP, tunggu pullback atau next signal
- Jangan chase harga yang sudah naik >3% dari trigger point

### Scenario B: "Koin sudah dekat peak, mau keluar"
- RSI > 75 + volume melemah → partial TP 50% langsung
- Trailing stop diperketat dari 5% → 2% setelah profit > 3%
- Jika Kinance detect momentum_ending → exit 100% dengan LIMIT aggressive
- Jangan tunggu target penuh kalau volume sudah turun drastis

### Scenario C: "Koin tiba-tiba turun, minimasi rugi"
- Trailing stop normal jalan otomatis
- Jika turun > 2x normal volatility dalam 1 menit → early exit LIMIT
- Hard cut loss -3% dari entry → LIMIT sell, tunggu 3s, jika tidak fill → MARKET
- Jangan average down (jangan beli lebih saat posisi sedang rugi)

### Scenario D: "Sudah HARD_STOP tapi ada koin bagus banget"
- ONE_SHOT mode aktif: 1x entry dengan max 20% equity
- Syarat ONE_SHOT: signal TTL < 200ms + score >= 8.0 + EV > Rp 500
- Jika ONE_SHOT profit → kembali ke WARNING mode
- Jika ONE_SHOT loss → FULL_STOP sampai midnight WIB

---

## 5. PAIR TIER + ORDER TYPE STRATEGY

| Tier | Pairs | Max Size | Target | Order Type |
|------|-------|----------|--------|------------|
| A | xlm, doge, xrp, trx, ada | 20% equity | 2.0% | LIMIT default |
| B | enj, fun, bnb, sol | 15% equity | 3.0% | LIMIT default |
| C | dusk, dll | 10% equity | 5.0% | LIMIT only |
| STABIL | usdt_idr | parkir modal | - | hanya hold |

MARKET boleh dipakai HANYA untuk:
1. Hard emergency cut loss (limit tidak fill 5s)
2. Breakout_urgent entry (signal A+ < 200ms, harga bergerak >1%/menit)

Indodax maker fee = 0% → LIMIT selalu lebih murah. Prioritaskan LIMIT.

---

## 6. WHAT-IF FEE MATH (deterministik)

```python
MAKER_FEE = 0.0004   # 0.04% LIMIT order (hampir gratis)
TAKER_FEE = 0.0055   # 0.55% MARKET order (mahal)

def simulate_what_if(pair_id, budget, spread, slippage, use_market=False):
    fee = TAKER_FEE if use_market else MAKER_FEE
    cost = (spread/2) + slippage + (fee * 2)
    net  = target_pct - cost
    loss = (trailing_stop + cost) * budget
    ev   = (win_rate * net * budget) - ((1-win_rate) * loss)
    rr   = (net * budget) / loss if loss > 0 else 0

    # MARKET order: cost jauh lebih tinggi, butuh target lebih besar
    if use_market:
        min_target = 0.015  # 1.5% minimum untuk MARKET (nutup fee 0.55%)
    else:
        min_target = 0.008  # 0.8% minimum untuk LIMIT (nutup fee 0.04%)

    return ENTER if ev > 0 and rr >= 1.2 and net >= min_target else SKIP
```

---

## 7. STRICT GUARDRAILS (HARGA MATI)

1. NO PANIC SELL ON UDP TIMEOUT — putus = suspend entry, BUKAN sell
2. ADAPTIVE TRAILING STOP — koin <Rp500: 5-7%, koin >Rp100k: 2%
3. RATIONAL QUARANTINE — cooldown max 15 menit per pair
4. STRICT TTL — signal >500ms = STALE. Tier C: >200ms = STALE
5. SOFT AI-AUDIT — AI degraded = warning only, bukan hard block
6. DAILY HARD STOP — PnL <=-2%: HARD_STOP persist disk, reset 00:00 WIB
7. ONE_SHOT OVERRIDE — 1x/hari setelah HARD_STOP, syarat ketat (score >=8.0)
8. LIMIT ORDER DEFAULT — MARKET hanya emergency + breakout_urgent
9. PAIR WHITELIST — pair tidak dikenal = auto-reject
10. PERIODIC MATH CHECK — PnL check 30s, full review 30 menit
11. MINIMUM CAPITAL — equity <Rp30k: suspend. <Rp10k: stop total
12. NO AVERAGE DOWN — jangan beli lebih saat posisi sedang rugi

---

## 8. AI INTEGRATION (SUPPORT ONLY)
Provider: Groq → OpenRouter → Cohere → Gemini
Standard: score >= 0.62, net >= 0.18%
Strict: score >= 0.70, net >= 0.25%
INSTANT APPROVAL DIHAPUS. AI degraded = soft audit.
AI batch review: setiap 6 jam.

---

## 9. SYSTEM STATUS
Implemented: hard stop, PnL state machine, LIMIT-first, pair memory,
learning hooks, oracle keepalive, health watchdog, BigDecimal precision
Pending: ONE_SHOT mode, 30min math review, what-if scenarios, breakout_urgent
