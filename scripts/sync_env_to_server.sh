#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_LOCAL="${ROOT_DIR}/.env"
SSH_KEY="${ROOT_DIR}/ssh-key-2026-03-22.key"
HOST="${KIBOT_SSH_HOST:-213.35.118.26}"
USER_NAME="${KIBOT_SSH_USER:-ubuntu}"
REMOTE_ENV="/home/ubuntu/KiBot/.env.server"

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

scp -i "${SSH_KEY}" "${tmp_file}" "${USER_NAME}@${HOST}:/tmp/kibot-env-sync.tmp" >/dev/null

ssh -i "${SSH_KEY}" "${USER_NAME}@${HOST}" "
set -euo pipefail
touch '${REMOTE_ENV}'
while IFS= read -r line; do
  key=\${line%%=*}
  sed -i \"/^\${key}=.*/d\" '${REMOTE_ENV}'
  printf '%s\n' \"\$line\" >> '${REMOTE_ENV}'
done < /tmp/kibot-env-sync.tmp
rm -f /tmp/kibot-env-sync.tmp
sudo systemctl restart kibot-engine
sleep 4
systemctl is-active kibot-engine
"

echo "Synced env keys and restarted kibot-engine."
