#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BINANCE_HOST="${BINANCE_HOST:-152.69.218.198}"
BINANCE_USER="${BINANCE_USER:-ubuntu}"
BINANCE_PORT="${BINANCE_PORT:-22}"
BINANCE_KEY="${BINANCE_KEY:-${ROOT_DIR}/SSH_BINANCE/ssh-key-2026-03-27.key}"
GRADLEW="${ROOT_DIR}/gradlew"
TEMP_DIR="$(mktemp -d)"
REMOTE_TMP_DIR="/tmp/kibot-deploy-$$"
trap 'rm -rf "${TEMP_DIR}"' EXIT

KIDAX_ROOT="/home/ubuntu/KiDax"
KIBOT_ROOT="/home/ubuntu/KiBot"
KIDAX_PORT="8787"
KIBOT_PORT="8789"

if [[ ! -x "${GRADLEW}" ]]; then
  echo "[FAIL] gradlew tidak ditemukan di ${GRADLEW}"
  exit 1
fi
if [[ ! -f "${BINANCE_KEY}" ]]; then
  echo "[FAIL] SSH key tidak ditemukan di ${BINANCE_KEY}"
  exit 1
fi

build_env_file() {
  local mode="$1"
  local out_file="$2"
  python3 - "$ROOT_DIR/.env" "$mode" > "$out_file" <<'PY'
from pathlib import Path
import sys

env_path = Path(sys.argv[1])
mode = sys.argv[2]
values = {}
for line in env_path.read_text(encoding="utf-8").splitlines():
    s = line.strip()
    if not s or s.startswith("#") or "=" not in s:
        continue
    k, v = s.split("=", 1)
    values[k.strip()] = v.strip().strip('"').strip("'")

common_prefixes = ("SUPABASE_", "BOT_", "GEMINI_", "OPENROUTER_", "GROQ_", "COHERE_")
exchange_prefixes = ("INDODAX_", "BINANCE_")

keys = []
if mode == "kidax":
    keys = [k for k in values if k.startswith(common_prefixes) or k.startswith(exchange_prefixes)]
    keys += ["BOT_ENABLE_LIVE_EXECUTION", "SHADOW_MODE", "KIBOT_EXCHANGE_KIND"]
elif mode == "kibot":
    keys = [k for k in values if k.startswith(common_prefixes) or k.startswith("BINANCE_")]
    keys += ["BOT_ENABLE_LIVE_EXECUTION", "SHADOW_MODE"]
else:
    raise SystemExit(f"unknown mode: {mode}")

seen = set()
for key in keys:
    if key in seen:
        continue
    seen.add(key)
    value = values.get(key)
    if value is None:
        continue
    safe = value.replace("\\", "\\\\").replace('"', '\\"')
    print(f'{key}="{safe}"')

print('BOT_ENABLE_LIVE_EXECUTION="true"')
if mode == "kidax":
    print('SHADOW_MODE="false"')
    print('KIBOT_EXCHANGE_KIND="INDODAX"')
    print('KIBOT_LEAD_LAG_UDP_LISTEN_PORT="9997"')
    print('KIBOT_LEAD_LAG_UDP_TARGET_PORT="9998"')
    print('KIBOT_HIVE_UDP_PEERS="127.0.0.1:9999"')
    print('KIBOT_HIVE_EXPECTED_BOT_IDS="kinance,kibot"')
elif mode == "kibot":
    print('SHADOW_MODE="false"')
    print('KIBOT_EXPECT_LIVE_EXECUTION="true"')
    print('KIBOT_LEAD_LAG_UDP_ENABLED="true"')
    print('KIBOT_LEAD_LAG_UDP_LISTEN_PORT="9999"')
    print('KIBOT_LEAD_LAG_UDP_TARGET_PORT="9998"')
    print('KIBOT_HIVE_UDP_PEERS="127.0.0.1:9997"')
    print('KIBOT_HIVE_EXPECTED_BOT_IDS="kinance,kidax"')
PY
}

