#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

REPO_SLUG="${REPO_SLUG:-frahmat68-beep/Kibot}"
BRANCH_NAME="${BRANCH_NAME:-main}"
RUN_LIMIT="${RUN_LIMIT:-6}"
LOG_WINDOW="${LOG_WINDOW:-15 minutes ago}"

INDODAX_HOST="${INDODAX_HOST:-213.35.118.26}"
BINANCE_HOST="${BINANCE_HOST:-152.69.218.198}"
INDODAX_KEY="${INDODAX_KEY:-${ROOT_DIR}/SSH_INDODAX/ssh-key-2026-03-22.key}"
BINANCE_KEY="${BINANCE_KEY:-${ROOT_DIR}/SSH_BINANCE/ssh-key-2026-03-27.key}"

ssh_run() {
  local host="$1"
  local key="$2"
  shift 2
  ssh -i "$key" \
    -o BatchMode=yes \
    -o ConnectTimeout=10 \
    -o ServerAliveInterval=5 \
    -o ServerAliveCountMax=2 \
    -o StrictHostKeyChecking=accept-new \
    "ubuntu@${host}" "$@"
}

safe_ssh_section() {
  local title="$1"
  local host="$2"
  local key="$3"
  local script="$4"
  print_header "$title"
  if ! ssh_run "$host" "$key" "$script"; then
    echo "host=${host}"
    echo "status=unreachable"
  fi
}

print_header() {
  echo
  echo "=== $1 ==="
}

require_bin() {
  local name="$1"
  command -v "$name" >/dev/null 2>&1 || {
    echo "Missing required command: $name" >&2
    exit 1
  }
}

require_bin gh
require_bin ssh
require_bin python3

print_header "GitHub Auth"
gh auth status

print_header "Recent Workflow Runs"
runs_json="$(gh run list \
  --repo "$REPO_SLUG" \
  --branch "$BRANCH_NAME" \
  --limit "$RUN_LIMIT" \
  --json databaseId,workflowName,displayTitle,status,conclusion,headSha,updatedAt,url)"
RUNS_JSON="$runs_json" python3 - <<'PY'
import json
import os
import sys

runs = json.loads(os.environ["RUNS_JSON"])
for run in runs:
    print(
        f"{run['workflowName']:<16} "
        f"run={run['databaseId']} "
        f"status={run['status']} "
        f"conclusion={run.get('conclusion') or '-'} "
        f"sha={run['headSha'][:7]} "
        f"updated={run['updatedAt']}"
    )
    print(f"  {run['displayTitle']}")
    print(f"  {run['url']}")
PY

safe_ssh_section "KiDax" "$INDODAX_HOST" "$INDODAX_KEY" "
set -e
printf 'host=%s\n' '$INDODAX_HOST'
printf 'service=%s\n' \"\$(systemctl is-active kidax-engine)\"
printf 'failed_units=%s\n' \"\$(systemctl --failed --no-legend | wc -l | tr -d ' ')\"
printf 'api_state=%s\n' \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:8787/api/state || true)\"
printf 'api_health=%s\n' \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:8787/api/health || true)\"
printf 'jar_sha=%s\n' \"\$(sha256sum /home/ubuntu/KiDax/server/mac-engine-all.jar | cut -d' ' -f1)\"
printf 'warn_cp_12s=%s\n' \"\$(journalctl -u kidax-engine --since '$LOG_WINDOW' --no-pager | grep -c 'Timed out waiting for 12000 ms' || true)\"
printf 'warn_oom=%s\n' \"\$(journalctl -u kidax-engine --since '$LOG_WINDOW' --no-pager | grep -c 'Failed to parse OOM policy' || true)\"
printf 'warn_health_500=%s\n' \"\$(journalctl -u kidax-engine --since '$LOG_WINDOW' --no-pager | grep -c '500 Internal Server Error: GET - /api/health' || true)\"
"

safe_ssh_section "Kinance" "$BINANCE_HOST" "$BINANCE_KEY" "
set -e
printf 'host=%s\n' '$BINANCE_HOST'
printf 'kinance_service=%s\n' \"\$(systemctl is-active kinance-engine)\"
printf 'kibot_service=%s\n' \"\$(systemctl is-active kibot-engine)\"
printf 'manager_service=%s\n' \"\$(systemctl is-active kibot-manager)\"
printf 'failed_units=%s\n' \"\$(systemctl --failed --no-legend | wc -l | tr -d ' ')\"
printf 'api_state=%s\n' \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 http://127.0.0.1:8788/api/state || true)\"
printf 'api_health=%s\n' \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 http://127.0.0.1:8788/api/health || true)\"
printf 'jar_sha=%s\n' \"\$(sha256sum /home/ubuntu/Kinance/server/mac-engine-all.jar | cut -d' ' -f1)\"
printf 'warn_cp_12s=%s\n' \"\$(journalctl -u kinance-engine --since '$LOG_WINDOW' --no-pager | grep -c 'Timed out waiting for 12000 ms' || true)\"
printf 'warn_stopped=%s\n' \"\$(journalctl -u kinance-engine --since '$LOG_WINDOW' --no-pager | grep -c 'LIFECYCLE_BLOCK: Cannot start sync cycle' || true)\"
printf 'warn_json=%s\n' \"\$(journalctl -u kinance-engine --since '$LOG_WINDOW' --no-pager | grep -c 'Ignoring malformed dashboard JSON file' || true)\"
printf 'warn_health_500=%s\n' \"\$(journalctl -u kinance-engine --since '$LOG_WINDOW' --no-pager | grep -c '500 Internal Server Error: GET - /api/health' || true)\"
"

print_header "Summary"
echo "Audit selesai. Kalau semua target utama sehat, yang ideal adalah:"
echo "- workflow deploy terbaru conclusion=success"
echo "- api_state=200 dan api_health=200"
echo "- failed_units=0"
echo "- warn_cp_12s=0"
echo "- warn_stopped=0"
