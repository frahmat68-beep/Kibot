#!/bin/bash

# KiBot Auto-Recovery Script
# This script checks if the Mac engine is running and restarts it if not

LOG_FILE="/home/ubuntu/KiBot/kibot-recovery.log"
ENGINE_PID_FILE="/home/ubuntu/KiBot/mac-engine.pid"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" >> "$LOG_FILE"
}

check_engine() {
    # Check if Java process is running
    if pgrep -f "gradle.*mac-engine" > /dev/null; then
        log "Engine is running"
        return 0
    else
        log "Engine is not running"
        return 1
    fi
}

start_engine() {
    log "Starting Mac engine..."
    cd /home/ubuntu/KiBot

    # Kill any existing processes
    pkill -9 -f gradle
    pkill -9 -f java
    sleep 2

    # Start engine in background
    nohup /home/ubuntu/KiBot/gradle-8.5/bin/gradle :apps:mac-engine:run --no-daemon > /home/ubuntu/KiBot/engine.out 2>&1 &
    echo $! > "$ENGINE_PID_FILE"

    sleep 10

    if check_engine; then
        log "Engine started successfully"
    else
        log "Failed to start engine"
    fi
}

check_dashboard() {
    # Check if dashboard is responding
    if curl -s --max-time 5 http://localhost:8787/ > /dev/null; then
        log "Dashboard is accessible"
        return 0
    else
        log "Dashboard is not accessible"
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
        log "Dashboard not accessible, restarting engine..."
        start_engine
    fi

    log "=== Recovery Check Completed ==="
}

main