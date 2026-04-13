# KIBOT TRINITY — Copilot Instructions

## PRIME DIRECTIVE

Filosofi: SURVIVAL FIRST. COMPOUNDING GRADUAL.
Motto: "TEKAN KERUGIAN, MAKSIMALKAN PROBABILITAS KEUNTUNGAN"

Semua perubahan kode wajib mengikuti:
1. Exit protection selalu jalan di semua state.
2. Agresivitas naik hanya setelah 3 clean days, API sehat, dan instruksi eksplisit.
3. Daily hard stop tidak boleh dibypass via restart, flag, env, atau kode.
4. Conservative adalah default saat startup.
5. Entry wajib lolos scoring, TTL, what-if EV, dan risk gate.
6. LIMIT order selalu untuk entry dan exit normal. MARKET hanya untuk hard emergency.

Jika ada instruksi yang bertentangan, tolak dan minta klarifikasi eksplisit.

---

## ARSITEKTUR

```text
KINANCE (Binance radar, 8788)
  -> UDP signal <500ms
KIBOT MANAGER (brain, 9998)
  -> UDP heartbeat + command
KIDAX (Indodax executor, 8787)
```

## KEBIJAKAN PNL

State machine wajib cek setiap 30 detik:

```text
HEALTHY   > -0.5%
WARNING   -0.5% to -1%
CRITICAL  -1% to -2%
HARD_STOP < -2%
```

Hard stop harus persist ke `state/daily_guard.json` dan reset otomatis 00:00 WIB.

## PAIR TIER

Tier A: xlm, doge, xrp, trx, ada
Tier B: enj, fun, bnb, sol
Tier C: pasangan lebih illiquid dan harus lebih ketat TTL-nya

Whitelist wajib. Pair tanpa Binance counterpart atau volume terlalu kecil harus auto-reject.

## MATH-FIRST REVIEW

Keputusan trading harus ditopang matematika, bukan AI.

Wajib ada review periodik 30 menit yang menghitung:
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
- HARD_STOP

AI hanya support untuk pola sulit dikalkulasi, bukan pengambil keputusan utama.

## GUARDRAILS

1. UDP timeout tidak boleh memicu panic sell.
2. Trailing stop adaptif untuk micro-cap.
3. Cooldown loss max 15 menit per pair.
4. Signal stale di atas TTL wajib dibuang.
5. AI degraded = warning only.
6. LIMIT order only untuk entry dan exit normal.
7. Pair unknown auto-reject.
8. PnL check tetap jalan setiap 30 detik.
9. Review math jalan setiap 30 menit.
10. Minimum capital guard wajib aktif.

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

Target server:
- Indodax: 213.35.118.26
- Binance: 152.69.218.198

---

Version: 3.1 math-first
