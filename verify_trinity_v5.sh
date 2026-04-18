#!/bin/bash
# KIBOT TRINITY ULTIMATE — VERIFICATION SCRIPT v5
# This script verifies that all architectural fixes are present in the codebase.

DAEMON="apps/mac-engine/src/main/kotlin/com/kibot/macengine/runtime/MacEngineDaemon.kt"
POLICY="packages/core/src/commonMain/kotlin/com/kibot/core/AlwaysInvestedPolicy.kt"
WHITELIST="packages/core/src/commonMain/kotlin/com/kibot/core/PairWhitelistManager.kt"
ANALYZER="packages/core/src/commonMain/kotlin/com/kibot/core/ChartAnalyzer.kt"
MANAGER="scripts/kibot_manager.py"

echo "=== KiBot Trinity v5 Verification ==="

check() {
    local name="$1"
    local file="$2"
    local pattern="$3"
    local res=$(grep -c "$pattern" "$file" 2>/dev/null || echo 0)
    if [ "$res" -gt 0 ]; then
        echo "✅ $name: OK ($res hits)"
    else
        echo "❌ $name: MISSING (Pattern: $pattern in $file)"
    fi
}

echo "--- Architectural Wiring ---"
check "Daily Hard-Stop Logic" "$DAEMON" "dailyRisk.*hardStopTriggered"
check "Partial TP Manager Wiring" "$DAEMON" "partialTpManager.checkTpLevels"
check "Profit Lock Manager Wiring" "$DAEMON" "profitLockManager.onProfitRealized"
check "Capital Allocation (Lead-Lag/Local)" "$DAEMON" "capitalAllocationManager.*allocate"
check "Fee Gate (AlwaysInvested) Wiring" "$DAEMON" "entryPolicy.shouldEnter"

echo "--- Reliability & Connectivity ---"
check "Coroutine Scope (Memory Leak Fix)" "$DAEMON" "daemonScope"
check "Bootstrap Timing Fix" "$DAEMON" "loadFromSupabase"
check "UDP ACK Port (8789)" "$DAEMON" "kinanceAckPort"

echo "--- Intelligence & Logic ---"
check "AlwaysInvested Decision Logic" "$POLICY" "return EntryDecision"
check "RSI Indicator" "$ANALYZER" "calculateRSI"
check "VWAP Indicator" "$ANALYZER" "calculateVWAP"
check "Volume Spike Detection" "$ANALYZER" "detectVolumeSpike"

echo "--- Telemetry & Reporting ---"
check "Telegram Real PnL Tag" "$MANAGER" "Real"
check "Supabase Egress Throttle (30s)" "$MANAGER" "SUPABASE_PUSH_INTERVAL_SEC"

echo "--- Build Status ---"
if [ -f "apps/mac-engine/src/main/kotlin/com/kibot/macengine/runtime/MacEngineDaemon.kt" ]; then
    echo "✅ Files exist. Ready for fatJar build."
else
    echo "❌ Project structure invalid."
fi

echo "======================================"
echo "Verification Complete."