ensure_supabase_bot_row() {
  python3 - "$ROOT_DIR/.env" <<'PY'
from pathlib import Path
import json
import sys
import urllib.request

env_path = Path(sys.argv[1])
values = {}
for line in env_path.read_text(encoding="utf-8").splitlines():
    s = line.strip()
    if not s or s.startswith("#") or "=" not in s:
        continue
    k, v = s.split("=", 1)
    values[k.strip()] = v.strip().strip('"').strip("'")

required = ("SUPABASE_URL", "SUPABASE_ANON_KEY", "SUPABASE_USER_EMAIL", "SUPABASE_USER_PASSWORD")
missing = [k for k in required if not values.get(k)]
if missing:
    raise SystemExit(f"Missing env keys: {', '.join(missing)}")

url = values["SUPABASE_URL"].rstrip("/")
login_body = json.dumps({
    "email": values["SUPABASE_USER_EMAIL"],
    "password": values["SUPABASE_USER_PASSWORD"],
}).encode()
login_req = urllib.request.Request(
    f"{url}/auth/v1/token?grant_type=password",
    data=login_body,
    headers={
        "apikey": values["SUPABASE_ANON_KEY"],
        "Content-Type": "application/json",
        "Accept": "application/json",
    },
    method="POST",
)
with urllib.request.urlopen(login_req, timeout=20) as response:
    auth = json.load(response)

token = auth["access_token"]
user_id = auth["user"]["id"]
query_req = urllib.request.Request(
    f"{url}/rest/v1/bots?bot_id=eq.kibot&select=bot_id",
    headers={
        "apikey": values["SUPABASE_ANON_KEY"],
        "Authorization": f"Bearer {token}",
        "Accept": "application/json",
    },
)
with urllib.request.urlopen(query_req, timeout=20) as response:
    existing = json.load(response)

if existing:
    print("[SUPABASE] kibot bot row already exists")
    raise SystemExit(0)

payload = json.dumps({
    "bot_id": "kibot",
    "user_id": user_id,
    "display_name": "KiBot Commander",
}).encode()
insert_req = urllib.request.Request(
    f"{url}/rest/v1/bots",
    data=payload,
    headers={
        "apikey": values["SUPABASE_ANON_KEY"],
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Prefer": "return=representation",
        "Accept": "application/json",
    },
    method="POST",
)
with urllib.request.urlopen(insert_req, timeout=20) as response:
    print(response.read().decode())
PY
}

echo "[1/5] Build mac-engine fat jar"
./gradlew --no-daemon :apps:mac-engine:fatJar

echo "[1b/5] Ensure kibot bot row exists in Supabase"
ensure_supabase_bot_row

JAR_PATH="${ROOT_DIR}/apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar"
if [[ ! -f "${JAR_PATH}" ]]; then
  echo "[FAIL] Jar tidak ditemukan: ${JAR_PATH}"
  exit 1
fi

KIDAX_ENV="${TEMP_DIR}/.env.kidax"
KIBOT_ENV="${TEMP_DIR}/.env.kibot"
build_env_file kidax "${KIDAX_ENV}"
build_env_file kibot "${KIBOT_ENV}"

echo "[2/5] Prepare remote directories"
ssh -i "${BINANCE_KEY}" -p "${BINANCE_PORT}" -o StrictHostKeyChecking=accept-new "${BINANCE_USER}@${BINANCE_HOST}" "mkdir -p '${REMOTE_TMP_DIR}' '${KIDAX_ROOT}/server' '${KIDAX_ROOT}/infra/systemd' '${KIBOT_ROOT}/server' '${KIBOT_ROOT}/infra/systemd'"

echo "[3/5] Transfer jar, service files, and recovery scripts"
scp -i "${BINANCE_KEY}" -P "${BINANCE_PORT}" -o StrictHostKeyChecking=accept-new \
  "${JAR_PATH}" "${BINANCE_USER}@${BINANCE_HOST}:${REMOTE_TMP_DIR}/mac-engine-all.jar"
scp -i "${BINANCE_KEY}" -P "${BINANCE_PORT}" -o StrictHostKeyChecking=accept-new \
  "${ROOT_DIR}/infra/systemd/kidax-engine.service" "${BINANCE_USER}@${BINANCE_HOST}:${REMOTE_TMP_DIR}/kidax-engine.service"
scp -i "${BINANCE_KEY}" -P "${BINANCE_PORT}" -o StrictHostKeyChecking=accept-new \
  "${ROOT_DIR}/infra/systemd/kibot-engine.service" "${BINANCE_USER}@${BINANCE_HOST}:${REMOTE_TMP_DIR}/kibot-engine.service"
scp -i "${BINANCE_KEY}" -P "${BINANCE_PORT}" -o StrictHostKeyChecking=accept-new \
  "${ROOT_DIR}/infra/scripts/engine-recovery.sh" "${BINANCE_USER}@${BINANCE_HOST}:${REMOTE_TMP_DIR}/engine-recovery.sh"
scp -i "${BINANCE_KEY}" -P "${BINANCE_PORT}" -o StrictHostKeyChecking=accept-new \
  "${ROOT_DIR}/infra/scripts/setup-engine-autorecover.sh" "${BINANCE_USER}@${BINANCE_HOST}:${REMOTE_TMP_DIR}/setup-engine-autorecover.sh"
