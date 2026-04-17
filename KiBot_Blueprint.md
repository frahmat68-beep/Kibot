# 🧩 KiBot Trinity v7.2: The Autonomous Blueprint
> **Version**: 7.2 (Infrastructure Optimized)  
> **Philosophy**: "Math is the Law, Speed is the Edge."

---

## 📜 MANDATORY PROTOCOL: THE SURVIVAL RULE
**This document is the SINGLE SOURCE OF TRUTH for the KiBot system. Every AI Agent (Antigravity, Codex, Copilot, or others) WHO RECEIVES COMMANDS to modify this codebase MUST:**
1.  **UPDATE** this Blueprint immediately after any architectural or logic change.
2.  **DOCUMENT** the reasoning for the change (The "Why").
3.  **VALIDATE** that the change does not violate the 7 Pillars of Autonomy.
*Failing to update this document leads to system drift and is considered a critical operational failure.*

---

This document serves as the master technical blueprint for the KiBot Trinity autonomous system. It details the architecture, decision-making logic, and distributed intelligence across the Singapore and Tokyo nodes.

---

## 🏛️ 1. Distributed Topology (The Great Bridge)

KiBot Trinity is a **Distributed HFT Organism**. It separates observation from decision-making to minimize latency and maximize resource efficiency.

### **Node A: Singapore (SGP - The Brain)**
*   **Role**: Orchestration, AI Audit, Portfolio Management.
*   **Components**: `kibot_manager.py`, `kibot_ai_coordinator.py`, Guardian Cluster.
*   **Responsibility**: Deciding *if* and *when* to execute based on global health and AI consensus.

### **Node B: Tokyo (TYO - The Edge)**
*   **Role**: Scanning, Signal Broadcasting, Engine Execution.
*   **Components**: `kibot-local-scanner`, `kinance-engine`.
*   **Responsibility**: Monitoring order books and volume spikes at micro-second intervals.

**Connectivity**: Nodes communicate via **UDP Private Heartbeats**. SGP receives "Veto" or "Go" signals from TYO, ensuring the brain is always fed with fresh market data.

---

## 🛡️ 2. The 7 Pillars of Trinity Autonomy

Based on the v7.2 core audit, these seven pillars define the system's operational integrity:

### **Pillar 1: Maximum Performance Connectivity**
The system is optimized for **Autonomous HFT**. By removing heavy monitoring stacks (Grafana/Influx), we've reclaimed CPU cycles for the signal loop. Signals are processed in millisecond windows, putting KiBot ahead of retail traders.

### **Pillar 2: Impactful Sub-Systems**
Every sub-system (Analyst, Auditor, Security) is mission-critical.
*   **Guardian**: Monitors for stuck orders.
*   **Security**: Validates API key health and network integrity.
*   **Lazarus**: Automatically hunts for ARM/High-performance hardware within Oracle Cloud.

### **Pillar 3: Perfect Log Integration**
Logs are no longer just text; they are a **Diagnostic Ecosystem**. Every heartbeat is tracked. If TYO loses connectivity, SGP immediately enters "Safe Mode" and stops all new entries until communication is restored.

### **Pillar 4: Active Decision Intelligence**
The system is "PnL-Aware":
*   **Profit Lock**: Hard-locks gains when targets are reached.
*   **Trailing Stop**: Dynamic exit that follows price rallies to squeeze maximum profit.
*   **Rotation**: Automatically closes stagnant positions (>4h hold, ~4% profit) to pivot capital into newer, higher-confidence signals.
*   **Daily Reporting**: Automated sub-system that broadcasts a comprehensive PnL and Learning overview to Telegram every midnight (00:00 WIB). It tracks daily/weekly growth and the number of losses prevented by the AI (Gate Blocks and What-If Skips), providing a clear mathematical ledger of autonomous performance.

### **Pillar 5: Mathematical Scanning (Confidence 0.85)**
Scanning is a consensus of:
*   **Chart**: Price action and Bollinger Band squeeze.
*   **Volume**: Genuine buying pressure vs. wash trading.
*   **Order Book**: Depth imbalance (Bid > Ask ratio).
*   **Score**: Only signals with a **Conviction Score ≥ 0.85** reach the engine (Threshold reduced to **0.55** for speculative Bucket B).
*   **Global Whiteboard (Consensus Engine)**: A real-time data terminal where Binance, Crypto.com, and CoinGecko "cross-fire" their data. 
    *   **Price Consensus**: Mandates an AND-gate check between Binance and Crypto.com price action to kill "fake pumps" (spread > 1.5% leads to instant rejection).
    *   **Sentiment Consensus**: Integrates CoinGecko Trending Data to apply a **+15% Confidence Bonus** to popular assets, ensuring the bot follows genuine market momentum.

### **Pillar 6: AI-Residency (NVIDIA/Gemini Audit)**
AI is used as a **Strategic Auditor**. 
*   **News Trawler**: AI scans news/social sentiment for holdings.
*   **Problem Solver**: AI analyzes log errors and proposes logic updates.
*   **Confidence Booster**: AI cross-references technical signals with global crypto trends.

### **Pillar 7: The Learning Loop (Experience-Based AI)**
The Brain learns via **Deterministic Experience**:
*   **Blacklist Logic (The Cooldown)**: Every "Hard Stop" or loss triggers an automatic cooling-off period (30-120 minutes) for that specific pair to prevent "revenge trading" or repetitive losses on failing assets.
*   **Dynamic Kelly Scaling**: The system adjusts position sizes based on historical win rates. High-performing pairs earn larger capital allocations, while underperformers are restricted.
*   **What-If Simulation**: Every entry is simulated locally before deployment. If the math doesn't "make sense" (spread > potential gain), the AI blocks the trade, recording it as a **"What-If Skip"** in the performance report.
*   **Periodic Math Review (30m Cycle)**: Every 30 minutes, the manager conducts a self-audit of all open and closed positions, adjusting the global "Risk Regime" (GROWTH, CAUTION, DEFENSIVE) based on recent PnL trajectory.

---

## ⚙️ 3. Optimization & Hardening Protocols

### **1GB RAM Optimization**
- **Resident Daemons**: AI Coordinator stays in memory to avoid "warm-up" spikes.
- **Purge Policy**: Zero-storage policy for non-critical monitoring tools.
- **Resource High-Watermark**: 160MB hard limit per service to avoid OOM crashes.

### **Revive Mode (Auto-Resurrection)**
Every service is managed by `systemd` with `Restart=always`.
- **Status Check**: Real-time PID monitoring.
- **Reaction Time**: < 10 seconds for service recovery after any crash.

### **Oracle Keep-Alive**
- **Mechanism**: `stress-ng` load-cycling.
- **Goal**: Prevents Oracle Free Tier from reclaiming "idle" instances. 0.3% average impact for 24/7 uptime.

---

## 📈 4. Capital Strategy (The 50/50 Bucket)

Capital agility is handled via two distinct pools:
1.  **Lead-Lag Bucket (50%)**: Exploits latency between Binance and Indodax.
2.  **Local Conviction Bucket (50%)**: Scalps high-volatility pairs using the Conviction Score.

---
*Authorized by Antigravity AI for KiBot Trinity v7.2 Deployment.*
