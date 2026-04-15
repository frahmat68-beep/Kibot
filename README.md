# KiBot Trinity v6.2 (Survival First)

KiBot Trinity is a high-performance, math-first trading architecture designed for Indodax and Binance markets. Version 6.2 ("Trinity") transitions the system from AI-inference-reliance to a deterministic, memory-capable execution engine.

---

## 🏗️ Architecture

- **`kibot_manager.py` (Brain)**:
  - **TradeLogger**: Persistent memory of wins, losses, and real-time Learning Engine updates.
  - **What-If Engine**: Pre-trade mathematical simulation (Kelly, RR, EV) acting as a Veto Gate.
  - **Universal Vision**: Real-time technical scanner for all 200+ Indodax assets (not just whitelists).
  - **Depth Vision**: Order Book imbalance detection (Bid/Ask ratio) to filter pump-fakeouts.
  - **Pump Reversal Guard**: Automatic Volume Collapse and Peak zone profit locking.
  - **30-min Math Review**: Automated performance tracking and threshold adaptation.
- **`MacEngineDaemon.kt` (Executor)**:
  - **70/30 Capital Split**: Automated STABLE (70%) and AGGRESSIVE (30%) bucket management.
  - **25% Single-Coin Cap**: Hard diversification guardrail.
  - **Lead-Lag Radar**: Real-time signal execution from Kinance (Binance) UDP stream.

---

## 📊 Core Strategies

1.  **Stable Rotation (70%)**:
    - Low-volatility pairs with tight spread.
    - 1.8% profit targets.
    - Limit order execution (maker fee optimization).
2.  **Aggressive Anomalies (30%)**:
    - High-volume pumps (>= 2.5x volume spikes).
    - 3.5% - 5.0% profit targets.
    - Market order execution (speed priority).
3.  **Survival First Mode**:
    - Auto-reset daily baseline at midnight WIB.
    - Hard-stop if daily PnL drops below -2.0%.
    - Dynamic entry thresholds (standard increases if daily PnL < 0).

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
