#!/bin/bash

# KiBot Auto-Recovery Script for Oracle server

set -euo pipefail

LOG_FILE="/home/ubuntu/KiBot/kibot-recovery.log"
HEALTH_URL="${KIBOT_HEALTH_URL:-http://127.0.0.1:8789/api/state}"
ROOT_URL="${KIBOT_ROOT_URL:-http://127.0.0.1:8789/}"
LOGS_URL="${KIBOT_LOGS_URL:-http://127.0.0.1:8789/api/logs}"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" >> "$LOG_FILE"
}

check_engine() {
    if systemctl is-active --quiet kibot-engine; then
        log "Engine service is active"
        return 0
    else
        log "Engine service is not active"
        return 1
    fi
}

start_engine() {
    log "Restarting kibot-engine service"
    sudo systemctl stop kibot.service || true
    sudo systemctl disable kibot.service || true
    sudo rm -f /etc/systemd/system/kibot.service
    sudo systemctl stop kibot-engine || true
    sudo pkill -f 'gradle.*:apps:mac-engine:run' || true
    sudo pkill -f '/home/ubuntu/KiBot/apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar' || true
    sudo pkill -f '/home/ubuntu/mac-engine-0.1.0-all.jar' || true
    sudo pkill -f '/home/ubuntu/KiBot/server/mac-engine-all.jar' || true
    sudo fuser -k "${KIBOT_PORT:-8789}"/tcp || true
    sudo systemctl daemon-reload
    sudo systemctl restart kibot-engine
    sleep 8
    check_engine && log "Engine restarted successfully" || log "Engine restart failed"
}

check_dashboard() {
    if curl -fsS --max-time 5 "$HEALTH_URL" > /dev/null && \
       curl -fsS --max-time 5 "$ROOT_URL" > /dev/null && \
       curl -fsS --max-time 5 "$LOGS_URL" > /dev/null; then
        log "Dashboard/API is accessible"
        return 0
    else
        log "Dashboard/API is not accessible"
        return 1
    fi
}

main() {
    log "=== KiBot Recovery Check Started ==="

    if ! check_engine; then
        log "Engine not running, attempting restart..."
        start_engine
    fi

    if ! check_dashboard; then
        log "Dashboard/API unhealthy, restarting engine..."
        start_engine
    fi

    log "=== Recovery Check Completed ==="
}

main