scp -i "${BINANCE_KEY}" -P "${BINANCE_PORT}" -o StrictHostKeyChecking=accept-new \
  "${KIDAX_ENV}" "${BINANCE_USER}@${BINANCE_HOST}:${REMOTE_TMP_DIR}/env.kidax"
scp -i "${BINANCE_KEY}" -P "${BINANCE_PORT}" -o StrictHostKeyChecking=accept-new \
  "${KIBOT_ENV}" "${BINANCE_USER}@${BINANCE_HOST}:${REMOTE_TMP_DIR}/env.kibot"

echo "[4/5] Install and start KiDax + KiBot on Binance host"
ssh -i "${BINANCE_KEY}" -p "${BINANCE_PORT}" -o StrictHostKeyChecking=accept-new "${BINANCE_USER}@${BINANCE_HOST}" bash -s <<REMOTE
set -euo pipefail
install -m 0644 "${REMOTE_TMP_DIR}/mac-engine-all.jar" "${KIDAX_ROOT}/server/mac-engine-all.jar"
install -m 0644 "${REMOTE_TMP_DIR}/mac-engine-all.jar" "${KIBOT_ROOT}/server/mac-engine-all.jar"
install -m 0644 "${REMOTE_TMP_DIR}/kidax-engine.service" "${KIDAX_ROOT}/infra/systemd/kidax-engine.service"
install -m 0644 "${REMOTE_TMP_DIR}/kibot-engine.service" "${KIBOT_ROOT}/infra/systemd/kibot-engine.service"
install -m 0644 "${REMOTE_TMP_DIR}/engine-recovery.sh" "${KIDAX_ROOT}/engine-recovery.sh"
install -m 0644 "${REMOTE_TMP_DIR}/engine-recovery.sh" "${KIBOT_ROOT}/engine-recovery.sh"
install -m 0600 "${REMOTE_TMP_DIR}/env.kidax" "${KIDAX_ROOT}/.env.kidax"
install -m 0600 "${REMOTE_TMP_DIR}/env.kibot" "${KIBOT_ROOT}/.env.kibot"
install -m 0755 "${REMOTE_TMP_DIR}/setup-engine-autorecover.sh" "${KIDAX_ROOT}/setup-autorecover.sh"
install -m 0755 "${REMOTE_TMP_DIR}/setup-engine-autorecover.sh" "${KIBOT_ROOT}/setup-autorecover.sh"

KIBOT_RUNTIME_ROOT="${KIDAX_ROOT}" \
KIBOT_SERVICE_NAME="kidax-engine" \
KIBOT_DASHBOARD_PORT="${KIDAX_PORT}" \
KIBOT_ENV_FILE="${KIDAX_ROOT}/.env.kidax" \
KIBOT_RECOVERY_SCRIPT_PATH="${KIDAX_ROOT}/engine-recovery.sh" \
KIBOT_SERVICE_FILE_PATH="${KIDAX_ROOT}/infra/systemd/kidax-engine.service" \
KIBOT_AI_SCRIPT_PATH="${KIDAX_ROOT}/scripts/ai_learning_cycle.sh" \
bash "${KIDAX_ROOT}/setup-autorecover.sh"

KIBOT_RUNTIME_ROOT="${KIBOT_ROOT}" \
KIBOT_SERVICE_NAME="kibot-engine" \
KIBOT_DASHBOARD_PORT="${KIBOT_PORT}" \
KIBOT_ENV_FILE="${KIBOT_ROOT}/.env.kibot" \
KIBOT_RECOVERY_SCRIPT_PATH="${KIBOT_ROOT}/engine-recovery.sh" \
KIBOT_SERVICE_FILE_PATH="${KIBOT_ROOT}/infra/systemd/kibot-engine.service" \
KIBOT_AI_SCRIPT_PATH="${KIBOT_ROOT}/scripts/ai_learning_cycle.sh" \
bash "${KIBOT_ROOT}/setup-autorecover.sh"

sudo systemctl daemon-reload
sudo systemctl restart kidax-engine
sudo systemctl restart kibot-engine
sleep 15
sudo systemctl is-active --quiet kidax-engine
sudo systemctl is-active --quiet kibot-engine
curl -fsS --retry 10 --retry-delay 2 --retry-all-errors "http://127.0.0.1:${KIDAX_PORT}/api/state" >/tmp/kidax-state.json
curl -fsS --retry 10 --retry-delay 2 --retry-all-errors "http://127.0.0.1:${KIBOT_PORT}/api/state" >/tmp/kibot-state.json
echo "KiDax state: $(head -c 220 /tmp/kidax-state.json)"
echo "KiBot state: $(head -c 220 /tmp/kibot-state.json)"
REMOTE

echo "[5/5] Deploy selesai."
