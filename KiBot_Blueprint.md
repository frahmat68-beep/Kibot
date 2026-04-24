# KiBot Blueprint v7.3.1 - Trinity Autonomous System

## Canonical Architecture Note
- Detailed runtime documentation is maintained at:
  - `docs/architecture/TWO_SERVER_SYSTEM_GUIDE.md`
- Current production overlay now consists of 3 nodes:
  - `Node A / SG` = executor + control plane
  - `Node B / Tokyo` = radar + scanner mesh
  - `Node C / Batam` = AI brain hub (`Ollama` + gateway)
- If any statement in this blueprint conflicts with live runtime or `logs/OPS_UPDATE_LOG.md`, follow the latest ops log.

## 1. Vision & Mindset
**Philosophy**: "Low Profile, High Profit".
**Goal**: Completely autonomous trading with zero manual intervention unless critical.
**Profit Orientation**: "Green PnL" mindset. Bayesian learning loop prioritizes capital preservation and statistical probability.

## 2. Infrastructure & Distributed Logic
**Nodes**:
- `Node A / SG`: 1GB Oracle VPS executor/control-plane.
- `Node B / Tokyo`: 1GB Oracle VPS radar/scanner.
- `Node C / Batam`: 4 OCPU / 24 GB / 190 GB Oracle Ampere AI brain node.
**Keep-Alive**: `stress-ng` active to prevent VPS suspension. `systemd` handles auto-recovery.
**Live Node Identity**:
- `Node A` = primary runtime node (`kidax-engine` + `kibot-*` services).
- `Node B` = market radar node (`kinance-engine` + `kibot-*` services).
- `Node C` = AI reasoning node (`ollama` + `kibot-ollama-gateway`).
- Physical placement may change; config and service identity must stay aligned with logical node responsibility, not legacy location labels.

| **Bucket A** | Lead-Lag (Arb/Breakout) | Global Market Alpha | 50% |
| **Bucket B** | Local Math (Anomaly/Scanners) | Indodax Local Alpha | 50% |

## 4. SSH Operational Manual (Catatan v7.3.1)
**Node A (Indodax Engine & Manager / SG)**
- **IP**: `213.35.118.26`
- **Key**: `SSH_SINGAPORE/SSH_SG1/ssh-key-2026-03-22.key`
- **Command**: `ssh -i SSH_SINGAPORE/SSH_SG1/ssh-key-2026-03-22.key ubuntu@213.35.118.26`

**Node B (Binance Radar / Tokyo)**
- **IP**: `152.69.218.198`
- **Key**: `SSH_SINGAPORE/SSH_SG2/ssh-key-2026-03-27.key`
- **Command**: `ssh -i SSH_SINGAPORE/SSH_SG2/ssh-key-2026-03-27.key ubuntu@152.69.218.198`

**Node C (AI Brain Hub / Batam)**
- **IP**: `168.110.201.228`
- **Key**: `SSH_BATAM/ssh-key-batam-active.pem`
- **Command**: `ssh -i SSH_BATAM/ssh-key-batam-active.pem ubuntu@168.110.201.228`
- **Role**:
  - menjalankan `ollama`
  - menyimpan model `qwen3:4b` untuk advisory live dan `qwen3:8b` untuk review berat
  - melayani SG dan Tokyo via gateway lokal + tunnel SSH persisten

**Quick Debug Commands**:
- Monitor Log: `journalctl -u kidax-engine -f`
- Cek Service: `systemctl list-units --type=service | grep kibot`
- Cek Port UDP: `netstat -ulnp | grep 999`

## 4. Operation Protocol
### Silent Review Loop
- 30-minute Math Reviews are **SILENT**.
- Telegram alerts ONLY trigger on: `PREPARE_STOP`, `DEFENSIVE`, `HARD_STOP`.
- Daily Performance Report at **00:00 WIB**.

### Rotation Engine (Fixed v7.3.1)
- Resolves "Ghost Position" bug.
- Any memory position removal **MUST** broadcast a `SMART_EXIT` to the exchange first.

- Prioritizes pairs with proven legitimacy scores > 62.

## 6. Technical Spec Hardening (v7.3.1)
- **Order Type**: Force **LIMIT** (Maker) to ensure minimal fees.
- **Partial TP**: Trigger @ **1.2%** (Profit safety net).
- **Micro-cap Trailing**: **7%** for coins priced `< 50 IDR`.
- **Stagnancy Penalty**: **2 hours** idle time allowed before auto-rotation.
- **Shadow Mode**: **DISABLED** (All systems are LIVE).

---
*Blueprint Version: v7.3.1*
*Status: Verified & Deployed*
