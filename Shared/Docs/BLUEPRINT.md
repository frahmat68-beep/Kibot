# KiBot Trinity Blueprint

This is the source of truth for the three-node KiBot layout shown in `KiBot_Diagram.png`.

## 1) Cluster topology

| Node | Role | Public IP | Main duty |
|---|---:|---:|---|
| Batam | Brain / Hub | `168.110.201.228` | Brain Logic, AI Veto, Ollama, Telegram Ops, Web Dashboard |
| EXECUTOR | Executor | `213.35.118.26` | Indodax Engine, Polymarket Engine (Centralized Execution) |
| SCANNER | Scanner Node | `152.69.218.198` | Super Scanner Cluster (20 sources + aggregator) |

## 2) Diagram meaning

The diagram is not a flat mesh. It is a hub-and-spoke system:

- **SCANNER -> Batam**: market data, scanner signal, heartbeat, and summary feed.
- **Batam -> EXECUTOR**: control plan, maintenance directives, and execution posture.
- **Batam -> Web Dashboard**: read-only realtime view.
- **Batam -> Android app**: read-only realtime view.
- **Batam -> Telegram**: daily report and urgent alerts only.

So the visible surfaces are observers. They do not own execution.

## 3) What runs on each node

### Batam main system

Core services:

- `kibot-manager`
- `kibot-analyst`
- `kibot-auditor`
- `kibot-notifier`
- `kibot-orchestrator`
- `kibot-security`
- `kibot-guardian`
- `kibot-ollama-gateway`
- `ki-telegram-monitor` (Ops Alerts)
- `indodax-dashboard-proxy` (Web Dashboard)
- `lazarus-ampere`
- `ollama`
- `netdata`

Batam is the only node that should own brain-level work:

- strategy generation (Math + AI)
- AI veto / AI review (Ollama)
- daily summary and post-mortem
- infra watchdog and auto-heal
- Telegram ops reporting
- OCR/ops helpers and cluster maintenance
- Dashboard Proxy & Android Bridge

### EXECUTOR executor

Core services:

- `kibot-executor-indodax` (Indodax)
- `kibot-polymarket` (Polymarket)

Role:

- execute Indodax orders locally
- keep hot-path state small
- obey Batam control and local hard guards
- send execution/health state back to Batam

### SCANNER scanner

Core services:

- `kibot-scanner@*` (20 active sources: 17 global exchanges + Indodax + Polymarket + Kraken)
- `ki-global-scanner-mesh` (aggregator + Batam relay)

Role:

- scan global markets
- publish scanner feed and heartbeats
- send signals and market summaries to Batam
- stay lean and fast

## 4) Core ports

| Port | Surface | Meaning |
|---|---|---|
| `9998` | Batam manager HTTP | Brain / control plane state |
| `9999` | Batam manager UDP | Fast signal and heartbeat channel |
| `8787` | Batam Dashboard | Trinity Control Center (Proxy to EXECUTOR) |
| `8787` | EXECUTOR engine | Executor state and health |
| `11435` | Batam Ollama tunnel | Local AI access on Batam |
| `11600` | EXECUTOR Polymarket state | Polymarket runtime state |
| `19999` | Batam Netdata | Host-level monitoring |

## 5) Service wiring rules

These rules define the diagram in practice:

1. Batam is the only brain hub.
2. EXECUTOR is execution only.
3. SCANNER is scanner only.
4. SG nodes send data upstream; Batam sends control downstream.
5. Read-only surfaces never become control surfaces.
6. No SG node should depend on a remote SG service for its own boot path.
7. Batam support services should follow Batam brain services, not EXECUTOR.

The important fix already applied in this repo is:

- `kibot-analyst.service` now follows `kibot-manager.service`, not `kibot-executor-indodax.service`.

## 6) Control, signal, and reporting flow

### Control flow

1. Batam evaluates market posture.
2. `kibot-manager` decides allow/block/reduce/exit posture.
3. EXECUTOR receives execution posture and acts locally.
4. SCANNER receives scan posture and keeps publishing feed.

### Signal flow

1. SCANNER scanner mesh produces market signals.
2. Signals are mirrored to Batam.
3. Batam merges scanner data with AI and risk state.
4. Batam writes the resulting plan into state files and runtime memory.

### Reporting flow

1. `kibot-analyst` writes summaries and daily journals.
2. `kibot-notifier` and `ki-telegram-monitor` push urgent or daily notifications.
3. The dashboard and phone app only render realtime state.

## 7) Main features

### Trading safety

- hard stop always active
- trailing stop always active
- exit ladder for partial profit taking
- daily loss protection
- pair cooldown / stale-signal protection
- duplicate-signal suppression
- conviction and regime gating

### Brain / AI layer

- central `kibot-manager`
- AI review and fallback logic
- nightly post-mortem and plan refresh
- compact memory state for decisions

