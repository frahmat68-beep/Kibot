# Failure Modes

## Fail-Safe Triggers

- Lease conflict or stale term
- Reconciliation not clean
- Hard daily loss limit reached
- Exchange state ambiguity after restart/takeover
- Control plane sync broken
- Android battery/network health too weak for safe trading

## Expected Behavior

- New entries blocked immediately.
- Pending entry orders canceled when safe.
- Existing open positions remain observable.
- Bot enters `SAFE_MODE` until the next safe resume decision.

## Examples

- Android dies after submitting order but before persisting result
  Mac must reconcile `open orders + recent fills + balances` before any new action.
- Both devices believe they are active
  Fencing token mismatch should stop trade writes, mark conflict, and force safe mode.
- Websocket disconnect
  Polling fallback may continue monitoring, but entry quality and health score degrade.

