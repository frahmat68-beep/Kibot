# KIBOT TRINITY — Copilot Instructions v5.0
# SURVIVAL FIRST | MATH-DRIVEN | OPPORTUNITY-AWARE

## PRIME DIRECTIVE
Filosofi: SURVIVAL FIRST. COMPOUNDING GRADUAL.
Motto: "TEKAN KERUGIAN, MAKSIMALKAN PROBABILITAS KEUNTUNGAN"

Setiap perubahan kode WAJIB:
1. Exit protection SACRED — trailing stop & cut loss jalan di SEMUA state.
2. Agresivitas NAIK hanya jika: 3 clean days + API healthy + explicit instruction.
3. Daily hard stop TIDAK bisa di-bypass kecuali ONE_SHOT_OVERRIDE (1x/hari).
4. CONSERVATIVE = default state saat startup, selalu.
5. Math = primary decision engine. AI = support only.
6. LIMIT order default. MARKET hanya untuk: emergency cut loss + breakout catch (sinyal A+).
7. DATA RETENTION: Rolling delete history > 100 samples untuk hemat memori.

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
Gate 4:  Pair Whitelist → Tier A/B/C/D only
Gate 5:  Signal TTL     → < 500ms (Tier C/D: < 200ms)
Gate 6:  Math Score     → 11-point >= threshold (Math-First)
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
| ONE_SHOT | setelah HARD_STOP | 1x kesempatan signal A+. |

### 30-Minute Math Review
Setiap 30 menit hitung:
- win_rate, avg_win_idr, avg_loss_idr, EV/trade
- action: CONTINUE/RELAX/DEFENSIVE/TIGHTEN/PREPARE_STOP
- Telegram report setiap review.

---

## 4. WHAT-IF SCENARIOS (REMEDIATED)
- **Scenario A (Chasing)**: Jangan chase harga yang sudah naik >3% dari trigger point.
- **Scenario B (Peak Exit)**: Trailing stop diperketat dari 5% → 2% setelah profit > 3%.
- **Scenario C (Emergency)**: Hard cut loss -3% dari entry → LIMIT sell (5s) → MARKET.
- **Scenario D (Recovery)**: ONE_SHOT mode aktif 1x dengan max 20% equity.

---

## 5. PAIR TIER + MAPPING (STRICT)
- **Mapping Bug Fix**: Always map `xlm_idr` → `XLMUSDT` on Binance. DO NOT concatenate with `IDR` for global radar.
- **Tier A**: xlm, doge, xrp, trx, ada.
- **Tier B**: bnb, enj, fun, arb, sol.

---

## 6. SYSTEM STATUS (v5.0)
- **Implemented**: Trinity v5.0 Overhaul, Infrastructure Optimization, Data Retention (TTL 100), Math Review Loops, Veto Gate v2.
- **Maintenance**: Automated log vacuuming and memory recycling enabled.
