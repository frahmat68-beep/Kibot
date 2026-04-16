# Trinity v7.0 Technical Context Report (Last 12 Hours)

## 📋 Overview
This document summarizes the critical recovery and synchronization work performed on the KiBot Trinity v7.0 production environment. It is intended for AI collaborators to understand the recent shifts in architecture, telemetry, and deployment logic.

## 🛠️ Key Technical Changes

### 1. Telemetry & Data Model Synchronization
*   **High-Fidelity Tracking**: Added `conviction` (Double) and `signalSource` (String) to core telemetry models (`TradeRecord`, `TrinityPendingSignal`, and `CommandCenterHolding`).
*   **Timestamp Integrity**: Enforced non-nullable `lastLossTimestampEpochMs` across the dashboard state and WebSocket envelopes to prevent UI jitter and data gaps.
*   **Global Logic Scoping**: Refactored loop-level variables (`currentRegime`, `currentBalanceIdr`, `wasAggressiveTrade`) from local timeout blocks in `MacEngineDaemon.kt` to global `syncOnce` scope to ensure availability for logging and telemetry.

### 2. Build Strategy & Enum Normalization
*   **BotMode Alignment**: Standardized all high-aggression checks to `BotMode.ATTACK`. 
    *   *Context*: Legacy `HYPER_AGGRESSIVE` member was deprecated/removed in the v7.0 shared-models.
*   **Fail-Safe Compilation**: Fixed multiple unresolved references in `MacEngineDaemon.kt` by providing default values for new telemetry parameters in `TradeLogger` and `TrinityPendingSignal`.

### 3. Stability & Null-Safety
*   **Early-Return Pattern**: Implemented guarded null-checks in `maybeManageLiveTrading`. If the strategy cycle is null, the engine now gracefully returns rather than attempting property access on a null object.
*   **Logic Regression**: Successfully passed the full suite of 15 regression tests (`scripts/test_v7_logic.py`), covering Whiteshale blocking, Cascade State progression, and TP/SL execution.

## 🚀 Deployment Status

### 🛰️ Environment Status
*   **Latest Stable Commit**: `e1e8d0d0` (Final Build Normalization).
*   **CI/CD Pipeline**: **GREEN** (Compilation and Jar construction successful for Indodax and Kinance).

### 🚩 Known Impediment (Last-Mile)
Both production nodes (Indodax: 213.35.118.26, Kinance: 152.69.218.198) are currently blocked at the `Install and Restart` phase.
*   **Error**: `Failed to enable unit: File /etc/systemd/system/multi-user.target.wants/kidax-engine.service already exists.`
*   **Resolution Required**: Manual SSH cleanup of existing systemd symlinks on the target servers is necessary before the next automated deployment.

## 💡 Notes for AI Collaborators
*   **Environment Variables**: Ensure `KIBOT_PERSONAL_ACCESS_TOKEN` is loaded for MCP functionality.
*   **Core Logic**: The engine is now biased towards the `ATTACK` mode for high-confidence signals from Kinance, managed via the `CapitalAllocationManager`.
*   **Telemetry**: Always use the provided default values for `conviction` (0.0) and `signalSource` ("UNKNOWN") when instantiating new trade records.

---
*Report Generated: 2026-04-17 02:50 AM WIB*
*Status: Ready for Production Maintenance*
