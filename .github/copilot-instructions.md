# KIBOT TRINITY — Copilot Instructions v6.2
# POST-MORTEM: ownership split + DEGRADED loop + control-plane timeout
# Last verified: 2026-04

## PRIME DIRECTIVE

Filosofi: SURVIVAL FIRST. COMPOUNDING GRADUAL.
Sedikit demi sedikit lama lama jadi bukit.

Wajib dipatuhi:
1. Exit protection SACRED — trailing stop & cut loss jalan di SEMUA state
2. LEASE adalah primary ownership authority (bukan list)
3. Trading BLOCK saat: 0 quotes, equity < Rp 10,000, DEGRADED > 5 menit
4. Control-plane write TIDAK BOLEH block trading (max 3s timeout, non-throwing)
5. EMERGENCY_NUKE: hanya jika PnL <= -2%, cooldown 30 menit
6. CRASH_GUARD: LIMIT sell dulu, MARKET hanya fallback 5s
7. Math = primary engine. AI = support only
8. LIMIT order default. MARKET hanya emergency

## OWNERSHIP RESOLUTION

RULE: LEASE = canonical authority.
- `ownerByList=true + ownerByLease=true` -> OWNER
- `ownerByList=false + ownerByLease=false` -> NOT OWNER
- `ownerByList=true + ownerByLease=false` -> NOT OWNER (lease wins, safety)
- `ownerByList=false + ownerByLease=true` -> OWNER (lease wins)

Log ownership hanya saat state berubah atau mismatch muncul.

## MARKET DATA VALIDATION

Trading wajib diblok jika:
- `quotes == 0`
- `equity < Rp 10,000`
- semua quote stale (`> 60 detik`)
- DEGRADED mode bertahan `> 5 menit`

`EXCHANGE_PROBE` wajib pakai exponential backoff `5s -> 10s -> 20s -> 40s -> max 120s`.

## ENTRY GATE

Urutan gate:
1. Ownership lease-confirmed
2. Market data valid
3. PnL state
4. Hard stop disk state
5. Capital minimum
6. Signal TTL
7. Pump score
8. What-if EV
9. Balance validation
10. LIMIT submit

## PNL STATE MACHINE

- `HEALTHY`: `> -0.5%`
- `WARNING`: `-0.5%` sampai `-1.0%`
- `CRITICAL`: `-1.0%` sampai `-2.0%`
- `HARD_STOP`: `<= -2.0%`

## THRESHOLDS

- `EMERGENCY_NUKE`: `<= -2.0%`, cooldown `30 menit`
- `CRASH_GUARD`: LIMIT sell dulu, MARKET fallback `5 detik`
- `DAILY_HARD_STOP`: `<= -2.0%`, wajib persist ke disk
- `HARD_STOP_CHECK`: cache `30 detik`
- `STALE_ORDER`: auto-cancel `> 5 menit`
- `CONTROL_PLANE_TIMEOUT`: `3 detik max`, non-throwing
- `/api/state` cache: refresh background `500ms`
- `DEGRADED_MAX`: `5 menit`, lalu pause/recovery path

## GUARDRAILS

1. No panic sell on UDP timeout.
2. Adaptive trailing sesuai price regime.
3. Rational quarantine max 15 menit per pair.
4. Strict signal TTL.
5. Soft AI-audit only.
6. Daily hard stop `<= -2%` harus persisted.
7. Nuke cooldown 30 menit.
8. LIMIT default, MARKET hanya emergency.
9. Market data gate wajib.
10. Ownership gate wajib lease-confirmed.
11. Minimum capital gate wajib.
12. Control-plane non-blocking wajib.
