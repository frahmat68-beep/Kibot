#!/bin/bash
set -u

PASS=0
FAIL=0
WARN=0

check() {
    local name="$1"
    local cmd="$2"
    local expected="$3"
    local result
    result=$(eval "$cmd" 2>/dev/null || true)
    if [[ "$result" == *"$expected"* ]]; then
        echo "PASS  $name"
        PASS=$((PASS + 1))
    else
        echo "FAIL  $name (got: ${result:0:120})"
        FAIL=$((FAIL + 1))
    fi
}

warn_absent() {
    local name="$1"
    local cmd="$2"
    local bad_pattern="$3"
    local result
    result=$(eval "$cmd" 2>/dev/null || true)
    if [[ "$result" == *"$bad_pattern"* ]]; then
        echo "WARN  $name ($bad_pattern)"
        WARN=$((WARN + 1))
    else
        echo "PASS  $name"
        PASS=$((PASS + 1))
    fi
}

echo "=== Control Plane ==="
warn_absent "No register-device 12s timeout" "journalctl -u kibot-executor-indodax --since '5 minutes ago' --no-pager" "Timed out waiting for 12000 ms"
warn_absent "No lifecycle STOPPED block" "journalctl -u kibot-executor-indodax --since '5 minutes ago' --no-pager" "LIFECYCLE_BLOCK: Cannot start sync cycle"

echo "=== File Integrity ==="
for f in state/whatif_results.json state/trade_summary.json; do
    if python3 -c "import json; json.load(open('$f'))" 2>/dev/null; then
        echo "PASS  $f valid JSON"
        PASS=$((PASS + 1))
    else
        echo "FAIL  $f invalid or missing"
        FAIL=$((FAIL + 1))
    fi
done

echo "=== Systemd ==="
warn_absent "kibot-executor-indodax StartLimit not inside [Service]" "systemctl cat kibot-executor-indodax | grep -A 20 '^\\[Service\\]' | grep StartLimitIntervalSec" "StartLimitIntervalSec"
warn_absent "No invalid OOM policy warning" "journalctl -u kibot-executor-indodax -n 50 --no-pager" "Failed to parse OOM policy"

echo "=== Health Endpoint ==="
check "Health endpoint reachable" "curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:8787/api/health" "200"

echo "=== Summary ==="
echo "PASS=$PASS FAIL=$FAIL WARN=$WARN"
[ "$FAIL" -eq 0 ]
