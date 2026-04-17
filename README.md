# KiBot: Trinity v7.1 Autonomous HFT System

Autonomous, self-healing High-Frequency Trading (HFT) infrastructure build with a Math-First Core.

## 🚀 Trinity v7.1 Upgrade Summary (Math-First)

Trinity v7.1 shifts the decision-making authority from AI-heavy logic to a deterministic **Math-First Core**. AI has been moved to an advisory layer (Watchdogs/News), ensuring the system remains responsive and profitable even when AI latency occurs or API quotas are exhausted.

### 🛡️ Core Infrastructure & Watchdogs
1.  **News Watchdog (5m cycle)**: Monitored held coins for critical sentiment shifts. Automated emergency sell if bearish consensus is detected by the AI Legion.
2.  **PnL Audit Watchdog (10m cycle)**:
    -   **Daily Hard Stop**: Enforces a total equity draw-down limit (default -2.5%). If hit, the system suspends all entries until midnight WIB.
    -   **Coin Rotation**: Identifies "Dead Weight" positions (stagnant for >30 minutes) and sells them at market to free up capital for high-conviction signals.
3.  **Log Maintenance (6h cycle)**: Aggressively manages storage on Oracle Micro (50GB limit).
    -   Auto-purges logs older than 3 days.
    -   Triggers emergency cleanup if disk usage > 80%.

### 📈 Trading Logic: Math-First Core
-   **Conviction Score (Deterministic)**: Entry signals are scored based on raw orderbook depth, breakout momentum, and volume spike intensity *before* AI is consulted.
-   **AI Advisory (Fallback Layer)**: AI serves to *boost* conviction or provide "What-If" simulations. If AI APIs fail, the Math Core continues to operate based on calculated Risk-Reward ratios.
-   **Trailing Stop Profit (TSP)**: Implements dynamic trailing stops based on the "Pump Phase" (Early, Mid, Late) of the coin.

---

## 🏗️ System Components

### 1. Unified Manager (`scripts/kibot_manager.py`)
The central nervous system. Manages state, coordinates UDP communication between sub-systems, and hosts the watchdog loops.

### 2. Trading Engine (`scripts/kibot_engine_v2.py`)
Handles execution logic, portfolio tracking, and mathematical conviction scoring.

### 3. AI Legion (`scripts/kibot_ai_coordinator.py`)
A load-balanced, failover-ready pool of LLM providers (Groq, Gemini, OpenRouter, Cohere, Nvidia NIM). Used for:
-   News Sentiment Analysis.
-   Multi-agent consensus on discovery.
-   What-if scenario analysis.

### 4. Audit & Discovery (`scripts/audit_trading_30m_ai.py`)
Runs every 30 minutes to analyze trade logs and suggest policy tweaks to the Math Core.

---

## 🛠️ Automated Maintenance & Health

| Feature | Interval | Action |
| :--- | :--- | :--- |
| **PnL Check** | 10m | Check -2.5% stop; rotate stagnant coins. |
| **News Scan** | 5m | Check news for held positions; exit on panic. |
| **Log Cleanup** | 6h | Delete old logs; maintain <80% disk usage. |
| **Math Review** | 30m | Analyze profit factor and EV per trade. |

---

## 📋 Initialization & Deployment

1.  **Environment Setup**: Ensure `.env` contains all necessary API keys (Supabase, Telegram, AI Providers).
2.  **Validation**: Run the smoke test suite:
    ```bash
    ./scripts/smoke_test_trinity.sh
    ```
3.  **Launch**:
    ```bash
    python3 scripts/kibot_manager.py
    ```

---

*Note: Trinity v7.1 is optimized for Oracle 1GB/1OCPU Micro instances. Avoid heavy Docker usage; use native python processes to minimize CPU wakeups.*
