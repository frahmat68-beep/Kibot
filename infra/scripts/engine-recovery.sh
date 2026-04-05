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
MAX_ATTEMPTS="${KIBOT_RECOVERY_MAX_ATTEMPTS:-3}"
BASE_DELAY_SECONDS="${KIBOT_RECOVERY_BASE_DELAY_SECONDS:-6}"
MAX_DELAY_SECONDS="${KIBOT_RECOVERY_MAX_DELAY_SECONDS:-30}"
EXPECT_LIVE_EXECUTION="${KIBOT_EXPECT_LIVE_EXECUTION:-true}"

if [[ -n "$ENV_FILE" && -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" >> "$LOG_FILE"
}

parse_state() {
  python3 - "$1" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
try:
    data = json.loads(path.read_text())
except Exception:
    print("INVALID")
    raise SystemExit(0)
effective = data.get("effectiveState", "")
running = data.get("isBotRunning", False)
live = data.get("liveExecutionEnabled", False)
print(f"{effective}|{str(running).lower()}|{str(live).lower()}")
PY
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

check_dashboard() {
  local tmp_file
  tmp_file="$(mktemp)"
  if curl -fsS --max-time 5 "$HEALTH_URL" > "$tmp_file" && \
     curl -fsS --max-time 5 "$ROOT_URL" >/dev/null && \
     curl -fsS --max-time 5 "$LOGS_URL" >/dev/null; then
    local parsed
    parsed="$(parse_state "$tmp_file")"
    rm -f "$tmp_file"
    local live_ok="true"
    if [[ "$EXPECT_LIVE_EXECUTION" == "false" ]]; then
      live_ok="*"
    fi
    if [[ "$parsed" == RUNNING* ]] || [[ "$parsed" == *"|true|true" ]] || [[ "$parsed" == *"|true|false" && "$EXPECT_LIVE_EXECUTION" == "false" ]]; then
      log "Dashboard/API is accessible and running"
      return 0
    fi
    log "Dashboard/API accessible but state is ${parsed}"
    return 1
  fi
  rm -f "$tmp_file"
  log "Dashboard/API is not accessible"
  return 1
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

restart_with_backoff() {
  local attempt="$1"
  local delay=$((BASE_DELAY_SECONDS * (1 << (attempt - 1))))
  if (( delay > MAX_DELAY_SECONDS )); then
    delay="$MAX_DELAY_SECONDS"
  fi
  log "Recovery backoff ${delay}s before retry ${attempt}/${MAX_ATTEMPTS}"
  sleep "$delay"
}

main() {
  log "=== ${SERVICE_NAME} Recovery Check Started ==="

  for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
    engine_ok=0
    dashboard_ok=0
    if check_engine; then
      engine_ok=1
    fi
    if check_dashboard; then
      dashboard_ok=1
    fi
    if [[ "$engine_ok" -eq 1 && "$dashboard_ok" -eq 1 ]]; then
      log "Engine and dashboard healthy"
      break
    fi

    log "Engine/dashboard unhealthy, attempt ${attempt}/${MAX_ATTEMPTS}"
    start_engine
    if check_engine && check_dashboard; then
      log "Engine and dashboard recovered"
      break
    fi

    if (( attempt < MAX_ATTEMPTS )); then
      restart_with_backoff "$attempt"
    fi
  done

  log "=== ${SERVICE_NAME} Recovery Check Completed ==="
}

main
