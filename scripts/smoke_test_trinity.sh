#!/usr/bin/env bash
set -euo pipefail

KIDAX_URL="${KIDAX_URL:-http://127.0.0.1:8787}"
KINANCE_URL="${KINANCE_URL:-http://127.0.0.1:8788}"
KIBOT_URL="${KIBOT_URL:-http://127.0.0.1:8789}"
CURL_BIN="${CURL_BIN:-curl}"
SSH_HOST="${SSH_HOST:-}"
SSH_KEY="${SSH_KEY:-}"

fetch_state() {
  local name="$1"
  local url="$2"
  local tmp_file
  tmp_file="$(mktemp)"
  if ! "$CURL_BIN" -fsS --retry 5 --retry-delay 2 "$url/api/state" >"$tmp_file"; then
    echo "[FAIL] $name tidak merespons di $url/api/state"
    rm -f "$tmp_file"
    return 1
  fi
  python3 - "$name" "$tmp_file" <<'PY'
import json
import pathlib
import sys

name = sys.argv[1]
path = pathlib.Path(sys.argv[2])
data = json.loads(path.read_text())
effective = data.get("effectiveState", "UNKNOWN")
status = data.get("statusMessage", "-")
active = data.get("activeEngine", "-")
sync = data.get("syncHealth", "-")
print(f"[OK] {name}: effectiveState={effective} syncHealth={sync} activeEngine={active}")
print(f"     status={status}")
PY
  rm -f "$tmp_file"
}

check_service() {
  local name="$1"
  if [[ -n "$SSH_HOST" && -n "$SSH_KEY" ]]; then
    ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new "ubuntu@${SSH_HOST}" "systemctl is-active ${name}" 2>/dev/null || echo "inactive"
  fi
}

echo "== Trinity Smoke Test =="
fetch_state "KiDax" "$KIDAX_URL"
fetch_state "Kinance" "$KINANCE_URL"
fetch_state "KiBot" "$KIBOT_URL"
if [[ -n "$SSH_HOST" && -n "$SSH_KEY" ]]; then
  echo "== Service Status =="
  echo "kidax-engine: $(check_service kidax-engine)"
  echo "kinance-engine: $(check_service kinance-engine)"
  echo "kibot-engine: $(check_service kibot-engine)"
fi
echo "== Smoke test selesai =="
