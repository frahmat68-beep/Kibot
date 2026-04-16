# KiCryp System Audit & Remediation Plan (Module 0)

## Overview
This audit was performed to assess the current state of the KiCryp repository before the **KiCryp v7.0 Complete System Overhaul**. 

## Current Component Status
| Component | File Path | Status | Notes |
| :--- | :--- | :--- | :--- |
| **AlwaysInvestedPolicy** | `packages/core/.../AlwaysInvestedPolicy.kt` | `READY` | Logic complete. Integration into `MacEngineDaemon` pending. |
| **DynamicConfigReloader** | `packages/core/.../DynamicConfigReloader.kt` | `READY` | Fetch logic exists. Integration pending. |
| **CapitalAllocation** | `packages/core/.../CapitalAllocationManager.kt` | `REFACTOR` | Need to rename to `DualBucketManager` and apply 50/50 split. |
| **TradeLogger (Python)** | `scripts/kicryp_manager.py` | `LEGACY` | Python version exists. Need to build Kotlin version (`TradeLogger.kt`). |
| **Learning Engine** | `scripts/kicryp_learning_engine.py` | `STABLE` | Integrated with Python manager. |
| **MacEngineDaemon** | `apps/mac-engine/.../MacEngineDaemon.kt` | `FRAGILE` | Rescued from brace-drift. Needs massive integration of new KiCryp modules. |

## Structural Risks
1.  **Package Naming**: The entire repo uses `com.kicryp`. Renaming packages to `com.kicryp` is extremely high-risk for build stability.
2.  **File Naming**: Scripts like `kicryp_manager.py` need renaming to `kicryp_manager.py`.
3.  **Indodax-Only Detection**: Currently misses early-stage pumps (e.g., BIO-IDR +122%). `kicryp_local_signal.py` is the proposed solution.

## Deployment Blockers
1.  **No Local Java**: Build verification depends entirely on GitHub Actions.
2.  **Git Config**: Fixed in previous session, but requires constant monitoring.

## Remediation Strategy
- **Phase 1**: Implement `TradeLogger.kt` and `CascadeLossGuard.kt`.
- **Phase 2**: Deploy `kicryp_local_signal.py` (Early Pump Detector).
- **Phase 3**: Refactor `MacEngineDaemon` to use `DualBucketManager` (50/50).
- **Phase 4**: Rebrand UI/Logs to KiCryp.

---
*Created by Antigravity on 2026-04-16*