### Scanner layer

- 20-source super cluster (SCANNER)
- Global feed aggregation (Mesh)
- Supported: Binance, Bybit, Kucoin, OKX, Upbit, MEXC, Gate, Bitget, HTX, LBank, Bitbank, Bitmart, Coinbase, Phemex, Bithumb, Whale, Indodax, Polymarket, Kraken
- Heartbeat and feed mirroring to Batam
- Local-first fast scanner cycle (30s)

### Ops layer

- guardian auto-restart
- auditor and security monitor
- notifier and Telegram bridge
- Netdata on Batam
- Lazarus Ampere helper for OCI instance hunting

`lazarus-ampere` is Batam-only. It randomizes the availability domain, keeps the target small, retries with cooldowns, sends Telegram on success, and stops after it gets an instance.

## 8) Speed model

KiBot is intentionally asymmetric:

- **EXECUTOR / SCANNER** stay lean and fast.
- **Batam** is heavier, but it must not block every market tick.

Current timing profile:

- manager fast loop: about 30s
- manager medium loop: about 5m
- manager slow review: nightly
- scanner mesh cycle: 30s
- scanner heartbeat: 1s
- engine heartbeats: ~1s with short timeout windows
- Telegram reports: daily or urgent only

The result is low-latency execution on EXECUTOR/SCANNER and slower but smarter control on Batam.

## 9) Accessing the servers

Use the SSH inventory in `ops/SERVERS.json`.

| Node | SSH target | Key path |
|---|---|---|
| Batam | `ubuntu@168.110.201.228` | `SSH_BATAM/ssh-key-batam-active.pem` |
| SCANNER | `ubuntu@152.69.218.198` | `SSH_SINGAPORE/SSH_SCANNER/ssh-key-2026-03-27.key` |
| EXECUTOR | `ubuntu@213.35.118.26` | `SSH_SINGAPORE/SSH_EXECUTOR/ssh-key-2026-03-22.key` |

Example:

```bash
ssh -i SSH_BATAM/ssh-key-batam-active.pem ubuntu@168.110.201.228
ssh -i SSH_SINGAPORE/SSH_SCANNER/ssh-key-2026-03-27.key ubuntu@152.69.218.198
ssh -i SSH_SINGAPORE/SSH_EXECUTOR/ssh-key-2026-03-22.key ubuntu@213.35.118.26
```

Useful checks:

```bash
systemctl is-active kibot-manager kibot-executor-indodax kibot-executor-indodax
journalctl -u kibot-manager -n 100 --no-pager
journalctl -u kibot-executor-indodax -n 100 --no-pager
journalctl -u kibot-executor-indodax -n 100 --no-pager
```

## 10) Read-only access surfaces

These are the user-facing views:

- **Web dashboard**: realtime-only view of current state
- **Android app**: realtime-only view of current state
- **Telegram**: daily summary and urgent alerts only

They are observers. They should not be used to issue direct execution logic.

## 11) State and data files

Important persistent paths:

- `state/trade_log.jsonl`
- `state/cascade_mode.json`
- `state/open_positions.json`
- `state/daily_guard.json`
- `state/daily_summary.json`
- `state/orchestrator_state.json`
- `state/guardian_state.json`
- `state/analyst/`
- `state/scanners/`
- `state/events/`

Meaning:

- trade history is append-only
- open positions survive restart
- cascade mode survives restart
- daily guard preserves hard stop state
- analyst data stores summaries and failure journal
- orchestrator and guardian track system health

## 12) Node-specific service notes

### Batam

- central authority
- AI and maintenance heavy
- all support services live here
- Polymarket and Ollama are Batam-side

### EXECUTOR

- execution hot path only
- should remain small, deterministic, and fast
- no heavy AI or support bulkheads

### SCANNER

- scanner hot path only
- should stay lightweight and feed Batam
- scan output is upstream data, not direct strategy authority

## 13) Performance and guardrails

Keep the cluster fast by doing this:

- limit SG service memory
- keep AI reasoning on Batam
- keep dashboards read-only
- preserve hard stops and trailing stops
- never block execution on a slow UI surface
- keep Telegram spam low and actionable

## 14) Compatibility surfaces

Some extra services exist in the repo for compatibility, debugging, or legacy paths. They must not replace the diagram topology:

- `kibot-engine`
- `indodax-dashboard-proxy`
- `indodax-public-proxy`
- `kibot-local-scanner`
- `kibot-scanner@.service`
- `kicryp-manager`
- `kicryp-engine`

If these are used, they should be treated as support surfaces, not the primary three-node authority chain.

## 15) Summary

The intended KiBot shape is:

**Batam brain -> SCANNER scanner feed -> Batam decision -> EXECUTOR execution -> Batam reporting**

That is the diagram in words.
