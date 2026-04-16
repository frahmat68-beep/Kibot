#!/bin/bash

set -euo pipefail

ROOT_DIR="${ROOT_DIR:-/home/ubuntu/KiBot}"
LOG_FILE="${LOG_FILE:-$ROOT_DIR/kibot-recovery.log}"
FAIL_THRESHOLD="${FAIL_THRESHOLD:-3}"
CHECK_INTERVAL="${CHECK_INTERVAL:-30}"
RESTART_COOLDOWN="${RESTART_COOLDOWN:-60}"
MAX_CRASH_COUNT="${MAX_CRASH_COUNT:-3}"
CRASH_WINDOW_SEC="${CRASH_WINDOW_SEC:-300}"

source "$ROOT_DIR/.env" 2>/dev/null || true

SERVICES=("kibot-manager" "kidax-engine" "kinance-engine")
ENDPOINTS=("http://127.0.0.1:9998/api/state" "http://127.0.0.1:8787/api/state" "http://127.0.0.1:8788/api/state")

declare -A fail_streak
for service in "${SERVICES[@]}"; do
    fail_streak["$service"]=0
done

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" | tee -a "$LOG_FILE" >/dev/null
}

hard_stop_active() {
    local guard_file="$ROOT_DIR/state/daily_guard.json"
    [ -f "$guard_file" ] || return 1
    python3 - "$guard_file" <<'PY'
import json, sys
path = sys.argv[1]
try:
    data = json.load(open(path))
except Exception:
    raise SystemExit(1)
raise SystemExit(0 if data.get("hard_stopped") else 1)
PY
}

crash_loop_active() {
    local service="$1"
    local crash_count
    crash_count=$(journalctl -u "$service" --since "${CRASH_WINDOW_SEC} sec ago" --no-pager 2>/dev/null | grep -cE "exited, status=1|Failed with result|Traceback|Exception|Error" || true)
    [ "${crash_count:-0}" -ge "$MAX_CRASH_COUNT" ]
}

restart_service() {
    local service="$1"
    if hard_stop_active && [[ "$service" == "kibot-manager" || "$service" == "kidax-engine" ]]; then
        log "[SKIP] ${service}: hard stop aktif"
        return 0
    fi
    if crash_loop_active "$service"; then
        log "[ABORT] ${service}: crash loop terdeteksi, skip restart"
        return 1
    fi
    log "[RECOVER] restarting ${service}"
    sudo systemctl restart "$service"
    sleep "$RESTART_COOLDOWN"
}

main() {
    log "=== KiBot recovery v2 started ==="
    while true; do
        for idx in "${!SERVICES[@]}"; do
            service="${SERVICES[$idx]}"
            endpoint="${ENDPOINTS[$idx]}"
            if curl -fsS --max-time 5 "$endpoint" >/dev/null 2>&1; then
                if [ "${fail_streak[$service]}" -gt 0 ]; then
                    log "[OK] ${service} recovered"
                fi
                fail_streak["$service"]=0
                continue
            fi

            fail_streak["$service"]=$((fail_streak["$service"] + 1))
            log "[WARN] ${service} fail ${fail_streak[$service]}/${FAIL_THRESHOLD}"
            if [ "${fail_streak[$service]}" -ge "$FAIL_THRESHOLD" ]; then
                restart_service "$service" || true
                fail_streak["$service"]=0
            fi
        done
        sleep "$CHECK_INTERVAL"
    done
}

main
