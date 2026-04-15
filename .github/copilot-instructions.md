# KiBot Trinity v7.0 — Dual-Bucket Core Instructions

You are the AI co-pilot for KiBot, an autonomous trading system optimized for Indodax. Phase 7.0 introduces the **Dual-Bucket Trinity Architecture** with a strictly mathematical focus.

## 1. Architecture: Dual-Bucket System (50/50 Split)
All trading decisions MUST belong to one of these two buckets:

### Bucket A: Global Lead-Lag (50% Allocation)
- **Goal**: Capture 1-3% profits using confirmations from global markets.
- **AND-Gate Logic**: BOTH a Kinance (Binance) signal AND a KiCom (Crypto.com) confirmation are REQUIRED.
- **Execution**: LIMIT orders only. Max 3 concurrent positions.
- **Stop Loss**: -1.5% hard stop or 2% trailing.

### Bucket B: Local Indodax-Only (50% Allocation)
- **Goal**: Capture 3-8% profits on local IDR pairs.
- **Conviction Score**: Pure mathematical score (0.0 - 1.0) derived from Volume Spike, Breakout Velocity, Orderbook Depth, and Momentum.
- **Entry Threshold**: Score >= 0.85 REQUIRED.
- **Execution**: 20% cash reserve strictly maintained. Max 2 concurrent positions.
- **Stop Loss**: -3% hard stop or 5% trailing.

## 2. Risk Intelligence: Cascade Loss System
The system MUST dynamically scale risk based on PnL history:
- **State Machine**: `GROWTH` → `CAUTION` → `DEFENSIVE` → `RESTRICTED` → `HARD_STOP`.
- **Kelly Multiplier**: Scales from 1.0 (Growth) down to 0.0 (Hard Stop).
- **Circuit Breaker**: Daily PnL <= -2.0% triggers `HARD_STOP` (blocks all new entries).
- **Bucket B Restriction**: `DEFENSIVE` mode and lower disables Bucket B entries entirely.

## 3. Exit Strategy: Multi-Level Ladder
- **Ladder Targets**: Partial profit taking at +3% (30%), +6% (30%), +10% (20%), +15% (all remaining).
- **Volume Crash Detector**: If 15m volume drops >70% below average while in profit, exit immediately.
- **Post-Mortem**: Every loss MUST be classified: `TIMING`, `PEAK_ENTRY`, or `STOP_LOSS` for learning.

## 4. Coding Standards (Trinity v7.0)
- **Mathematical Determinism**: Logic MUST be 100% deterministic based on `kibot_engine_v2.py`.
- **Zero Placeholder Policy**: Never use `TODO` or placeholders. Implement complete logic immediately.
- **Logging**: All trades MUST be logged to `STATE_DIR/trade_log.jsonl` and synced to Supabase.
- **Safety Guards**: Always enforce the 20% global cash reserve in `CapitalAllocationManager`.

## 5. Technical Stack
- **Engine**: Python 3.14 (kibot_manager.py, kibot_engine_v2.py).
- **Core**: Kotlin KMP (MacEngineDaemon, KiComScanner).
- **Database**: Supabase (trade_history, pair_memory, performance_snapshots).
- **Protocol**: UDP for low-latency signal transit between scanners and manager.

## 6. Mathematical Formulas to Enforce
- **Conviction Score**: `(0.3*Vol_Spike) + (0.25*Breakout) + (0.25*Orderbook) + (0.2*Momentum)`.
- **Expected Value (EV)**: `(WR * Avg_Win) - ((1-WR) * Avg_Loss) - Fees`.
- **Position Size**: `Half-Kelly` multiplier scaled by `Cascade Multiplier`.

Follow these rules strictly. Any deviation from the Dual-Bucket logic or mathematical guardrails is a critical failure.
