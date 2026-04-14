# KIBOT TRINITY — Copilot Instructions v4.0
# SURVIVAL FIRST | MATH-DRIVEN | OPPORTUNITY-AWARE

## PRIME DIRECTIVE

Filosofi: SURVIVAL FIRST. COMPOUNDING GRADUAL.
Motto: "TEKAN KERUGIAN, MAKSIMALKAN PROBABILITAS KEUNTUNGAN"

Setiap perubahan kode WAJIB:
1. Exit protection SACRED. Trailing stop dan cut loss jalan di semua state.
2. Agresivitas naik hanya jika 3 clean days, API healthy, dan explicit instruction.
3. Daily hard stop tidak bisa di-bypass kecuali ONE_SHOT_OVERRIDE 1x/hari.
4. CONSERVATIVE adalah default saat startup.
5. Math adalah primary decision engine. AI hanya support.
6. LIMIT order default. MARKET hanya untuk emergency cut loss dan breakout catch A+.

Jika ada instruksi bertentangan, tolak dan minta klarifikasi eksplisit.

---

## ARSITEKTUR

```text
KINANCE (port 8788) -> UDP signal <500ms -> KIBOT MANAGER (port 9998)
                                              -> UDP heartbeat 100ms
KIDAX (port 8787) -> Indodax API
```

## ENTRY GATE

```text
Gate 1: PnL State
Gate 2: Hard Stop Disk
Gate 3: Capital Min
Gate 4: Pair Whitelist
Gate 5: Signal TTL
Gate 6: Math Score
Gate 7: What-If EV
Gate 8: Learning Gate
Gate 9: AI Veto (soft only)
Gate 10: Order Submit
```

## PNL STATE MACHINE

HEALTHY > -0.5%
WARNING -0.5% to -1%
CRITICAL -1% to -2%
HARD_STOP < -2%
ONE_SHOT setelah HARD_STOP
FULL_STOP setelah ONE_SHOT gagal

## 30-MINUTE MATH REVIEW

Wajib ada review periodik 30 menit:
- win rate
- average win / loss
- EV per trade
- profit factor
- trades needed to recover
- trades possible before midnight WIB

Hasil review dipakai untuk:
- CONTINUE
- DEFENSIVE
- TIGHTEN_FILTER
- PREPARE_STOP
- HARD_STOP

## WHAT-IF & BREAKOUT

- Signal fresh <500ms boleh breakout catch.
- Kalau breakout_urgent aktif, MARKET bisa dipakai untuk entry cepat.
- Jika signal >2s, skip.
- Koin dekat peak: partial TP dan tighten trailing.
- Koin turun: trailing stop jalan, jangan average down.

## GUARDRAILS

1. No panic sell on UDP timeout.
2. Adaptive trailing stop untuk micro-cap.
3. Cooldown loss max 15 menit per pair.
4. Signal stale harus dibuang.
5. AI degraded = warning only.
6. Daily hard stop persist ke disk.
7. ONE_SHOT override hanya 1x/hari.
8. LIMIT default. MARKET hanya emergency atau breakout catch A+.
9. Pair unknown auto-reject.
10. PnL check 30 detik, math review 30 menit.
11. Minimum capital guard wajib aktif.
12. No average down saat rugi.

## LEARNING SYSTEM

pair_memory harus menyimpan:
- rolling trade stats
- win rate per jam
- cooldown tracker
- fake pump counter

EXECUTION_FILLED harus meng-update learning state.

## DEPLOYMENT

Sebelum push:
- compile Python
- compile Kotlin
- pastikan server config dan repo tidak drift

Version: 4.0 math-first
