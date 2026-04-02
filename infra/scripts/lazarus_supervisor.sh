#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="${ROOT_DIR:-/home/ubuntu/KiBot}"
LOG_FILE="${LOG_FILE:-${ROOT_DIR}/logs/lazarus.supervisor.log}"
WATCH_SCRIPT="${WATCH_SCRIPT:-${ROOT_DIR}/infra/scripts/lazarus_autostart_watch.sh}"
PID_FILE="${PID_FILE:-${ROOT_DIR}/logs/lazarus.pid}"
WATCH_OUT="${WATCH_OUT:-${ROOT_DIR}/logs/lazarus.watch.out}"
STATUS_FILE="${STATUS_FILE:-${ROOT_DIR}/state/lazarus.status.json}"
RESTART_DELAY_SECONDS="${RESTART_DELAY_SECONDS:-10}"
HEALTH_CHECK_INTERVAL_SECONDS="${HEALTH_CHECK_INTERVAL_SECONDS:-20}"

mkdir -p "$(dirname "$LOG_FILE")"
touch "$LOG_FILE"

exec 200>"${ROOT_DIR}/logs/lazarus.supervisor.lock"
flock -n 200 || exit 0

log() {
  printf '[%s] %s\n' "$(date '+%F %T')" "$*" | tee -a "$LOG_FILE"
}

running_pid() {
  local pid=""
  if [[ -f "$PID_FILE" ]]; then
    pid="$(tr -d '[:space:]' <"$PID_FILE" || true)"
  fi
  if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
    echo "$pid"
    return 0
  fi
  return 1
}

start_watchdog() {
  if [[ -f "$STATUS_FILE" ]] && grep -q '"status":"completed"' "$STATUS_FILE"; then
    log "[Supervisor] Lazarus completed already. Standing by."
    return 0
  fi
  if [[ ! -x "$WATCH_SCRIPT" ]]; then
    log "[Supervisor] Watch script missing: $WATCH_SCRIPT"
    return 1
  fi

  log "[Supervisor] Starting Lazarus watch script..."
  nohup env \
    PATH="/home/ubuntu/bin:/home/ubuntu/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    bash "$WATCH_SCRIPT" >>"$WATCH_OUT" 2>&1 < /dev/null &
  echo $! >"$PID_FILE"
  log "[Supervisor] Watch PID $(cat "$PID_FILE")"
}

trap 'log "[Supervisor] Exit signal received."; exit 0' INT TERM

log "[Supervisor] Boot"
while true; do
  if [[ -f "$STATUS_FILE" ]] && grep -q '"status":"completed"' "$STATUS_FILE"; then
    log "[Supervisor] Completion marker found; remaining idle."
    sleep "$HEALTH_CHECK_INTERVAL_SECONDS"
    continue
  fi
  if pid="$(running_pid)"; then
    log "[Supervisor] Watch alive (pid=$pid)"
  else
    log "[Supervisor] Watch missing. Restarting in ${RESTART_DELAY_SECONDS}s..."
    sleep "$RESTART_DELAY_SECONDS"
    start_watchdog || true
  fi

  sleep "$HEALTH_CHECK_INTERVAL_SECONDS"
done
