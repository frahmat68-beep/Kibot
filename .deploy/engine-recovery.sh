#!/bin/bash

set -euo pipefail

RUNTIME_ROOT="${KIBOT_RUNTIME_ROOT:-/home/ubuntu/KiDax}"
SERVICE_NAME="${KIBOT_SERVICE_NAME:-kidax-engine}"
DASHBOARD_PORT="${KIBOT_DASHBOARD_PORT:-8787}"
LOG_FILE="${KIBOT_RECOVERY_LOG_FILE:-${RUNTIME_ROOT}/kibot-recovery.log}"
HEALTH_URL="http://127.0.0.1:${DASHBOARD_PORT}/api/state"
ROOT_URL="http://127.0.0.1:${DASHBOARD_PORT}/"
LOGS_URL="http://127.0.0.1:${DASHBOARD_PORT}/api/logs"
JAR_PATH="${RUNTIME_ROOT}/server/mac-engine-all.jar"
ENV_FILE="${KIBOT_ENV_FILE:-}"

if [[ -n "$ENV_FILE" && -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" >> "$LOG_FILE"
}

check_engine() {
  if systemctl is-active --quiet "$SERVICE_NAME"; then
    log "Engine service is active"
    return 0
  else
    log "Engine service is not active"
    return 1
  fi
}

start_engine() {
  log "Restarting ${SERVICE_NAME} service"
  sudo systemctl stop "$SERVICE_NAME" || true
  sudo pkill -f 'gradle.*:apps:mac-engine:run' || true
  sudo pkill -f "$JAR_PATH" || true
  sudo fuser -k "${DASHBOARD_PORT}/tcp" || true
  sudo systemctl daemon-reload
  sudo systemctl restart "$SERVICE_NAME"
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
  log "=== ${SERVICE_NAME} Recovery Check Started ==="

  if ! check_engine; then
    log "Engine not running, attempting restart..."
    start_engine
  fi

  if ! check_dashboard; then
    log "Dashboard/API unhealthy, restarting engine..."
    start_engine
  fi

  log "=== ${SERVICE_NAME} Recovery Check Completed ==="
}

main
