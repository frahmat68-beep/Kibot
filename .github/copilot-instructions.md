# KiBot Trinity v6.2 - Developer Guardrails

As an AI coding assistant, follow these rules strictly to maintain the integrity of the KiBot Trinity system.

---

## 🏗️ Core Philosophy: Survival First

1.  **Logic over AI**: Prioritize deterministic math (Kelly sizing, RR ratio, fee calculations) over probabilistic AI inference. If a trade doesn't pass the `What-If Engine` simulation, it MUST NOT be executed.
2.  **Memory over Reaction**: Every trade must be logged via `TradeLogger`. Pair-specific performance (win rate, slippage) should inform future entry decisions.
3.  **Data Persistence**: All critical state changes must synchronize to Supabase. Local JSONL is the primary source of truth; Supabase is the long-term intelligence layer.

---

## 📊 Critical Thresholds & Guardrails

| Metric | Threshold | Action |
| :--- | :--- | :--- |
| **Max Daily Loss** | -2.0% | Hard-stop all trading until midnight WIB. |
| **Max Position Size**| 25.0% | Limit per-coin allocation to 25% of total equity. |
| **Capital Split** | 70 / 30 | 70% Stable (Rotation), 30% Aggressive (Anomaly). |
| **Min Profit Gap** | 0.8% | Minimum gain required after fees before allowing exit. |
| **Volume Anomaly** | >= 2.5x | Classification for "AGGRESSIVE" bucket eligibility. |
| **Slippage Cap** | 1.8% | Block entry if spread/slippage exceeds 1.8%. |

---

## 🛠️ Coding Standards

### Python (`kibot_manager.py`)
- Use asynchronous operations for logging and network requests.
- Ensure all technical indicators (Bollinger, RSI) are calculated with a minimum of 20 periods.
- Re-calculate daily PnL relative to the midnight WIB baseline.

### Kotlin (`MacEngineDaemon.kt`)
- Maintain strict 70/30 bucket isolation in `CapitalAllocationManager`.
- Use `ManagedPosition.bucketType` for all performance tracking and rebalancing.
- Log capital status every 5 minutes using the periodic timer.

---

## 💾 Database Schema (Supabase)

- **`trade_history`**: Record of every entry/exit with full metadata.
- **`pair_memory`**: Rolling average of slippage and win rate per pair.
- **`performance_snapshots`**: Records of bot health and PnL every 30 minutes.
- **`capital_allocation`**: Current status of STABLE vs AGGRESSIVE bucket utilization.

---

*“Verify every move. Assume the market is trying to steal your capital.”*
