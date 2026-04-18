# KiBot Oncall Quickstart (1 Page)

Use this for incidents, degraded health, or post-deploy validation.
Target: decide safe action in under 10 minutes.

## 0) Topology Snapshot

- Node A (primary): `213.35.118.26` / `kidax-engine`
- Node B (radar): `152.69.218.198` / `kinance-engine`
- APIs:
  - Node A: `http://localhost:8787`
  - Node B: `http://localhost:8788`

## 1) 5-Minute Health Check

Run on each node:

```bash
systemctl is-active kidax-engine kinance-engine kibot-manager kibot-orchestrator kibot-auditor kibot-guardian kibot-security kibot-notifier
curl -fsS http://localhost:8787/api/health || true
curl -fsS http://localhost:8788/api/health || true
```

Green baseline:
- all services `active`
- engine API responds
- no rapid restart loop

## 2) Fast State Triage

```bash
curl -fsS http://localhost:8787/api/state | jq '.connections, .pairScores[0:5], .rawState.scanUniverseCount, .rawState.lastRejectedReason'
curl -fsS http://localhost:8788/api/state | jq '.connections, .pairScores[0:5], .rawState.scanUniverseCount, .rawState.lastRejectedReason'
```

Interpretation:
- `scanUniverseCount > 0` and `pairScores` populated = market feed pipeline is alive.
- `pairScores=[]` + `scanUniverseCount=0` = feed/radar path broken.
- `lastRejectedReason` set repeatedly = strategy blocked by guardrail; inspect reason text.

## 3) Log Filters That Matter

```bash
journalctl -u kidax-engine --since "15 min ago" --no-pager | egrep -i "DEAD_ZONE_TRACE|EXCHANGE_FETCH|health warnings detail|FAILED|Invalid symbol|stale|duplicate|already exists"
journalctl -u kinance-engine --since "15 min ago" --no-pager | egrep -i "DEAD_ZONE_TRACE|EXCHANGE_FETCH|health warnings detail|FAILED|Invalid symbol|stale|duplicate|already exists"
journalctl -u kibot-manager --since "15 min ago" --no-pager | tail -n 120
```

High-signal patterns:
- `Market quote feed fetch failed` => exchange feed issue
- `Invalid symbol` => symbol mapping/fallback issue
- `already exists`/`duplicate` => order id retry/reconciliation problem
- repeated heartbeat timeout lines => peer link degraded

## 4) Common Incident Playbook

### Case A: Engine active but feed empty
Symptoms:
- `scanUniverseCount=0`
- `pairScores=[]`
- warning about market quote feed

Actions:
1. Check engine profile/env identity (`BOT_ID`, `BOT_PROFILE_KEY`, `KIBOT_EXCHANGE_KIND`).
2. Check service unit does not override wrong identity.
3. Restart only affected engine:
   - `sudo systemctl restart kidax-engine` or `sudo systemctl restart kinance-engine`
4. Recheck `/api/state` in 1-2 minutes.

### Case B: Duplicate order id or repeated rejection
Symptoms:
- logs contain `already exists` / `duplicate`

Actions:
1. Verify latest `main` includes retry/reconciliation fix in `LiveExecutionCoordinator`.
2. Rebuild/deploy artifact to both nodes from same commit.
3. Observe 10 minutes for recurrence before closing incident.

### Case C: Manager noisy or restart-limited
Symptoms:
- heartbeat flood or frequent service restarts

Actions:
1. Validate `scripts/kibot_manager.py` is full runtime file (not placeholder).
2. Verify heartbeat interval env is sane for low-resource nodes.
3. Restart `kibot-manager`, then monitor 2-3 minutes.

## 5) Safe Restart Order

If full-cycle restart is needed:

1. `kinance-engine` (Node B)
2. `kibot-manager` (both nodes if required)
3. `kidax-engine` (Node A)

Why:
- bring radar/feed first, then coordinator, then primary executor.

## 6) Close Incident Criteria

Only close when all conditions are true:
- both engine services stay `active`
- `/api/health` stable for both nodes
- `scanUniverseCount` and `pairScores` are populated where expected
- no repeating critical warning pattern for at least 10 minutes

## 7) Canonical Docs

- Full architecture: `docs/architecture/TWO_SERVER_SYSTEM_GUIDE.md`
- Deployment details: `docs/TRINITY_DEPLOYMENT.md`
- Checklist: `docs/ops/server-deploy-checklist.md`

