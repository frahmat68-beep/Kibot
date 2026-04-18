# 🤖 KiBot Trinity: The Autonomous HFT Infrastructure
> **Multi-Chain Intelligence | Math-First Execution | 1GB VPS Optimized**

## Start Here (Mandatory)

For current production topology and runtime behavior, read this first:

- `docs/architecture/TWO_SERVER_SYSTEM_GUIDE.md`

This file is the source of truth for:
- two-server architecture,
- active services on each node,
- end-to-end runtime flow,
- known failure modes and root causes,
- post-deploy validation procedure.

KiBot Trinity is a next-generation autonomous trading system designed to run on ultra-low resource environments (Oracle Micro 1GB RAM) while maintaining institutional-grade risk management and high-frequency execution.

---

## 🏗️ Version History: Evolution of Trinity

### [v7.2] The Efficiency & Rotation Upgrade (Current)
Optimizing for performance and capital agility on 1GB RAM / 1 OCPU VPS.
- **Capital Rotation Engine**: 5-gate safety check to rotate stagnant positions (>4h hold) into higher-confidence signals.
- **50/50 Capital Split**: Automated budget allocation between **Bucket A** (Lead-Lag pairs) and **Bucket B** (Local/Technical pairs).
- **CPU Optimization**: Purged Grafana/InfluxDB/Telegraf. Converted AI Coordinator into a **Resident Daemon** to eliminate process-restart spikes.
- **V7.2 API Integration**: Added NVIDIA NIM for higher-fidelity infrastructure auditing.

### [v7.1] The Math-First Revolution
Moving from AI-dependent decision-making to a **Mathematical Conviction Core**.
- **Deterministic Entries**: 7-layer math filters (Bollinger, RSI, Volume Spike, Orderbook Depth).
- **Watchdog Sub-systems**: Automated background threads for News, PnL, and Log Maintenance.
- **AI Legion v2.0**: Expanded fallback chain (7 providers) for discovery and advisory tasks only.

### [v7.0] The Agentic Foundation
Introduction of independent background agents ("Team IT") and the Dual Bucket strategy.
- **Philosophy**: "Redundancy as Weapons" - Every component monitors another.
- **Goal**: Cumulative growth with minimal human oversight.

---

## 📈 Trading Logic: Trinity Consensus

The system enforces a **50/50 Capital Allocation** strategy to balance risk between global trends and local volatility.

### 1. Bucket A: Global Lead-Lag (50% Allocation)
Real-time signal synchronization between global exchanges to detect local price movements before they happen.
- **Exchanges**: Kinance (Binance) + KiCom (Crypto.com) + KiDax (Indodax).
- **Pairs**: Category A (BTC, ETH, SOL, etc.) + Category B (Futures Proxy).
- **Target**: +1-3% per trade | **Stop**: -1.5%.

### 2. Bucket B: Local Conviction / Math (50% Allocation)
Pure technical scalping on Indodax-only pairs using the `ConvictionScoreCalculator`.
- **Pairs**: Category C (Pippin, Myx, Aster, etc.).
- **Entrance**: Conviction Score ≥ 0.85 (Volume, BB, OB Imbalance, RSI).
- **Control**: 60% deployment cap per bucket to maintain liquidity for rotations.

---

## 🔄 Capital Rotation Engine (5-Gate Safety)

To prevent "bag-holding" stagnant positions, v7.2 introduces a rotation logic whenever a new high-confidence signal arrives while at max capacity:

1. **Fee Safety**: Active position must have >0.5% profit to cover round-trip fees.
2. **Opportunity Gain**: New signal must be >15% higher in confidence than active.
3. **Stagnancy Penalty**: Positions held >4 hours receive a rising rotation score.
4. **Opportunity Cost**: Evaluation of "Greed vs. Agility" based on total portfolio health.
5. **Conviction Score**: Consensus from Auditor/Analyst on rotation risk.

---

## 🛡️ Self-Healing Sub-Systems (Trinity Guardians)

