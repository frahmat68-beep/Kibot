# KIBOT TRINITY — Copilot Instructions v6.1
# POST-MORTEM FIX + SURVIVAL FIRST
# Last updated: 2026-04

## PRIME DIRECTIVE

Filosofi: SURVIVAL FIRST. COMPOUNDING GRADUAL.
Kerugian post-mortem: -2.94% dipicu emergency sell loop.

Wajib dipatuhi:
1. Exit protection SACRED — trailing stop & cut loss jalan di SEMUA state.
2. EMERGENCY_GARBAGE_NUKE hanya jika daily PnL <= -2.0%.
3. CRASH_GUARD pakai LIMIT sell dulu, MARKET fallback jika LIMIT gagal.
4. Daily hard stop harus persisted + throttle check (hindari loop spam).
5. Math = primary engine. AI = support only.
6. LIMIT order default. MARKET hanya emergency.
7. Cancel stale order >5 menit.
8. Validasi balance sebelum submit exit order.

## THRESHOLDS (POST-FIX)

- `EMERGENCY_GARBAGE_NUKE`: <= -2.0%
- `DAILY_HARD_STOP`: <= -2.0%
- `NUKE_COOLDOWN`: 30 menit
- `DAILY_STOP_CHECK`: cache/throttle 30 detik
- `STALE_ORDER_AGE`: 5 menit

## ENTRY GATES

1. PnL State (HEALTHY/WARNING/CRITICAL/HARD_STOP)
2. Hard Stop state file
3. Capital minimum
4. Stale lock cleanup
5. Signal freshness
6. Pump score threshold
7. What-if EV positive
8. Balance validation
9. AI veto soft (warning)
10. LIMIT-first execution

## PNL STATE MACHINE

- `HEALTHY`: > -0.5%
- `WARNING`: -0.5% to -1.0%
- `CRITICAL`: -1.0% to -2.0%
- `HARD_STOP`: <= -2.0%

## EXIT RULES

1. CRASH_GUARD: LIMIT sell (fast-limit), MARKET fallback only jika tidak fill.
2. EMERGENCY_NUKE: trigger drawdown signifikan + cooldown 30 menit.
3. Balance validate: gunakan min(target_qty, actual_balance).
4. Stale order auto-cancel >5 menit.
5. Lock cleanup setelah cancel/fill/timeout.

## GUARDRAILS

1. No panic sell on transient timeout.
2. Adaptive trailing per price regime.
3. Rational quarantine per pair.
4. Strict signal TTL.
5. Soft AI-audit.
6. Daily hard stop <= -2% (persist + reset harian).
7. Nuke cooldown 30 menit.
8. LIMIT default, MARKET hanya emergency.
9. Stale order cleanup wajib.
10. Balance validation wajib.
11. Minimum capital gate.
12. Hard-stop check wajib di-throttle.
