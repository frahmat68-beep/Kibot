# 🤖 KiBot Trinity: The Autonomous HFT Infrastructure
> **Multi-Chain Intelligence | Math-First Execution | Self-Healing Watchdogs**

KiBot Trinity is a next-generation autonomous trading system designed to run on ultra-low resource environments (Oracle Free Tier) while maintaining institutional-grade risk management and high-frequency execution.

---

## 🏗️ Version History: Evolution to v7.1

### [v7.1] The Math-First Revolution (Current)
Moving from AI-dependent decision-making to a **Mathematical Conviction Core**.
- **Deterministic Entries**: Removed AI Veto from the critical path. Signals are processed purely via 7-layer math filters (Bollinger, RSI, Volume Spike, Orderbook Depth).
- **Watchdog Sub-systems**: Automated background threads for News, PnL, and Log Maintenance.
- **AI Legion v2.0**: Expanded fallback chain (7 providers) for discovery and advisory tasks only.

### [v7.0] The Agentic Foundation
Introduction of independent background agents ("Team IT") and the Dual Bucket strategy.
- **Philosophy**: "Redundancy as Weapons" - Every component monitors another.
- **Goal**: Cumulative growth with minimal human oversight.

---

## 📈 Trading Logic: Trinity Consensus

### 1. Bucket A: Global Lead-Lag (Arbitrage)
Real-time signal synchronization between global exchanges to detect local price movements before they happen.
- **Exchanges**: Kinance (Binance) + KiCom (Crypto.com) + KiDax (Indodax).
- **Consensus**: Both global sources must agree on price direction (AND gate).
- **Target**: +1-3% per trade | **Stop**: -1.5%.

### 2. Bucket B: Local Conviction (Scalping)
Pure technical scalping on Indodax-only pairs using high-conviction mathematical models.
- **Filters**: 7-layer technical stack (Volume, Momentum, BB, RSI, Cluster, etc).
- **Conviction Score**: Must be ≥ 0.85 for market entry.
- **Target**: +3-8% per trade | **Stop**: -3%.

### 3. Volatility Guard
Automatically slashes Kelly-sizing if market volatility exceeds safety thresholds to prevent "Buying the Top."

---

## 🛡️ Self-Healing Sub-Systems (Watchdogs)
v7.1 reinforces the infrastructure with specialized background guards:

| Sub-System | Loop | Role |
| :--- | :--- | :--- |
| **News Watchdog** | 5m | Scans held coins for critical "Bearish" news & sentiment via AI Legion. |
| **PnL Watchdog** | 10m | Audits Daily PnL. Enforces strong **-2.5% Daily Hard Stop**. |
| **Coin Rotation** | 10m | Automatically exits stagnant positions (held >30m, <0.2% move) to free capital. |
| **Log Maintenance** | 6h | (New) Automatically purges logs older than 3 days to protect 50GB storage. |

---

## ✅ Operational Readiness Checklist (30s Check)
Ensure your Trinity Node is running optimally:

1.  **Thread Health**: Run `grep "started" logs/kibot_manager.log` and verify all 12 threads initiated.
2.  **AI Connectivity**: Run `python3 scripts/verify_integration.py` to confirm Nvidia/Gemini/Groq keys.
3.  **Storage Guard**: Check `df -h /` and ensure usage is < 80%.
4.  **Telegram**: Verify receiving the `[STARTUP]` summary message.

---

## 🤖 The "Team IT" Agent Legion
The system uses a fallback chain of AI providers for non-trading decision support (Discovery, News, Post-mortems):
1. **Groq** (Instant) | 2. **Gemini** (High-Fidelity) | 3. **OpenRouter** (Redundancy) | 4. **Cohere** | 5. **Jina** | 6. **Nvidia NIM** | 7. **Huggingface**

---

## ⚙️ Operational Commands & Files

### Core Files
- `scripts/kibot_manager.py`: The "Brain". Handles UDP signals, Watchdogs, and AI routing.
- `scripts/kibot_engine_v2.py`: The "Engine". Mathematical consensus and trade execution.
- `scripts/kibot_ai_coordinator.py`: The "Coordinator". Manages API quotas and provider fallback.

### Service Management
```bash
# Check status of the Agent Cluster
sudo systemctl status kibot-*

# Global Restart (Trinity Pulse)
sudo systemctl restart kibot-manager kibot-engine
```

### Monitoring
- **Web Dashboard**: `http://[SERVER_IP]:8787/dashboard`
- **Telegram**: Noise-filtered reports (@Alarms, @Summary, @Critical).

---

## 🌍 Global Infrastructure
- **Indodax Node**: `213.35.118.26` (Oracle Singapore)
- **Binance Node**: `152.69.218.198` (Oracle Tokyo)
- **Database**: Supabase (Cloud Sync / vptlelbgyxwieyfdpuja)

---
*KiBot Trinity v7.1 - Math is the law. AI is the advisor.*