V7.2 reinforces the infrastructure with specialized background guards in "Revive Mode" (Auto-Restart Always):

| Sub-System | Loop | Role | Optimization (v7.2) |
| :--- | :--- | :--- | :--- |
| **AI Coordinator** | 1m | AI Rate-Limit Hub | **Resident Daemon** (60s loop) - 95% CPU reduction. |
| **Rotation Engine** | 5m | Capital Agility | Evaluates every 5m or on new high-conf signals. |
| **PnL Watchdog** | 10m | Daily PnL Audit | Enforces strong **-2.5% Daily Hard Stop**. |
| **Log Guardian** | 6h | Storage Protection | Auto-purges logs older than 3 days. |
| **Oracle Keep-Alive**| 20s | VPS Persistence | Prevents idle-suspension of Oracle Free Tier. |
| **Lazarus Hunter**	| 40s | Resource Invasion | Automatically hunts for OCI Ampere (ARM) capacity. |

---

## ✅ Operational Readiness Checklist

1. **Thread Health**: Run `grep "started" logs/kibot_manager.log` and verify all threads initiated.
2. **AI Connectivity**: Run `python3 scripts/verify_integration.py` to confirm Nvidia/Gemini/Groq keys.
3. **Storage/RAM Guard**: Check `df -h /` and `free -m`. Ensure InfluxDB/Telegraf/Grafana are purged.
4. **Revive Check**: Run `sudo systemctl status kibot-*` to ensure all services are in "active (running)" state.

---

## 🤖 The "Team IT" Agent Legion
The system uses a fallback chain of AI providers for non-trading decision support (Discovery, News, Post-mortems):
1. **Groq** | 2. **Gemini** | 3. **OpenRouter** | 4. **Cohere** | 5. **Jina** | 6. **Nvidia NIM** | 7. **Huggingface**

---

## ⚙️ Operational Commands & Files

### Core Files
- `scripts/kibot_manager.py`: The "Brain". Handles UDP signals, Watchdogs, and AI routing.
- `scripts/kibot_engine_v2.py`: The "Engine". Mathematical consensus and trade execution.
- `scripts/kibot_ai_coordinator.py`: The "Coordinator". Persistent daemon for AI API management.
- `scripts/kibot_rotation_engine.py`: The "Rotation". Agility logic for stagnant positions.

### Service Management
```bash
# Check status of the v7.2 Agent Cluster
sudo systemctl status kibot-*

# Global Restart (Trinity Pulse)
sudo systemctl restart kibot-manager kidax-engine kinance-engine
```

---

## 🔑 Infrastructure Notes (Sanitized)

| Service | Mode | Note |
| :--- | :--- | :--- |
| **AI Providers** | AUDITOR / LEARNING | Simpan token hanya di file env server, jangan pernah commit ke repo. |
| **Indodax Node** | SGP | Oracle Singapore executor node. |
| **Binance Node** | TYO | Oracle Tokyo radar/scanner node. |
| **Database** | CLOUD | Supabase control-plane untuk sinkronisasi state. |

---
*KiBot Trinity v7.2 - Math is the law. Rotation is the edge.*

## 🏗️ KIBOT BLUEPRINT REGISTRY (Production Hardening)

| Date | Component | Change Type | Impact |
| :--- | :--- | :--- | :--- |
| 2026-04-18 | `PairWhitelistManager` | Persistence | Persist/Restore whitelist from Supabase on reboot. |
| 2026-04-18 | `UDP ACK Protocol` | Reliability | Fixed port 8789 ACK for Kinance signal delivery. |
| 2026-04-18 | `PartialTakeProfit` | Risk Management | 30-50% ladder exits at +0.5% & +1.2%. |
| 2026-04-18 | `ProfitLockManager` | Capital Safety | 30% profit auto-locked from re-deployment. |
| 2026-04-18 | `AlwaysInvestedPolicy` | Entry Gate | Dynamic fee/slippage/spread gating for positive EV. |
| 2026-04-18 | `IndodaxGateway` | Precision | Integer quantity scaling for micro-caps (DRX, etc). |

