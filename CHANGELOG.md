# Changelog - KiBot Trinity

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [6.2.0] - 2026-04-15
### Added
- **Trinity v6.2 Upgrade**: Full integration of math-first, memory-capable trading logic.
- **TradeLogger**: Persistent local JSONL logging with asynchronous Supabase synchronization.
- **What-If Engine**: Pre-trade simulation for net profitability, Kelly sizing, and risk-reward ratios.
- **PureTechnicalDetector**: Independent technical analysis (Bollinger, RSI, Volume) for Indodax-only pairs (WHITEWHALE, BR, DRX, BIO).
- **30-min Math Review**: Automated performance monitoring with dynamic entry threshold adjustments and Telegram reporting.
- **Supabase Migration**: Complete schema implementation for `trade_history`, `pair_memory`, and `performance_snapshots`.
- **25% Single-Coin Cap**: Strictly enforced position sizing limit in `CapitalAllocationManager.kt`.
- **Periodic Capital Logging**: 5-minute status reports in `MacEngineDaemon.kt`.
- **Universal Screener**: Real-time scanner for all 200+ Indodax pairs (replaced Whitelist).
- **Order Book Vision**: Bid/Ask volume imbalance detection (Ratio > 2.0).
- **Pump Reversal Security**: Volume collapse detection and Dynamic Peak Locking (Tighten stop at 1%).
- **Learning Bridge**: Hard-wired TradeLogger to LearningEngine for real-time Bayesian-Kelly updates.
- **Global Consensus (Whiteboard)**: Integrated 'Papan Tulis' system for cross-exchange validation (Binance + Crypto.com).
- **KiCryp Radar**: New real-time WebSocket streamer for high-confidence global signals.
- **50/50 Capital Partitioning**: Reformulated core capital allocation into Local Sniper (50%) and Global Alpha (50%) buckets.

### Changed
- **Refactored `kibot_manager.py`**: Integrated the Trinity Brain components into the main execution loop.
- **Refactored `MacEngineDaemon.kt`**: Unified capital allocation logic and removed legacy hardcoded budget caps.
- **Improved Anomaly Detection**: Replaced hardcoded coin lists with dynamic volume spike detection (>= 2.5x).

### Removed
- **Obsolete Documentation**: Mass deletion of 26+ outdated `.md` and `.txt` files to maintain a clean workspace.

## [6.1.0] - 2026-04-10
### Added
- Initial Trinity architecture (Lead-Lag + Indodax Executor).
- Bayesian-Kelly risk management framework (theoretical).

## [3.0.0] - Prior to April 2026
- Legacy AI-relying architecture.
- Re-entry logic and basic portfolio management.
