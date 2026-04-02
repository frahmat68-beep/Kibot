#!/usr/bin/env bash
set -Eeuo pipefail

LOCK_DIR="/tmp/lazarus-auto-migrate.lockdir"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  echo "[FAIL] Lazarus auto-migrate already running"
  exit 0
fi
cleanup_lock() {
  rmdir "$LOCK_DIR" 2>/dev/null || true
}
trap cleanup_lock EXIT

usage() {
  cat <<'EOF'
Usage:
  lazarus_auto_migrate.sh --ocid OCID --kidax-jar /path/to/KiDax.jar --kibot-jar /path/to/KiBot.jar

Optional:
  --host 152.69.218.198
  --user ubuntu
  --port 22
  --ssh-key /path/to/key.pem
  --oci-config /path/to/.oci/config
  --log-file /path/to/lazarus.log
  --timeout 180
EOF
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BINANCE_HOST="152.69.218.198"
BINANCE_USER="ubuntu"
BINANCE_PORT="22"
BINANCE_KEY="${ROOT_DIR}/SSH_BINANCE/ssh-key-2026-03-27.key"
OCI_CONFIG_FILE="${HOME}/.oci/config"
LOG_FILE="${ROOT_DIR}/logs/lazarus.log"
SSH_TIMEOUT_SECONDS="180"
OCID_SERVER_B=""
LOCAL_KIDAX_JAR="${ROOT_DIR}/apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar"
LOCAL_KIBOT_JAR="${ROOT_DIR}/apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ocid) OCID_SERVER_B="$2"; shift 2 ;;
    --host) BINANCE_HOST="$2"; shift 2 ;;
    --user) BINANCE_USER="$2"; shift 2 ;;
    --port) BINANCE_PORT="$2"; shift 2 ;;
    --ssh-key) BINANCE_KEY="$2"; shift 2 ;;
    --oci-config) OCI_CONFIG_FILE="$2"; shift 2 ;;
    --log-file) LOG_FILE="$2"; shift 2 ;;
    --timeout) SSH_TIMEOUT_SECONDS="$2"; shift 2 ;;
    --kidax-jar) LOCAL_KIDAX_JAR="$2"; shift 2 ;;
    --kibot-jar) LOCAL_KIBOT_JAR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "[FAIL] Argumen tidak dikenal: $1"; usage; exit 1 ;;
  esac
done

if [[ -z "$OCID_SERVER_B" ]]; then
  echo "[FAIL] --ocid wajib diisi"
  exit 1
fi
if [[ ! -f "$BINANCE_KEY" ]]; then
  echo "[FAIL] SSH key tidak ditemukan: $BINANCE_KEY"
  exit 1
fi
if [[ ! -f "$OCI_CONFIG_FILE" ]]; then
  echo "[FAIL] OCI config tidak ditemukan: $OCI_CONFIG_FILE"
  exit 1
fi
if [[ ! -f "$LOCAL_KIDAX_JAR" ]]; then
  echo "[FAIL] KiDax jar tidak ditemukan: $LOCAL_KIDAX_JAR"
  exit 1
fi
if [[ ! -f "$LOCAL_KIBOT_JAR" ]]; then
  echo "[FAIL] KiBot jar tidak ditemukan: $LOCAL_KIBOT_JAR"
  exit 1
fi
if ! command -v oci >/dev/null 2>&1; then
  if [[ -x "${HOME}/bin/oci" ]]; then
    export PATH="${HOME}/bin:${PATH}"
  elif [[ -x "${HOME}/.local/bin/oci" ]]; then
    export PATH="${HOME}/.local/bin:${PATH}"
  fi
fi
if ! command -v oci >/dev/null 2>&1; then
  echo "[FAIL] oci CLI tidak ditemukan di server A"
  exit 1
fi
if ! command -v nc >/dev/null 2>&1; then
  echo "[FAIL] nc tidak ditemukan di server A"
  exit 1
fi

mkdir -p "$(dirname "$LOG_FILE")"
mkdir -p "${ROOT_DIR}/state"
touch "$LOG_FILE"

log() {
  printf '[%s] %s\n' "$(date '+%F %T')" "$*" | tee -a "$LOG_FILE"
}

stage() {
  log "=== $* ==="
}

run_oci() {
  OCI_CLI_SUPPRESS_FILE_PERMISSIONS_WARNING=true oci --config-file "$OCI_CONFIG_FILE" "$@"
}

get_instance_state() {
  run_oci compute instance get \
    --instance-id "$OCID_SERVER_B" \
    --query 'data."lifecycle-state"' \
    --raw-output 2>/dev/null | tr -d '\r'
}

get_public_ip() {
  run_oci compute instance list-vnics \
    --instance-id "$OCID_SERVER_B" \
    --all \
    --query 'data[?`public-ip`!=null]."public-ip" | [0]' \
    --raw-output
}

wait_for_ssh() {
  local ip="$1"
  local waited=0
  while (( waited < SSH_TIMEOUT_SECONDS )); do
    if nc -z -w 3 "$ip" 22 >/dev/null 2>&1; then
      return 0
    fi
    sleep 5
    waited=$((waited + 5))
  done
  return 1
}