---

## 🏛️ KiBot Trinity Blueprint (v5.0 - Audited)

Daftar komponen sistem yang telah diaudit dan diperkeras untuk produksi 100%:

### [ControlPlaneGateway] — ✅
- **File**: `packages/core/src/commonMain/kotlin/com/kibot/core/ControlPlaneGateway.kt`
- **Fungsi**: Interface utama komunikasi dengan Supabase dan dashboard.
- **Log tag**: `[CP_GATEWAY]`

### [MacEngineDaemon] — ✅
- **File**: `apps/mac-engine/src/main/kotlin/com/kibot/macengine/runtime/MacEngineDaemon.kt`
- **Fungsi**: Jantung eksekusi trading, koordinasi sinyal UDP, dan manajemen posisi.
- **Log tag**: `[BOOT]`, `[UDP_ACK]`, `[POLICY_OK]`

### [AlwaysInvestedPolicy] — ✅
- **File**: `packages/core/src/commonMain/kotlin/com/kibot/core/AlwaysInvestedPolicy.kt`
- **Fungsi**: "Fee Gate" matematika. Memastikan profit > biaya (0.10% LL / 0.15% LP).
- **Log tag**: `[POLICY_OK]`, `[POLICY_SKIP]`

### [CapitalAllocationManager] — ✅
- **File**: `packages/core/src/commonMain/kotlin/com/kibot/core/CapitalAllocationManager.kt`
- **Fungsi**: Penjaga rasio modal 50/50 antara Lead-Lag dan Local Pump.
- **Log tag**: `[ALLOC]`

### [PartialTakeProfitManager] — ✅
- **File**: `packages/core/src/commonMain/kotlin/com/kibot/core/PartialTakeProfitManager.kt`
- **Fungsi**: Manajemen exit bertahap untuk mengunci profit lebih awal.
- **Log tag**: `[PARTIAL_TP]`

### [KiScannerBase] — ✅ Production
- **File**: `scripts/ki_scanner_base.py`
- **Fungsi**: Base scanner global lintas exchange dengan pemetaan pair Indodax dinamis.
- **Dipanggil oleh**: `ki_binance_scanner.py`, `ki_bybit_scanner.py`, `ki_kucoin_scanner.py`, `ki_cryptocom_scanner.py`, `ki_mexc_scanner.py` saat loop radar berjalan.
- **Log tag**: `[INDODAX_PAIRS]`, `[BINANCE]`, `[BYBIT]`, `[KUCOIN]`, `[CRYPTOCOM]`, `[MEXC]`
- **Server**: Tokyo
- **Diubah**: 2026-04-19

### [MultiScannerEngine] — ✅ Production
- **File**: `scripts/multi_scanner_engine.py`
- **Fungsi**: Menggabungkan sinyal multi-scanner menjadi MSC lalu meneruskan entry ke executor.
- **Dipanggil oleh**: `scripts/kibot_manager.py` saat menerima `MULTI_SCANNER_SIGNAL` atau `DETECTOR_HIT`.
- **Log tag**: `[MSC_RECV]`, `[MSC]`, `[MSC_BLOCK]`
- **Server**: SG
- **Diubah**: 2026-04-19

### [KiCapitalEngine] — ✅ Production
- **File**: `scripts/ki_capital_engine.py`
- **Fungsi**: Alokasi modal bucket, partial TP, profit lock, trailing stop, dan hard stop guard.
- **Dipanggil oleh**: `scripts/kibot_manager.py` saat posisi/update fill diproses.
- **Log tag**: `[v7][PARTIAL_TP]`, `[v7][TRAILING_STOP]`, `[v7][PROFIT_LOCK]`, `[v7][HARD_STOP]`
- **Server**: SG
- **Diubah**: 2026-04-19

---
*Blueprint updated: 2026-04-18*
