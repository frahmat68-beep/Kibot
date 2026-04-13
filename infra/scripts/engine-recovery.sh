#!/bin/bash

set -euo pipefail

RUNTIME_ROOT="${KIBOT_RUNTIME_ROOT:-/home/ubuntu/KiDax}"
SERVICE_NAME="${KIBOT_SERVICE_NAME:-kidax-engine}"
DASHBOARD_PORT="${KIBOT_DASHBOARD_PORT:-8787}"
LOG_FILE="${KIBOT_RECOVERY_LOG_FILE:-${RUNTIME_ROOT}/kibot-recovery.log}"
STATE_FILE="${KIBOT_RECOVERY_STATE_FILE:-${RUNTIME_ROOT}/.recovery-state}"
HEALTH_URL="http://127.0.0.1:${DASHBOARD_PORT}/api/state"
ROOT_URL="http://127.0.0.1:${DASHBOARD_PORT}/"
LOGS_URL="http://127.0.0.1:${DASHBOARD_PORT}/api/logs"
ENGINE_LAUNCHER="${RUNTIME_ROOT}/bin/mac-engine"
ENV_FILE="${KIBOT_ENV_FILE:-}"
MAX_ATTEMPTS="${KIBOT_RECOVERY_MAX_ATTEMPTS:-3}"
BASE_DELAY_SECONDS="${KIBOT_RECOVERY_BASE_DELAY_SECONDS:-6}"
MAX_DELAY_SECONDS="${KIBOT_RECOVERY_MAX_DELAY_SECONDS:-30}"
EXPECT_LIVE_EXECUTION="${KIBOT_EXPECT_LIVE_EXECUTION:-true}"
HEALTHCHECK_TIMEOUT_SECONDS="${KIBOT_RECOVERY_HTTP_TIMEOUT_SECONDS:-5}"
ALLOW_SLOW_PORT_FALLBACK="${KIBOT_RECOVERY_ALLOW_SLOW_PORT_FALLBACK:-false}"
WARMUP_GRACE_SECONDS="${KIBOT_RECOVERY_WARMUP_GRACE_SECONDS:-90}"
TELEGRAM_BOT_TOKEN="${KIBOT_TELEGRAM_BOT_TOKEN:-${TELEGRAM_BOT_TOKEN:-}}"
TELEGRAM_CHAT_ID="${KIBOT_TELEGRAM_CHAT_ID:-${TELEGRAM_CHAT_ID:-}}"

