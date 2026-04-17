# KiBot Two-Server System Guide

This document is the primary reference for how KiBot runs across two production servers.
Use this as the first onboarding and debugging entry point for humans and coding agents.

## 1) System Purpose

KiBot is a distributed trading runtime split across two Oracle VPS nodes:

- Node A (`kidax-engine`): primary Indodax execution node.
- Node B (`kinance-engine`): Binance radar/market-feed node and peer for lead-lag coordination.

Both nodes share the same control-plane and exchange heartbeats/signals over UDP.

## 2) Production Nodes

### Node A (Primary Runtime)
- Host: `213.35.118.26`
- Hostname: `kibot-kotlin-1`
- Primary service: `kidax-engine`
- Profile: `BOT_ID=main`, `BOT_PROFILE_KEY=indodax`, `KIBOT_EXCHANGE_KIND=INDODAX`

### Node B (Radar Runtime)
- Host: `152.69.218.198`
- Hostname: `kibot-binance`
- Primary service: `kinance-engine`
- Profile: `BOT_ID=kinance`, `BOT_PROFILE_KEY=kinance`, `KIBOT_EXCHANGE_KIND=BINANCE_SPOT`

## 3) Capacity and Constraints

Both nodes are low-resource Oracle instances:

- CPU: `2 vCPU`
- RAM: around `954 MB`
- Swap: around `2 GB`

Design implications:

- JVM memory must stay conservative.
- Runtime loops must avoid expensive fan-out retries.
- Background daemons must be lightweight and restart-safe.

## 4) Service Inventory (Both Nodes)

Core Java/Kotlin runtime:

- `kidax-engine` (Node A) / `kinance-engine` (Node B)
  - Runs `apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar`
  - Owns strategy cycle, health gates, lease/ownership, and order execution logic

Python control/support layer:

- `kibot-manager`
  - UDP heartbeat/signal bridge and veto/control support
- `kibot-orchestrator`
  - Service orchestration/runtime coordination
- `kibot-auditor`
  - Runtime checks and anomaly auditing
- `kibot-guardian`
  - Safety guardrails and recovery helpers
- `kibot-security`
  - Security checks/hardening helpers
- `kibot-notifier`
  - Notifications and operational status delivery

## 5) End-to-End Runtime Flow

1. Bootstrap
- Engine loads env/runtime config, resolves exchange profile, and registers device identity.

2. Control-plane sync
- Engine reads bot state, lease term, device snapshots, commands, and risk context.

3. Exchange fetch stage
- Fetches balances, open orders, market quotes, and probes exchange reachability.

4. Strategy stage
- Builds market/radar context (`scanUniverseCount`, `pairScores`, candidate ranking).
- Applies health/risk gates before entry/exit planning.

5. Execution stage
- Builds execution plans.
- Submits orders to exchange gateway.
- Persists/reconciles order snapshots against control-plane state.

6. Cross-node coordination
- UDP heartbeat and lead-lag signal exchange between nodes.
- Lease and heartbeat conditions decide ownership and failover readiness.

7. Dashboard/API publishing
- Node A serves state/health on `:8787`.
- Node B serves state/health on `:8788`.

## 6) Configuration Source of Truth

Rules:

- `EnvironmentFile` values must stay authoritative for node identity.
- Avoid overriding `BOT_ID`, `BOT_PROFILE_KEY`, and `KIBOT_RUNTIME_ROOT` in systemd unit body unless intentionally required.
- Logical role is more important than historical physical label.

Related files:

- `infra/systemd/kidax-engine.service`
- `infra/systemd/kinance-engine.service`
- `infra/systemd/kibot-manager.service`
- `apps/mac-engine/src/main/kotlin/com/kibot/macengine/config/MacRuntimeConfig.kt`

## 7) Known Failure Modes and Root Causes

### A) Empty Binance universe (`scanUniverseCount=0`)

Symptoms:
- `pairScores=[]`
- strategy cycle repeatedly absent
- health warnings around market quote feed

Root causes previously seen:
- Invalid symbol handling in Binance ticker batch fallback dropped too many symbols.
- Runtime identity/config drift between env files and systemd overrides.

Code paths:
- `packages/binance-client/.../BinanceGateway.kt`
- `apps/mac-engine/.../MacEngineDaemon.kt` (market quote fetch/enrichment and health gating)

### B) Duplicate client order id retries

Symptoms:
- Exchange rejection with messages like `already exists`/`duplicate`
- repeated submission attempts with same client order id

Root cause:
- Retry loop did not short-circuit cleanly on explicit exchange rejections.

Code path:
- `packages/core/.../LiveExecutionCoordinator.kt`

### C) Manager process starts then exits

Symptoms:
- `kibot-manager` repeatedly restart-limited

Root cause seen:
- Placeholder/incomplete manager script deployed instead of full runtime file.

Code path:
- `scripts/kibot_manager.py`

## 8) Operational Checks

Service status:

```bash
systemctl is-active kidax-engine kinance-engine kibot-manager kibot-orchestrator kibot-auditor kibot-guardian kibot-security kibot-notifier
```

API health/state:

```bash
curl -fsS http://localhost:8787/api/health
curl -fsS http://localhost:8787/api/state
curl -fsS http://localhost:8788/api/health
curl -fsS http://localhost:8788/api/state
```

Live logs:

```bash
journalctl -u kidax-engine -f
journalctl -u kinance-engine -f
journalctl -u kibot-manager -f
```

## 9) Deploy Discipline

- Build once from `main`, deploy same artifact to both nodes.
- Deploy unit/env changes together with runtime code when identity/routing is touched.
- Observe logs post-deploy for at least 10 minutes.
- Do not close incident until:
  - both engine services are active,
  - health endpoints stable,
  - expected market quotes and radar fields are populated,
  - no recurring critical warning/error pattern.

## 10) Repository Pointers

- Blueprint summary: `KiBot_Blueprint.md`
- This detailed architecture guide: `docs/architecture/TWO_SERVER_SYSTEM_GUIDE.md`
- Deployment references:
  - `docs/TRINITY_DEPLOYMENT.md`
  - `docs/ops/server-deploy-checklist.md`
  - `docs/ops/github-actions-two-server.md`

