# KiBot Trinity v7.5.2

Distributed trading system for a 3-node cluster:

- **Batam** = brain / control hub
- **SCANNER** = scanner / radar
- **EXECUTOR** = executor

![KiBot Diagram](KiBot_Diagram.png)

## What this repo contains

- `BLUEPRINT.md` — full architecture source of truth
- `KIBOT_RULES.md` — operator rules and safety policy
- `ROLE_MANIFEST.md` — **single-role enforcement specification (1 role per node)**
- `ARCHITECTURE_SINGLE_ROLE.md` — visual topology and service deployment matrix
- `ops/SERVERS.json` — SSH inventory for the live nodes
- `infra/systemd/` — service units for each node
- `scripts/` and `tools/` — deploy, audit, health, and recovery helpers

## Live topology

| Node | Role | Main services |
|---|---|---|
| Batam | Brain / Hub | `kibot-manager`, `ki-telegram-monitor`, `indodax-dashboard-proxy` (Web), `ollama`, `lazarus-ampere` |
| EXECUTOR | Executor | `kibot-executor-indodax` (Indodax), `kibot-polymarket` (Polymarket) |
| SCANNER | Scanner Node | `kibot-scanner@*` (20 sources incl. Indodax/Polymarket/Kraken), `ki-global-scanner-mesh` |

## Data flow

1. **SCANNER Scans**: 20 sources (17 global exchanges + Indodax + Polymarket + Kraken) produce signals.
2. **Batam Decides**: Manager merges signals with AI Veto (Ollama) and Risk Gates.
3. **EXECUTOR Executes**: Centralized execution for both Indodax and Polymarket.
4. **Trinity Dashboard**: Real-time monitoring via Web (Batam:8787) and Android app.

## Key features

- hard stop and trailing stop always on
- partial take-profit ladder
- daily loss protection
- duplicate/stale signal suppression
- scanner heartbeat and state mirroring
- Batam-side AI review and maintenance
- Telegram urgent alerts and daily summary

## Server access

Use the keys in `ops/SERVERS.json`:

```bash
ssh -i SSH_BATAM/ssh-key-batam-active.pem ubuntu@168.110.201.228
ssh -i SSH_SINGAPORE/SSH_SCANNER/ssh-key-2026-03-27.key ubuntu@152.69.218.198
ssh -i SSH_SINGAPORE/SSH_EXECUTOR/ssh-key-2026-03-22.key ubuntu@213.35.118.26
```

## Health checks

- `bash scripts/morning_check.sh` — live SSH audit for all nodes
- `bash tools/smoke_test_trinity.sh` — local smoke test; set `SSH_HOST`/`SSH_KEY` if you want it to query a remote node

## Notes

- Keep Batam as the only brain node.
- Keep EXECUTOR and SCANNER lean.
- If you change architecture, update `BLUEPRINT.md` first.
