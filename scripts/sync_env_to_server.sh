#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_LOCAL="${ROOT_DIR}/.env"
SSH_KEY="${ROOT_DIR}/ssh-key-2026-03-22.key"
HOST="${KICRYP_SSH_HOST:-213.35.118.26}"
USER_NAME="${KICRYP_SSH_USER:-ubuntu}"
REMOTE_ENV="/home/ubuntu/KiDax/.env.kidax"
REMOTE_SERVICE="${KICRYP_REMOTE_SERVICE:-kidax-engine}"
REMOTE_RESTART_DELAY="${KICRYP_REMOTE_RESTART_DELAY:-4}"

if [[ ! -f "${ENV_LOCAL}" ]]; then
  echo "Missing local .env at ${ENV_LOCAL}" >&2
  exit 1
fi

if [[ ! -f "${SSH_KEY}" ]]; then
  echo "Missing SSH key at ${SSH_KEY}" >&2
  exit 1
fi

tmp_file="$(mktemp)"
trap 'rm -f "${tmp_file}"' EXIT

python3 - <<'PY' > "${tmp_file}"
from pathlib import Path

env_path = Path(".env")
keys = [
    "BOT_ID",
    "BOT_PROFILE_KEY",
    "MAC_ENGINE_BIND_HOST",
    "MAC_ENGINE_PORT",
    "MAC_ENGINE_LAN_SYNC_URL",
    "DEVICE_ID",
    "DEVICE_DISPLAY_NAME",
    "INDODAX_API_KEY",
    "INDODAX_API_SECRET",
    "BOT_ENABLE_LIVE_EXECUTION",
    "GEMINI_SUPPORT_API_KEY",
    "GEMINI_SUPPORT_MODEL",
    "OPENROUTER_API_KEY",
    "OPENROUTER_MODEL",
    "GROQ_API_KEY",
    "GROQ_MODEL",
    "COHERE_API_KEY",
    "COHERE_MODEL",
]
vals = {}
for line in env_path.read_text(encoding="utf-8").splitlines():
    s = line.strip()
    if not s or s.startswith("#") or "=" not in s:
        continue
    k, v = s.split("=", 1)
    vals[k.strip()] = v.strip().strip('"').strip("'")

for k in keys:
    v = vals.get(k)
    if v:
        safe = v.replace("\\", "\\\\").replace('"', '\\"')
        print(f'{k}="{safe}"')
PY

scp -i "${SSH_KEY}" "${tmp_file}" "${USER_NAME}@${HOST}:/tmp/kicryp-env-sync.tmp" >/dev/null

ssh -i "${SSH_KEY}" "${USER_NAME}@${HOST}" "
set -euo pipefail
touch '${REMOTE_ENV}'
while IFS= read -r line; do
  key=\${line%%=*}
  sed -i \"/^\${key}=.*/d\" '${REMOTE_ENV}'
  printf '%s\n' \"\$line\" >> '${REMOTE_ENV}'
done < /tmp/kicryp-env-sync.tmp
rm -f /tmp/kicryp-env-sync.tmp
sudo systemctl daemon-reload
sudo systemctl restart '${REMOTE_SERVICE}'
sleep '${REMOTE_RESTART_DELAY}'
systemctl is-active '${REMOTE_SERVICE}'
"

echo "Synced env keys and restarted kidax-engine."
