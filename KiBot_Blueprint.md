# KiBot Blueprint - Trinity Autonomous System

## Canonical Architecture Note
- Detailed, always-updated runtime documentation is maintained at:
  - `docs/architecture/KIBOT_SOVEREIGN_BRAIN.md`
  - `docs/architecture/three-node-topology.md`
- If any statement in this blueprint conflicts with live architecture behavior, follow the live architecture docs above.

## 1. Vision & Mindset
**Philosophy**: "Low Profile, High Profit".
**Goal**: One sovereign agentic framework named `KiBot` with multiple AI models and subsystem support, operating autonomously unless critical intervention is required.
**Profit Orientation**: "Green PnL" mindset. Bayesian learning loop prioritizes capital preservation, statistical probability, and adaptive capital posture.

## 2. Infrastructure & Distributed Logic
**Nodes**: three-node runtime with placement optimized by role, not legacy geography.
**Keep-Alive**: `systemd` handles auto-recovery. Any keep-alive service must be justified by production need, not habit.
**Live Node Identity**:
- `SG1` = executor / Indodax control plane
- `SG2` = radar / scanner / orchestration plane
- `Batam` = sovereign brain / Ollama / Polymarket / research plane
- Physical placement may change; config and service identity must stay aligned with logical node responsibility.

| **Bucket A** | Lead-Lag (Arb/Breakout) | Global Market Alpha | 50% |
| **Bucket B** | Local Math (Anomaly/Scanners) | Indodax Local Alpha | 50% |

## 4. SSH Operational Manual
**SG1 (Executor / Indodax)**
- **IP**: `213.35.118.26`
- **User**: `ubuntu`
- **Key**: `SSH_SINGAPORE/SSH_SG1/ssh-key-2026-03-22.key`
- **Command**: `ssh -i SSH_SINGAPORE/SSH_SG1/ssh-key-2026-03-22.key ubuntu@213.35.118.26`

**SG2 (Radar / Scanner)**
- **IP**: `152.69.218.198`
- **User**: `ubuntu`
- **Key**: `SSH_SINGAPORE/SSH_SG2/ssh-key-2026-03-27.key`
- **Command**: `ssh -i SSH_SINGAPORE/SSH_SG2/ssh-key-2026-03-27.key ubuntu@152.69.218.198`

**Batam (AI Brain & Fallback)**
- **IP**: `168.110.201.228`
- **User**: `ubuntu`
- **Key**: `SSH_BATAM/ssh-key-batam-active.pem`
- **Command**: `ssh -i SSH_BATAM/ssh-key-batam-active.pem ubuntu@168.110.201.228`

**Quick Debug Commands**:
- Monitor Log: `journalctl -u kidax-engine -f`
- Cek Service: `systemctl list-units --type=service | grep kibot`
- Cek Port UDP: `netstat -ulnp | grep 999`

## 4. Operation Protocol
### Silent Review Loop
- 30-minute Math Reviews are **SILENT**.
- Telegram alerts ONLY trigger on: `PREPARE_STOP`, `DEFENSIVE`, `HARD_STOP`.
- Daily Performance Report at **00:00 WIB**.

### Rotation Engine
- Any memory position removal **MUST** broadcast a `SMART_EXIT` to the exchange first.
- Prioritize pairs with proven legitimacy scores > 62.

## 5. Technical Spec Hardening
- **Order Type**: Force **LIMIT** (Maker) to ensure minimal fees.
- **Partial TP**: Trigger @ **1.2%** (Profit safety net).
- **Micro-cap Trailing**: **7%** for coins priced `< 50 IDR`.
- **Stagnancy Penalty**: **2 hours** idle time allowed before auto-rotation.
- **Shadow Mode**: **DISABLED** (All systems are LIVE).

---
*Blueprint Status: current*
*Status: aligned with live architecture docs*
