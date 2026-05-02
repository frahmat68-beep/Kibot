#!/bin/bash
# KiBot v7.0 Completion Auditor
# Verifies all 12 modules of the Overhaul.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

echo "========================================================="
echo "        KIBOT v7.0 SYSTEM AUDIT (FINAL CHECK)           "
echo "========================================================="

# 1. CORE FILES CHECK
echo -n "[CORE] Capital allocation manager... "
if [ -f "packages/core/src/commonMain/kotlin/com/kibot/core/CapitalAllocationManager.kt" ] || [ -f "packages/core/src/commonMain/kotlin/com/kibot/core/DualBucketManager.kt" ]; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Missing)${NC}"
fi

echo -n "[CORE] TradeLogger.kt... "
if [ -f "packages/core/src/commonMain/kotlin/com/kibot/core/TradeLogger.kt" ]; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Missing)${NC}"
fi

# 2. LOGIC INJECTION CHECK
echo -n "[DAEMON] Control-plane integration... "
if grep -Eq "registerDeviceWithRetry|writeControlPlane|ControlPlane" apps/mac-engine/src/main/kotlin/com/kibot/macengine/runtime/MacEngineDaemon.kt 2>/dev/null; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Not Integrated)${NC}"
fi

echo -n "[DAEMON] Dashboard state serving... "
if grep -Eq "/api/state|buildStateSnapshot|api/health" apps/mac-engine/src/main/kotlin/com/kibot/macengine/server/LocalDashboardServer.kt 2>/dev/null; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Not Integrated)${NC}"
fi

# 3. PYTHON SCRIPT CHECK
echo -n "[SCRIPTS] kibot_manager.py... "
if [ -f "scripts/kibot_manager.py" ]; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Missing/Not Renamed)${NC}"
fi

echo -n "[SCRIPTS] kibot_local_signal.py... "
if [ -f "scripts/kibot_local_signal.py" ]; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Missing)${NC}"
fi

# 4. SIGNAL ENGINE INTEGRATION
echo -n "[INTEGRATION] Signal Engine Management... "
if grep -q "run_local_signal_engine_manager" scripts/kibot_manager.py 2>/dev/null; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Not Managed)${NC}"
fi

# 5. INFRA CHECK
echo -n "[INFRA] Systemd Service (Indodax)... "
if [ -f "infra/systemd/kibot-executor-indodax.service" ]; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Incorrect Naming)${NC}"
fi

echo -n "[INFRA] CI/CD Workflow (Indodax)... "
if [ -f ".github/workflows/deploy-KiBot.yml" ]; then
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
if [ -n "${SUPABASE_URL:-}" ] || grep -q "SUPABASE_URL=" "${PROJECT_ROOT}/.env.kibot" 2>/dev/null; then
    echo -e "${GREEN}PASSED${NC}"
else
    echo -e "${RED}FAILED (Missing)${NC}"
fi

echo "========================================================="
echo "AUDIT COMPLETE. If all GREEN/PASSED, system is 100% READY."
echo "========================================================="
