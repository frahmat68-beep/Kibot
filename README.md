# KiBot Trinity v6.2.5 (Global Consensus)

KiBot Trinity is a high-performance, math-first trading architecture designed for Indodax and Binance markets. Version 6.2.5 introduces the **Global Consensus (Whiteboard)** system, requiring cross-exchange validation before trade execution.

---

## 🏗️ Architecture

- **`kibot_manager.py` (The General)**:
  - **Global Whiteboard (Papan Tulis)**: In-memory real-time state tracking prices from Binance and Crypto.com.
  - **Veto Gate**: Automatically rejects pumps that aren't correlated across multiple global exchanges (Max 1.5% spread).
  - **What-If Engine**: Pre-trade mathematical simulation (Kelly, RR, EV).
  - **Universal Vision**: Real-time technical scanner for all 200+ Indodax assets.
  - **Depth Vision**: Order Book imbalance detection (Bid/Ask ratio).
- **Radars (The Scouts)**:
  - **Kinance (Binance)**: UDP signal streamer for global alpha.
  - **KiCryp (Crypto.com)**: New real-time WebSocket price streamer for the Whiteboard.
- **`MacEngineDaemon.kt` (The Executor)**:
  - **50/50 Capital Split**: Balanced STABLE (Local Sniper) and AGGRESSIVE (Global Alpha) buckets.
  - **25% Single-Coin Cap**: Hard diversification guardrail.
  - **Dynamic Anomaly Detection**: 2.5x volume spike detection.

---

## 📊 Capital Partitioning

1.  **Local Sniper (50% - STABLE)**:
    - Pure Technical probes on Indodax-only pairs or lagging market moves.
    - 1.8% profit targets.
    - Maker/Limit order priority.
2.  **Global Alpha (50% - AGGRESSIVE)**:
    - Signals validated via **Global Consensus** (Binance + Crypto.com sync).
    - 3.5% - 5.0% profit targets.
    - Taker/Market order speed priority.
3.  **Survival First Mode**:
    - Hard-stop if daily PnL drops below -2.0%.
    - Dynamic entry thresholds based on daily performance.

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
