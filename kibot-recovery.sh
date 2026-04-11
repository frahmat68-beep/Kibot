#!/bin/bash

# KiBot Auto-Recovery Script for Oracle server

set -euo pipefail

source /home/ubuntu/KiDax/.env 2>/dev/null || true
source /home/ubuntu/KiBot/.env.kibot 2>/dev/null || true

SERVICES=("kidax-engine" "kinance-engine" "kibot-manager")
ENDPOINTS=("http://127.0.0.1:8787/api/state" "http://127.0.0.1:8788/api/state" "http://127.0.0.1:9998/api/state")
LOG_FILE="/home/ubuntu/KiBot/kibot-recovery.log"
FAIL_THRESHOLD=3
CHECK_INTERVAL=30
RESTART_COOLDOWN=60
TELEGRAM_BOT_TOKEN="${KIBOT_TELEGRAM_BOT_TOKEN:-${TELEGRAM_BOT_TOKEN:-}}"
TELEGRAM_CHAT_ID="${KIBOT_TELEGRAM_CHAT_ID:-${TELEGRAM_CHAT_ID:-}}"

declare -A fail_streak
for svc in "${SERVICES[@]}"; do
    fail_streak["$svc"]=0
done

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" | tee -a "$LOG_FILE" >/dev/null
}

notify() {
    log "$1"
    if [ -n "$TELEGRAM_BOT_TOKEN" ] && [ -n "$TELEGRAM_CHAT_ID" ]; then
        curl -sf --max-time 5 "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
            -d "chat_id=${TELEGRAM_CHAT_ID}" \
            --data-urlencode "text=${1}" >/dev/null 2>&1 || true
    fi
}

check_endpoint() {
    local endpoint="$1"
    curl -fsS --max-time 5 "$endpoint" >/dev/null 2>&1
}

restart_service() {
    local service_name="$1"
    log "Auto-revive: restarting ${service_name}"
    sudo systemctl restart "$service_name"
}

main() {
    log "=== KiBot Recovery Check Started ==="
    while true; do
        for index in "${!SERVICES[@]}"; do
            service_name="${SERVICES[$index]}"
            endpoint="${ENDPOINTS[$index]}"

            if check_endpoint "$endpoint"; then
                if [ "${fail_streak[$service_name]}" -gt 0 ]; then
                    notify "✅ ${service_name} recovered"
                fi
                fail_streak["$service_name"]=0
                continue
            fi

            fail_streak["$service_name"]=$((fail_streak["$service_name"] + 1))
            notify "⚠️ ${service_name} fail ${fail_streak[$service_name]}/${FAIL_THRESHOLD}"

            if [ "${fail_streak[$service_name]}" -ge "$FAIL_THRESHOLD" ]; then
                restart_service "$service_name"
                fail_streak["$service_name"]=0
                sleep "$RESTART_COOLDOWN"
            fi
        done
        sleep "$CHECK_INTERVAL"
    done
}

main
