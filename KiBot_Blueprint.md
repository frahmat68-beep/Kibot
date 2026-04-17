# KiBot Blueprint v7.3.1 - Trinity Autonomous System

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

## 3. Capital Allocation (50/50 Split)
| Bucket | Strategy | Target | Capital Ratio |
| :--- | :--- | :--- | :--- |
| **Bucket A** | Lead-Lag (Arb/Breakout) | Global Market Alpha | 50% |
| **Bucket B** | Local Math (Anomaly/Scanners) | Indodax Local Alpha | 50% |

## 4. Operation Protocol
### Silent Review Loop
- 30-minute Math Reviews are **SILENT**.
- Telegram alerts ONLY trigger on: `PREPARE_STOP`, `DEFENSIVE`, `HARD_STOP`.
- Daily Performance Report at **00:00 WIB**.

### Rotation Engine (Fixed v7.3.1)
- Resolves "Ghost Position" bug.
- Any memory position removal **MUST** broadcast a `SMART_EXIT` to the exchange first.

## 5. Bayesian Learning Loop
- Treatment of "What-If" Skips as negative data points.
- Gate Blocks as performance optimization metrics.
- Prioritizes pairs with proven legitimacy scores > 62.

---
*Blueprint Version: v7.3.1*
*Status: Verified & Deployed*