if [[ -n "$ENV_FILE" && -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

log() {
  mkdir -p "$(dirname "$LOG_FILE")" "$(dirname "$STATE_FILE")"
  echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" >> "$LOG_FILE"
}

send_telegram() {
  local text="$1"
  if [[ -z "${TELEGRAM_BOT_TOKEN}" || -z "${TELEGRAM_CHAT_ID}" ]]; then
    return 0
  fi
  curl -fsS --max-time 8 \
    -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
    -d "chat_id=${TELEGRAM_CHAT_ID}" \
    --data-urlencode "text=${text}" \
    -d "disable_web_page_preview=true" >/dev/null || true
}

read_state() {
  if [[ -f "${STATE_FILE}" ]]; then
    cat "${STATE_FILE}" 2>/dev/null || true
  fi
}

write_state() {
  printf '%s\n' "$1" > "${STATE_FILE}"
}

mark_healthy() {
  local previous
  previous="$(read_state)"
  if [[ "${previous}" != "healthy" ]]; then
    log "Recovery state -> healthy"
    send_telegram "✅ ${SERVICE_NAME} RUNNING lagi"
    write_state "healthy"
  fi
}

mark_unhealthy() {
  local previous
  previous="$(read_state)"
  if [[ "${previous}" != "unhealthy" ]]; then
    log "Recovery state -> unhealthy"
    send_telegram "🚨 ${SERVICE_NAME} bermasalah. Auto revive aktif."
    write_state "unhealthy"
  fi
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

service_uptime_seconds() {
  local main_pid
  main_pid="$(systemctl show -p MainPID --value "$SERVICE_NAME" 2>/dev/null || true)"
  if [[ -z "$main_pid" || "$main_pid" == "0" ]]; then
    echo 0
    return 0
  fi
  ps -o etimes= -p "$main_pid" 2>/dev/null | tr -d ' ' || echo 0
}

check_dashboard() {
  local tmp_file
  tmp_file="$(mktemp)"
  if curl -fsS --max-time "$HEALTHCHECK_TIMEOUT_SECONDS" "$HEALTH_URL" > "$tmp_file" && \
     curl -fsS --max-time "$HEALTHCHECK_TIMEOUT_SECONDS" "$ROOT_URL" >/dev/null && \
     curl -fsS --max-time "$HEALTHCHECK_TIMEOUT_SECONDS" "$LOGS_URL" >/dev/null; then
    local parsed
    parsed="$(parse_state "$tmp_file")"
    rm -f "$tmp_file"
    log "Dashboard/API is accessible (${parsed})"
    return 0
  fi
  if [[ "${ALLOW_SLOW_PORT_FALLBACK,,}" == "true" ]] && \
     systemctl is-active --quiet "$SERVICE_NAME" && \
     ss -ltn "( sport = :${DASHBOARD_PORT} )" | grep -q ":${DASHBOARD_PORT}"; then
    rm -f "$tmp_file"
    log "Dashboard/API slow but port ${DASHBOARD_PORT} is listening; allowing standby fallback"
    return 0
  fi
  rm -f "$tmp_file"
  log "Dashboard/API is not accessible"
  return 1
}

start_engine() {
  log "Restarting ${SERVICE_NAME} service"
  sudo systemctl daemon-reload
  sudo systemctl stop "$SERVICE_NAME" || true
  sudo pkill -f 'gradle.*:apps:mac-engine:run' || true
  sudo pkill -f "$ENGINE_LAUNCHER" || true
  sudo fuser -k "${DASHBOARD_PORT}/tcp" || true
  sudo systemctl restart "$SERVICE_NAME"
  sleep 8
  if check_engine; then
    if check_dashboard; then
      log "Engine and dashboard restarted successfully"
      return 0
    fi
  fi
  log "Engine restart failed"
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
  if [[ -n "${ENV_FILE}" && -f "${ENV_FILE}" ]]; then
    log "Using env file ${ENV_FILE}"
  fi
  if [[ ! -x "${ENGINE_LAUNCHER}" ]]; then
    log "Engine launcher missing or not executable: ${ENGINE_LAUNCHER}"
    mark_unhealthy
    exit 1
  fi

  for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
    engine_ok=0
    dashboard_ok=0
    if check_engine; then
      engine_ok=1
    fi
    if check_dashboard; then
      dashboard_ok=1
    fi
    if [[ "$engine_ok" -eq 1 ]]; then
      uptime_seconds="$(service_uptime_seconds)"
      if [[ "$uptime_seconds" =~ ^[0-9]+$ ]] && (( uptime_seconds < WARMUP_GRACE_SECONDS )); then
        log "Service warm-up grace active (${uptime_seconds}s < ${WARMUP_GRACE_SECONDS}s); skipping restart"
        break
      fi
    fi
    if [[ "$engine_ok" -eq 1 && "$dashboard_ok" -eq 1 ]]; then
      log "Engine and dashboard healthy"
      mark_healthy
      break
    fi

    log "Engine/dashboard unhealthy, attempt ${attempt}/${MAX_ATTEMPTS}"
    mark_unhealthy
    start_engine
    if check_engine && check_dashboard; then
      log "Engine and dashboard recovered"
      mark_healthy
      break
    fi

    if (( attempt < MAX_ATTEMPTS )); then
      restart_with_backoff "$attempt"
    fi
  done

  if ! check_engine || ! check_dashboard; then
    mark_unhealthy
  fi

  log "=== ${SERVICE_NAME} Recovery Check Completed ==="
}

main
