# Archived Android Documentation

⚠️ **NOTICE**: The Android/Mac dual-engine architecture has been deprecated.

## Current Architecture: Trinity (3-Bot System)

KiCryp has evolved into the **Trinity** trading system consisting of:

1. **Kinance** (Binance Scanner) - Detects market anomalies
2. **KiDax** (Indodax Executor) - Executes trades
3. **KiCryp Manager** (AI Veto) - Risk management and approval

See `architecture.md` for current system design.

## Archived Documents

The following documents reference the old Android+Mac architecture and are **archived for historical reference only**:

- `failure-modes.md` - Old failure scenarios
- `setup.md` - Old setup instructions
- `assumptions.md` - Old architectural assumptions
- `update-channel.md` - Old update mechanism
- `safe-rollout.md` - Old rollout procedures
- `access-and-secrets.md` - Old credential management
- `test-plan.md` - Old test scenarios
- `KICRYP_CONTRACT.md` - Old system contract

## Migration Notes

**Old System:**
- Android app as primary engine
- Mac as standby/backup engine
- Dual-engine failover

**New System (Trinity):**
- Kinance on Binance Oracle server (scanner only)
- KiDax on Indodax Oracle server (executor)
- KiCryp Manager (Python AI daemon)
- UDP for sub-ms signal communication
- Supabase for control plane

Last Android deployment: March 2026  
Trinity launch: April 2026
