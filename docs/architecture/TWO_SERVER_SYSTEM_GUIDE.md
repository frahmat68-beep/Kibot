# KiBot Two-Server System Guide

Last verified: 2026-04-18 (after compile/test + live SSH audit).

This is the primary onboarding/debugging map for humans and coding agents.

## 0) Core Rule: Identity Is Config-Driven

Never infer runtime behavior from folder names alone.
The active role is determined by env + service config:

- `BOT_ID`
- `BOT_PROFILE_KEY`
- `KIBOT_EXCHANGE_KIND`
- `DEVICE_ID`

Legacy labels like "Indodax server" / "Binance server" are shorthand only.
Operationally, treat both nodes as role-based runtime workers tied by control-plane + UDP.

## 1) Current Production Topology (2 Nodes)

## Node A (`kibot-kotlin-1`)

- Host: `213.35.118.26`
- Main engine service: `kidax-engine`
- API port: `8787`
- UDP: `9999` (lead-lag/trinity bus)
- Observed capacity:
  - CPU: `2 vCPU` (AMD EPYC 7742)
  - RAM: `954 MB`
  - Swap: `2047 MB`
  - Disk: `48 GB` root (`~25%` used during audit)

## Node B (`kibot-binance`)

- Host: `152.69.218.198`
- Main engine service: `kinance-engine`
- API port: `8788`
- UDP: `9999` (lead-lag/trinity bus)
- Active role in current 2-server topology:
  - global scanner / lead-lag radar
  - live execution disabled intentionally (`BOT_ENABLE_LIVE_EXECUTION=false`)
  - still emits heartbeat, UDP callouts, market scan, and AI-assisted review context
- Observed capacity:
  - CPU: `2 vCPU` (AMD EPYC 7742)
  - RAM: `954 MB`
  - Swap: `2047 MB`
  - Disk: `48 GB` root (`~22%` used during audit)

## 2) System Inventory Per Node

## A) Kotlin/JVM Trading Runtime

- `kidax-engine` (Node A) / `kinance-engine` (Node B)
- Artifact: `apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar`
- Main responsibilities:
  - strategy cycle orchestration (`MacEngineDaemon`)
  - control-plane sync (state/lease/device/commands)
  - exchange gateway calls (Indodax/Binance)
  - execution planning + order submission
  - risk/health gating
  - local dashboard APIs (`/api/health`, `/api/state`)

## B) Python Sidecar Runtime (Ops + Coordination)

Primary daemons:

- `kibot-manager`
  - UDP bridge, veto/control helper, AI provider coordination
  - local control endpoint follows `KIBOT_MANAGER_UDP_BIND_PORT`
  - observed live during audit:
    - Node A `9998`
    - Node B `9996`
- `kibot-orchestrator`
  - runtime health orchestration
- `kibot-auditor`
  - startup/runtime audit + repair hooks
- `kibot-guardian`
  - safety/restart guardrails
- `kibot-security`
  - security checks
- `kibot-notifier`
  - notification/log fanout
- `kibot-analyst` (optional/on-demand in many deployments)

## C) Control Plane + Shared State

- Supabase is the shared control-plane for both nodes.
- Core shared models live in `packages/shared-models`.
- Control-plane client path:
  - interface: `packages/core/.../ControlPlaneGateway.kt`
  - implementation: `packages/control-plane/.../SupabaseControlPlaneClient.kt`

## 3) End-to-End Call Path (Kotlin + Python)

## 1. Bootstrap

- Loader: `apps/mac-engine/.../MacRuntimeConfig.kt`
- Engine bootstrap: `apps/mac-engine/.../Main.kt`
- Daemon core: `apps/mac-engine/.../MacEngineDaemon.kt`

## 2. Sync Cycle (`syncOnce`)

- Fetch bot state + lease + devices + commands from control-plane.
- Fetch balances/orders/quotes via exchange gateway.
- Build strategy context (ranked pairs, health, risk, mode).
- Evaluate entry/exit plans.
- Submit/track orders through `LiveExecutionCoordinator`.

## 3. Cross-Node Coordination

