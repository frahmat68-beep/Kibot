#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRETS_DIR="${ROOT_DIR}/.secrets"
ROOT_ENV="${ROOT_DIR}/.env"
MAC_ENV="${ROOT_DIR}/apps/mac-engine/.env"
PASSFILE="${SECRETS_DIR}/e2ee-passphrase.txt"
REPORT_FILE="${SECRETS_DIR}/bootstrap-report.txt"

mkdir -p "${SECRETS_DIR}" "$(dirname "${MAC_ENV}")"

random_secret() {
  python3 - <<'PY'
import secrets
print(secrets.token_urlsafe(24))
PY
}

word_passphrase() {
  python3 - <<'PY'
import secrets
words = [
    "angin", "atlas", "bambu", "cahaya", "danau", "embun", "faset", "garis",
    "haluan", "intan", "jarum", "kabut", "lampu", "layar", "nadi", "ombak",
    "pohon", "radar", "sumbu", "tangga", "teluk", "ujung", "warna", "zirah",
]
print("-".join(secrets.choice(words) for _ in range(6)))
PY
}

ensure_local_env() {
  local supabase_url="${KIBOT_SUPABASE_URL:-https://your-project.supabase.co}"
  local supabase_anon_key="${KIBOT_SUPABASE_ANON_KEY:-your-publishable-or-anon-key}"
  local supabase_user_email="${KIBOT_SUPABASE_USER_EMAIL:-__SET_OWNER_EMAIL__}"
  local supabase_user_password="${KIBOT_SUPABASE_USER_PASSWORD:-$(random_secret)}"
  local device_display_name="${KIBOT_DEVICE_DISPLAY_NAME:-Android Device}"
  local indodax_api_key="${KIBOT_INDODAX_API_KEY:-replace-with-trade-only-api-key}"
  local indodax_api_secret="${KIBOT_INDODAX_API_SECRET:-replace-with-trade-only-api-secret}"
  local e2ee_passphrase

  if [[ -f "${PASSFILE}" ]]; then
    e2ee_passphrase="$(<"${PASSFILE}")"
  else
    e2ee_passphrase="$(word_passphrase)"
    printf '%s\n' "${e2ee_passphrase}" > "${PASSFILE}"
    chmod 600 "${PASSFILE}"
  fi

  cat > "${ROOT_ENV}" <<EOF
SUPABASE_URL=${supabase_url}
SUPABASE_ANON_KEY=${supabase_anon_key}
SUPABASE_USER_EMAIL=${supabase_user_email}
SUPABASE_USER_PASSWORD=${supabase_user_password}
BOT_ID=main
BOT_PROFILE_KEY=indodax
MAC_ENGINE_BIND_HOST=0.0.0.0
MAC_ENGINE_PORT=8787
MAC_ENGINE_LAN_SYNC_URL=http://127.0.0.1:8787
BOT_POLL_INTERVAL_MS=2000
MAC_DASHBOARD_STATE_POLL_INTERVAL_MS=2000
MAC_DASHBOARD_LOG_POLL_INTERVAL_MS=5000
DEVICE_DISPLAY_NAME=${device_display_name}
INDODAX_PUBLIC_BASE_URL=https://indodax.com/api
INDODAX_PRIVATE_BASE_URL=https://indodax.com/tapi
INDODAX_WS_PUBLIC_URL=wss://ws1.indodax.com/ws
INDODAX_WS_PRIVATE_URL=wss://ws1.indodax.com/ws/private
INDODAX_TRADE_API_V2_BASE_URL=https://tapi.indodax.com
INDODAX_API_KEY=${indodax_api_key}
INDODAX_API_SECRET=${indodax_api_secret}
BOT_DEFAULT_TIMEZONE=Asia/Jakarta
BOT_DEFAULT_LEASE_TTL_SECONDS=30
APP_E2EE_PASSPHRASE=${e2ee_passphrase}
EOF
  chmod 600 "${ROOT_ENV}"

  cat > "${MAC_ENV}" <<'EOF'
MAC_ENGINE_PORT=8787
MAC_ENGINE_BIND_HOST=0.0.0.0
MAC_ENGINE_LAN_SYNC_URL=http://127.0.0.1:8787
BOT_ID=main
BOT_PROFILE_KEY=indodax
BOT_POLL_INTERVAL_MS=2000
MAC_DASHBOARD_STATE_POLL_INTERVAL_MS=2000
MAC_DASHBOARD_LOG_POLL_INTERVAL_MS=5000
DEVICE_ID=macbook-main
DEVICE_DISPLAY_NAME=MacBook Pro 2020
BOT_POLL_INTERVAL_MS=5000
BOT_DEFAULT_LEASE_TTL_SECONDS=30
EOF
  chmod 600 "${MAC_ENV}"

  cat > "${REPORT_FILE}" <<EOF
KiBot local bootstrap finished at $(date '+%Y-%m-%d %H:%M:%S %Z')

Created:
- ${ROOT_ENV}
- ${MAC_ENV}
- ${PASSFILE}

Still required before live auth works:
- Replace SUPABASE_USER_EMAIL in ${ROOT_ENV} with an email address you control.
- Create or confirm that user in Supabase Auth using the password already stored in ${ROOT_ENV}.

Notes:
- This script only writes local ignored files.
- Rotate Indodax credentials before production because the previous key was exposed in chat history.
EOF
  chmod 600 "${REPORT_FILE}"

  printf 'Local bootstrap finished. Report: %s\n' "${REPORT_FILE}"
}

ensure_local_env
