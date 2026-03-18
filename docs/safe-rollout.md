# Safe Rollout

## Stage 1

- Apply Supabase migrations.
- Register Android and Mac devices.
- Keep live trading disabled.
- Validate UI, commands, lease transitions, and heartbeat only.

## Stage 2

- Enable read-only exchange sync.
- Validate balances, open orders, recent trades, and reconciliation after restart.
- Validate Android to Mac takeover with no live order submit.

## Stage 3

- Enable live trading with tiny capital.
- Keep Android charging and Mac online.
- Watch logs for stale term, sync degradation, and reconciliation warnings.

## Stage 4

- Enable unattended operation only after:
  - repeated clean failover drills
  - no duplicate order behavior
  - safe daily reset behavior
  - hard loss stop proven in dry and live conditions

