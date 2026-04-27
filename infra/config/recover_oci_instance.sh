#!/usr/bin/env bash
set -euo pipefail

INSTANCE_OCID="${1:-${INSTANCE_OCID:-}}"
OCI_BIN="${OCI_BIN:-oci}"
PROFILE="${OCI_PROFILE:-DEFAULT}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-5}"
BASE_DELAY_SECONDS="${BASE_DELAY_SECONDS:-10}"
MAX_DELAY_SECONDS="${MAX_DELAY_SECONDS:-300}"

usage() {
  cat <<'EOF'
Usage:
  recover_oci_instance.sh <instance-ocid>

Environment overrides:
  OCI_BIN=oci
  OCI_PROFILE=DEFAULT
  MAX_ATTEMPTS=5
  BASE_DELAY_SECONDS=10
  MAX_DELAY_SECONDS=300
EOF
}

if [[ -z "${INSTANCE_OCID}" ]]; then
  usage
  exit 1
fi

if ! command -v "${OCI_BIN}" >/dev/null 2>&1; then
  echo "[FAIL] OCI CLI tidak ditemukan: ${OCI_BIN}"
  exit 1
fi

get_state() {
  "${OCI_BIN}" compute instance get \
    --instance-id "${INSTANCE_OCID}" \
    --profile "${PROFILE}" \
    --query 'data."lifecycle-state"' \
    --raw-output 2>/dev/null || true
}

start_instance() {
  "${OCI_BIN}" compute instance action \
    --instance-id "${INSTANCE_OCID}" \
    --action START \
    --profile "${PROFILE}" 2>&1 || true
}

sleep_backoff() {
  local attempt="$1"
  local delay=$((BASE_DELAY_SECONDS * (1 << (attempt - 1))))
  if (( delay > MAX_DELAY_SECONDS )); then
    delay="${MAX_DELAY_SECONDS}"
  fi
  echo "[WAIT] Menunggu ${delay}s sebelum retry berikutnya..."
  sleep "${delay}"
}

echo "== KiBot OCI Recovery =="
echo "Instance: ${INSTANCE_OCID}"
echo "Profile : ${PROFILE}"
echo

for attempt in $(seq 1 "${MAX_ATTEMPTS}"); do
  state="$(get_state)"
  if [[ "${state}" == "RUNNING" ]]; then
    echo "[OK] Instance sudah RUNNING."
    exit 0
  fi

  if [[ "${state}" == "STARTING" || "${state}" == "STOPPING" || "${state}" == "STOPPED" || -z "${state}" ]]; then
    echo "[INFO] State saat ini: ${state:-UNKNOWN}. Coba START (attempt ${attempt}/${MAX_ATTEMPTS})..."
    response="$(start_instance)"
    echo "${response}"
  else
    echo "[INFO] State saat ini: ${state}. Coba START (attempt ${attempt}/${MAX_ATTEMPTS})..."
    response="$(start_instance)"
    echo "${response}"
  fi

  if echo "${response}" | grep -qiE 'Out of host capacity|capacity'; then
    echo "[WARN] Oracle masih penuh / out of host capacity."
  fi

  sleep 15
  state="$(get_state)"
  echo "[INFO] State setelah trigger: ${state:-UNKNOWN}"
  if [[ "${state}" == "RUNNING" ]]; then
    echo "[OK] Instance berhasil naik."
    exit 0
  fi

  if [[ "${state}" == "TERMINATED" ]]; then
    echo "[FAIL] Instance TERMINATED. Tidak bisa recovery otomatis."
    exit 2
  fi

  if (( attempt < MAX_ATTEMPTS )); then
    sleep_backoff "${attempt}"
  fi
done

echo "[FAIL] Gagal membuat instance RUNNING setelah ${MAX_ATTEMPTS} percobaan."
echo "        Coba cek kapasitas OCI, shape/AD, atau start manual dari Console."
exit 3
