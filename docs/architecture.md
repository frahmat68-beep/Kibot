# Architecture

## High Level

- Android is the default master-capable engine and primary control surface.
- Mac is the standby engine with takeover capability.
- Supabase is the shared control plane and audit store.
- Shared Kotlin modules keep the trading and safety rules consistent across both runtimes.

## Safety Model

- Only one lease holder may trade at a time.
- Lease ownership is protected by monotonically increasing `term`.
- Trade writes require both a valid lease and a short-lived execution action reservation.
- Takeover requires reconciliation before new entries.
- Conflict or ambiguous exchange state forces `SAFE_MODE`.

## Runtime Split

- `apps/android`
  Android UI, foreground service, Room cache, secure local storage, periodic watchdog workers.
- `apps/mac-engine`
  JVM daemon, local dashboard, takeover operator surface.
- `packages/shared-models`
  Serializable transport models shared by Android and Mac.
- `packages/core`
  Lease rules, health rules, pair selection, risk, reconciliation.
- `packages/control-plane`
  Shared Supabase auth, polling snapshot client, RPC wrappers, and control-plane gateway.
- `packages/indodax-client`
  Exchange adapter, signed REST helpers, and reconciliation inputs from Indodax.
- `packages/test-kit`
  Fake exchange and scenario testing support.
- `infra/supabase`
  SQL schema, RLS, RPC functions, cleanup policy.

## Phase 3 In Progress

- Polling-based Supabase command/state sync is wired for the Mac daemon.
- Safe takeover loop is exercised by failover tests with fake control-plane + fake exchange.
- Indodax REST path covers balances, open orders, fills, order submit, and cancel by `client_order_id`.
- Android repository is prepared to consume the shared control-plane gateway when device config is available.
- Remaining work: realtime subscriptions, encrypted credential bundle flow, Android live runtime wiring, and strategy/execution loops.