- UDP heartbeats + lead-lag signals over port `9999`.
- Health snapshots + runtime status from both nodes influence gating decisions.
- Lease ownership protects against split-brain execution.

## 4. Python Sidecar Interaction

- `kibot-manager` receives/bridges event streams and AI approval context.
- Guard/analyst/orchestrator services monitor liveness and anomalies.
- Systemd restart policy keeps sidecars resilient under low-RAM pressure.

## 4) Integration Hotspots (.kt + .py)

These are common root-cause zones when integration drifts:

- Config contract drift:
  - `MacRuntimeConfig` fields vs env keys vs systemd overrides
- Control-plane interface drift:
  - `ControlPlaneGateway` method changes not mirrored in fakes/clients
- Runtime helper API drift:
  - `CapitalAllocationManager` call-site mismatches in `MacEngineDaemon`
- Signal/whitelist persistence drift:
  - `PairWhitelistManager` schema/serialization mismatch
- Test-kit contract drift:
  - `packages/test-kit/.../FakeControlPlaneGateway.kt` lagging behind interface

## 5) Capacity Envelope and Safety Budgets

Both nodes are micro-capacity boxes.
Treat these as hard design constraints:

- keep JVM heaps conservative (`~256-360M` class)
- avoid heavy fan-out/retry storms
- keep sidecars lightweight and restart-safe
- prefer bounded polling/backoff over burst retries

If memory pressure rises, prioritize:

1. stabilize engine + manager first,
2. then re-enable optional sidecars.

## 6) Deploy Contract (Must Follow)

1. Build once from `main`.
2. Deploy the same artifact hash to both nodes.
3. Restart relevant services in controlled order.
4. Observe logs and APIs for at least 10 minutes.
5. Close only after both nodes are healthy and stable.

Recommended references:

- `docs/TRINITY_DEPLOYMENT.md`
- `docs/ops/server-deploy-checklist.md`
- `docs/ops/ONCALL_QUICKSTART.md`

## 7) 10-Minute Post-Deploy Validation

Minimum checks on both nodes:

- `systemctl is-active` for engine + manager + core sidecars
- `/api/health` returns `status=ok`
- `/api/state` returns non-empty runtime state
- no repeating high-signal errors in journal:
  - stale feed loops
  - duplicate order-id retries
  - lease conflict loops
  - malformed payload/JSON loops

## 8) Known Failure Signatures -> Root Causes

## A) `scanUniverseCount=0` / `pairScores=[]`

- likely feed symbol mapping/runtime identity mismatch
- inspect gateway fallback + env identity

## B) repeated `duplicate/already exists` on order submit

- execution retry path not short-circuiting correctly
- inspect `LiveExecutionCoordinator`

## C) manager active but engine degraded

- peer heartbeat path degraded or control-plane throttled
- inspect manager UDP/log pipeline and lease status

## D) compile/runtime drift after large updates

- usually interface or call-signature drift across:
  - core
  - mac-engine
  - control-plane
  - test-kit

## 9) Source of Truth Files

- Runtime entry:
  - `apps/mac-engine/src/main/kotlin/com/kibot/macengine/Main.kt`
  - `apps/mac-engine/src/main/kotlin/com/kibot/macengine/runtime/MacEngineDaemon.kt`
- Runtime config:
  - `apps/mac-engine/src/main/kotlin/com/kibot/macengine/config/MacRuntimeConfig.kt`
- Core contracts:
  - `packages/core/src/commonMain/kotlin/com/kibot/core/ControlPlaneGateway.kt`
  - `packages/core/src/commonMain/kotlin/com/kibot/core/CapitalAllocationManager.kt`
  - `packages/core/src/commonMain/kotlin/com/kibot/core/PairWhitelistManager.kt`
- Control-plane implementation:
  - `packages/control-plane/src/commonMain/kotlin/com/kibot/controlplane/SupabaseControlPlaneClient.kt`
- Systemd runtime:
  - `infra/systemd/kidax-engine.service`
  - `infra/systemd/kinance-engine.service`
  - `infra/systemd/kibot-manager.service`
