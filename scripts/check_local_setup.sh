#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_ENV="${ROOT_DIR}/.env"
LOCAL_PROPERTIES="${ROOT_DIR}/local.properties"
KEYSTORE_ENV="${ROOT_DIR}/.secrets/android-keystore.env"
PASSFILE="${ROOT_DIR}/.secrets/e2ee-passphrase.txt"
SDK_DIR=""

if [[ -f "${LOCAL_PROPERTIES}" ]]; then
  SDK_DIR="$(sed -n 's/^sdk.dir=//p' "${LOCAL_PROPERTIES}" | head -n 1)"
fi

status() {
  local label="$1"
  local value="$2"
  printf '%-28s %s\n' "${label}" "${value}"
}

supabase_auth_status() {
  if ! [[ -f "${ROOT_ENV}" ]]; then
    echo "MISSING"
    return
  fi

  local raw
  raw="$(python3 "${ROOT_DIR}/scripts/check_supabase_auth.py" 2>/dev/null || true)"
  case "${raw}" in
    *'"status": "ready"'*) echo "READY" ;;
    *'"status": "pending_confirmation"'*) echo "PENDING_CONFIRMATION" ;;
    *'"status": "authenticated_but_control_plane_error"'*) echo "AUTH_OK_CONTROL_PLANE_FAIL" ;;
    *'"status": "auth_error"'*) echo "AUTH_ERROR" ;;
    *'"status": "missing_config"'*) echo "PENDING" ;;
    *) echo "UNKNOWN" ;;
  esac
}

supabase_control_plane_status() {
  if ! [[ -f "${ROOT_ENV}" ]]; then
    echo "MISSING"
    return
  fi

  local raw
  raw="$(python3 "${ROOT_DIR}/scripts/check_supabase_control_plane.py" 2>/dev/null || true)"
  case "${raw}" in
    *'"status": "ready"'*) echo "READY" ;;
    *'"status": "missing_tables"'*) echo "MISSING_TABLES" ;;
    *'"status": "auth_error"'*) echo "AUTH_ERROR" ;;
    *'"status": "missing_config"'*) echo "PENDING" ;;
    *) echo "UNKNOWN" ;;
  esac
}

status ".env" "$( [[ -f "${ROOT_ENV}" ]] && echo OK || echo MISSING )"
status "apps/mac-engine/.env" "$( [[ -f "${ROOT_DIR}/apps/mac-engine/.env" ]] && echo OK || echo MISSING )"
status "local.properties" "$( [[ -f "${LOCAL_PROPERTIES}" ]] && echo OK || echo MISSING )"
status "E2EE passphrase" "$( [[ -f "${PASSFILE}" ]] && echo OK || echo MISSING )"
status "Android keystore" "$( [[ -f "${KEYSTORE_ENV}" ]] && echo OK || echo MISSING )"
if command -v adb >/dev/null 2>&1; then
  status "adb" "OK"
elif [[ -n "${SDK_DIR}" && -x "${SDK_DIR}/platform-tools/adb" ]]; then
  status "adb" "OK"
else
  status "adb" "MISSING"
fi

if [[ -f "${ROOT_ENV}" ]]; then
  if grep -q "__SET_OWNER_EMAIL__" "${ROOT_ENV}"; then
    status "Supabase owner email" "PENDING"
  else
    status "Supabase owner email" "SET"
  fi
  status "Supabase auth status" "$(supabase_auth_status)"
  status "Supabase control-plane" "$(supabase_control_plane_status)"

  if grep -q "^INDODAX_API_KEY=replace-with-" "${ROOT_ENV}" || grep -q "^INDODAX_API_SECRET=replace-with-" "${ROOT_ENV}"; then
    status "Indodax live keypair" "PENDING"
  else
    status "Indodax live keypair" "SET"
  fi
fi
