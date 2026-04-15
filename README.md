# KiBot Trinity v7.0 (Dual-Bucket Engine)

KiBot Trinity is a high-performance, math-first trading architecture designed for Indodax and Binance markets. Version 7.0 introduces the **Dual-Bucket Trinity Architecture**, a strictly mathematical and consensus-driven system.

---

## 🏗️ Architecture

- **`kibot_manager.py` (The General)**:
  - **Cascade Loss Intelligence**: Dynamic risk states (`GROWTH` → `HARD_STOP`) scaling risk Exposure.
  - **Dual-Bucket Manager**: Strictly 50/50 split between Bucket A and Bucket B.
  - **What-If Engine**: 5 mathematical scenarios for entry validation.
  - **TradeLogger**: Memory-based trade history with Supabase sync.
- **Scanners (The Scouts)**:
  - **Kinance (Binance)**: UDP signal streamer for global lead-lag.
  - **KiCom (Crypto.com)**: REST-based scanner for global consensus confirmation.
  - **ConvictionScore**: 4-component mathematical engine (Vol, Breakout, Orderbook, Momentum).
- **`MacEngineDaemon.kt` (The Executor)**:
  - **Multi-Level Ladder Exit**: 30/30/20 profit-taking strategy.
  - **25% Single-Coin Cap**: Hard diversification guardrail.
  - **Volume Crash Detector**: Immediate exit on 70% volume drop.

---

## 📊 Capital Partitioning

1.  **Bucket A: Global Lead-Lag (50%)**:
    - Validated via Kinance + KiCom (AND-gate).
    - +1-3% profit targets using Ladder Exit.
    - Limit orders for fee optimization.
2.  **Bucket B: Local Indodax-Only (50%)**:
    - Based on Conviction Score >= 0.85.
    - +3-8% profit targets.
    - 40% local cash reserve maintained (60% spendable).
3.  **Survival First (Cascade)**:
    - Hard-stop if daily PnL drops below -2.0%.
    - Dynamic multipliers (Kelly) based on consecutive losses.

---

## 🛠️ Performance & Monitoring

- **Grafana Dashboard**: Real-time visualization of `trade_history` and `performance_snapshots`.
- **Telegram Notifications**: Strategic move alerts and 30-minute math reviews.
- **Supabase Persistence**: Automated synchronization for long-term intelligence gathering.

---

## 🚀 Quick Start

1.  Ensure `SUPABASE_URL` and `SUPABASE_ANON_KEY` are set.
2.  Run the manager: `python3 scripts/kibot_manager.py`
3.  Verify the engine: `./gradlew :apps:mac-engine:run`

---

*“Logic is the shield that protects capital from the volatility of emotion.”*
