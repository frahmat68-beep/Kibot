# KiBot Blueprint v7.3.1 - Trinity Autonomous System

## Canonical Architecture Note
- Detailed, always-updated two-server runtime documentation is maintained at:
  - `docs/architecture/TWO_SERVER_SYSTEM_GUIDE.md`
- If any statement in this blueprint conflicts with live architecture behavior, follow `TWO_SERVER_SYSTEM_GUIDE.md`.

## 1. Vision & Mindset
**Philosophy**: "Low Profile, High Profit".
**Goal**: Completely autonomous trading with zero manual intervention unless critical.
**Profit Orientation**: "Green PnL" mindset. Bayesian learning loop prioritizes capital preservation and statistical probability.

## 2. Infrastructure & Distributed Logic
**Nodes**: 1GB Oracle VPS (Optimized for low RAM).
**Keep-Alive**: `stress-ng` active to prevent VPS suspension. `systemd` handles auto-recovery.
**Live Node Identity**:
- `Node A` = primary runtime node (`kidax-engine` + `kibot-*` services).
- `Node B` = market radar node (`kinance-engine` + `kibot-*` services).
- Physical placement may change; config and service identity must stay aligned with logical node responsibility, not legacy location labels.

| **Bucket A** | Lead-Lag (Arb/Breakout) | Global Market Alpha | 50% |
| **Bucket B** | Local Math (Anomaly/Scanners) | Indodax Local Alpha | 50% |

## 4. SSH Operational Manual (Catatan v7.3.1)
**Node A (Indodax Engine & Manager)**
- **IP**: `213.35.118.26`
- **Key**: `SSH_MANAGEMENT/ssh-key-2026-03-22.key`
- **Command**: `ssh -i SSH_MANAGEMENT/ssh-key-2026-03-22.key ubuntu@213.35.118.26`

**Node B (Binance Radar)**
- **IP**: `152.69.218.198`
- **Key**: `SSH_SCANNER/ssh-key-2026-03-27.key`
- **Command**: `ssh -i SSH_SCANNER/ssh-key-2026-03-27.key ubuntu@152.69.218.198`

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