wait_for_state_ready() {
  local ip="$1"
  local port="$2"
  local waited=0
  while (( waited < SSH_TIMEOUT_SECONDS )); do
    local state_json
    if state_json="$(curl -fsS --max-time 5 "http://${ip}:${port}/api/state" 2>/dev/null)"; then
      if [[ "$(python3 - "$state_json" <<'PY'
import json, sys
data = json.loads(sys.argv[1])
ok = bool(data.get("isBotRunning"))
effective = str(data.get("effectiveState") or "")
sync_health = str(data.get("syncHealth") or "")
print("READY" if ok and effective in {"RUNNING", "SAFE_MODE", "DEGRADED"} and sync_health in {"HEALTHY", "DEGRADED", "BROKEN"} else "WAIT")
PY
)" == "READY" ]]; then
          return 0
      fi
    fi
    sleep 5
    waited=$((waited + 5))
  done
  return 1
}

stage "Lazarus Protocol Boot"
log "Target OCID: ${OCID_SERVER_B}"
log "Log file: ${LOG_FILE}"
log "SSH key: ${BINANCE_KEY}"
log "OCI config: ${OCI_CONFIG_FILE}"

retry_count=0
while true; do
  retry_count=$((retry_count + 1))
  current_state="$(get_instance_state || true)"
  log "[Lazarus] Check state #${retry_count}: ${current_state:-unknown}"

  case "$current_state" in
    RUNNING)
      log "[Lazarus] Instance already RUNNING. Lanjut fase auto-migrate."
      break
      ;;
    PROVISIONING|STARTING|STOPPING|TERMINATING)
      log "[WAIT] Oracle sedang memproses/menyalakan server. Menunggu RUNNING..."
      sleep 30
      continue
      ;;
    STOPPED|"")
      log "[Lazarus] START attempt #${retry_count} for ${OCID_SERVER_B}"
      set +e
      START_OUT="$(run_oci compute instance action --action START --instance-id "$OCID_SERVER_B" 2>&1)"
      RC=$?
      set -e
      printf '%s\n' "$START_OUT" >> "$LOG_FILE"

      if grep -q "Out of host capacity" <<<"$START_OUT"; then
        log "[FULL] Kapasitas penuh, retry..."
        sleep 30
        continue
      fi
      if grep -q "Conflict" <<<"$START_OUT"; then
        log "[WAIT] Oracle sedang memproses/menyalakan server. Menunggu RUNNING..."
        sleep 30
        continue
      fi
      if [[ $RC -ne 0 ]]; then
        log "[Lazarus] Start command failed (rc=${RC}). Retrying in 30s..."
        log "[Lazarus] OCI response: ${START_OUT}"
        sleep 30
        continue
      fi
      log "[Lazarus] OCI response: ${START_OUT}"
      ;;
    *)
      log "[WAIT] Oracle sedang memproses/menyalakan server. Menunggu RUNNING..."
      sleep 30
      continue
      ;;
  esac
done

stage "Resolve Public IP"
NEW_IP="$(get_public_ip)"
if [[ -z "${NEW_IP:-}" || "$NEW_IP" == "null" ]]; then
  log "[Lazarus] Failed to resolve public IP."
  exit 1
fi
log "[Lazarus] Public IP: ${NEW_IP}"

stage "Wait For SSH"
if ! wait_for_ssh "$NEW_IP"; then
  log "[Lazarus] SSH did not become ready in time."
  exit 1
fi
log "[Lazarus] SSH ready."

stage "Inject Payload"
ssh -i "$BINANCE_KEY" -p "$BINANCE_PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  "${BINANCE_USER}@${NEW_IP}" "mkdir -p /home/ubuntu/KiDax/server /home/ubuntu/KiDax/infra/systemd /home/ubuntu/KiBot/server /home/ubuntu/KiBot/infra/systemd" >>"$LOG_FILE" 2>&1

scp -i "$BINANCE_KEY" -P "$BINANCE_PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  "$LOCAL_KIDAX_JAR" "${BINANCE_USER}@${NEW_IP}:/home/ubuntu/KiDax/server/mac-engine-all.jar" >>"$LOG_FILE" 2>&1
scp -i "$BINANCE_KEY" -P "$BINANCE_PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  "$LOCAL_KIBOT_JAR" "${BINANCE_USER}@${NEW_IP}:/home/ubuntu/KiBot/server/mac-engine-all.jar" >>"$LOG_FILE" 2>&1

stage "Restart Services"
ssh -i "$BINANCE_KEY" -p "$BINANCE_PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  "${BINANCE_USER}@${NEW_IP}" bash -s <<'REMOTE'
set -euo pipefail
sudo systemctl daemon-reload
sudo systemctl restart kidax-engine || true
sudo systemctl restart kibot-engine || true
REMOTE

stage "Verify Readiness"
if ! wait_for_state_ready "$NEW_IP" 8787; then
  log "[Lazarus] KiDax state did not become ready in time."
  exit 1
fi
if ! wait_for_state_ready "$NEW_IP" 8789; then
  log "[Lazarus] KiBot state did not become ready in time."
  exit 1
fi

log "[Lazarus] State verification passed for ports 8787 and 8789."
log "[Lazarus] Protocol Complete. Trinity Mesh is Online."
log "[Lazarus] Final report: KiDax+KiBot migrated to ${NEW_IP} and state checks passed."
cat > "${ROOT_DIR}/state/lazarus.status.json" <<EOF
{"status":"completed","host":"${NEW_IP}","timestamp":"$(date -u +%Y-%m-%dT%H:%M:%SZ)"}
EOF
log "[Lazarus] Ready-to-leave state: OCI wakeup, SSH injection, and service verification all passed."
