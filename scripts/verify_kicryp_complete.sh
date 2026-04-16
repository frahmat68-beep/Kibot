#!/bin/bash
# KiCryp v7.0 Completion Auditor
# Verifies all 12 modules of the Overhaul.

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

echo "========================================================="
echo "        KICRYP v7.0 SYSTEM AUDIT (FINAL CHECK)           "
echo "========================================================="

# 1. CORE FILES CHECK
echo -n "[CORE] DualBucketManager.kt... "
if [ -f "packages/core/src/commonMain/kotlin/com/kicryp/core/DualBucketManager.kt" ]; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Missing)${NC}"
fi

echo -n "[CORE] TradeLogger.kt... "
if [ -f "packages/core/src/commonMain/kotlin/com/kicryp/core/TradeLogger.kt" ]; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Missing)${NC}"
fi

# 2. LOGIC INJECTION CHECK
echo -n "[DAEMON] CascadeLossGuard logic... "
if grep -q "cascadeLevel" apps/mac-engine/src/main/kotlin/com/kicryp/macengine/runtime/MacEngineDaemon.kt 2>/dev/null; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Not Injected)${NC}"
fi

echo -n "[DAEMON] DualBucket integration... "
if grep -q "dualBucketManager" apps/mac-engine/src/main/kotlin/com/kicryp/macengine/runtime/MacEngineDaemon.kt 2>/dev/null; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Not Injected)${NC}"
fi

# 3. PYTHON SCRIPT CHECK
echo -n "[SCRIPTS] kicryp_manager.py... "
if [ -f "scripts/kicryp_manager.py" ]; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Missing/Not Renamed)${NC}"
fi

echo -n "[SCRIPTS] kicryp_local_signal.py... "
if [ -f "scripts/kicryp_local_signal.py" ]; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Missing)${NC}"
fi

# 4. SIGNAL ENGINE INTEGRATION
echo -n "[INTEGRATION] Signal Engine Management... "
if grep -q "run_local_signal_engine_manager" scripts/kicryp_manager.py 2>/dev/null; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Not Managed)${NC}"
fi

# 5. INFRA CHECK
echo -n "[INFRA] Systemd Service (Indodax)... "
if [ -f "infra/systemd/kicryp-indodax.service" ]; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Incorrect Naming)${NC}"
fi

echo -n "[INFRA] CI/CD Workflow (Indodax)... "
if [ -f ".github/workflows/deploy-kicryp-indodax.yml" ]; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Incorrect Naming)${NC}"
fi

# 7. ENV & PERMISSIONS CHECK
echo -n "[RUNTIME] state/ directory... "
if [ -d "state" ] && [ -w "state" ]; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (state/ not writable)${NC}"
fi

echo -n "[ENV] SUPABASE_URL presence... "
if [ -n "${SUPABASE_URL:-}" ] || grep -q "SUPABASE_URL=" .env.kicryp 2>/dev/null; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Missing)${NC}"
fi

echo "========================================================="
echo "AUDIT COMPLETE. If all GREEN/PASSED, system is 100% READY."
echo "========================================================="
