#!/usr/bin/env bash

set -Eeuo pipefail

###############################################################################
# PROJECT LAZARUS V2 - THE AMPERE INVASION
###############################################################################

COMPARTMENT_ID="${COMPARTMENT_ID:-}"
SUBNET_ID="${SUBNET_ID:-}"
AVAILABILITY_DOMAIN="${AVAILABILITY_DOMAIN:-}"
SSH_KEY_FILE="${SSH_KEY_FILE:-${HOME}/.ssh/lazarus_ampere.pub}"

OCI_CONFIG_FILE="${OCI_CONFIG_FILE:-${HOME}/.oci/config}"
OCI_PROFILE="${OCI_PROFILE:-SINGAPORE}"
REGION="ap-singapore-1"
SHAPE="VM.Standard.A1.Flex"
SHAPE_CONFIG='{"ocpus": 1, "memoryInGBs": 6}'
BOOT_VOLUME_SIZE_GB=50
INSTANCE_NAME_PREFIX="lazarus-ampere"
CAPACITY_RETRY_SECONDS=40
CAPACITY_RETRY_JITTER_MAX=12
CAPACITY_REPORT_RETRY_SECONDS=50
TIMEOUT_RETRY_SECONDS=45
TIMEOUT_RETRY_JITTER_MAX=15
RATE_LIMIT_BACKOFF_SEQUENCE=(70 140 210)
NORMAL_RETRY_SECONDS=45
COOLDOWN_SECONDS=60
IMAGE_CACHE_TTL_SECONDS=$((6 * 60 * 60))
STATE_DIR="${HOME}/ampere-hunt/state"
IMAGE_CACHE_FILE="${STATE_DIR}/arm_image_id.cache"
SHAPE_CONFIG_FILE="${STATE_DIR}/shape_config.json"
CAPACITY_AVAILABILITIES_FILE="${STATE_DIR}/capacity_shape_availabilities.json"
SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"

# Opsional: kalau diisi, script kirim notif Telegram saat sukses saja.
TELEGRAM_BOT_TOKEN="${KIBOT_TELEGRAM_BOT_TOKEN:-}"
TELEGRAM_CHAT_ID="${KIBOT_TELEGRAM_CHAT_ID:-}"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

log_err() {
  log "$@" >&2
}

sleep_normal_retry() {
  log "Jeda normal ${NORMAL_RETRY_SECONDS} detik sebelum retry berikutnya..."
  sleep "${NORMAL_RETRY_SECONDS}"
}

sleep_cooldown() {
  log "Cooldown ${COOLDOWN_SECONDS} detik sebelum retry berikutnya..."
  sleep "${COOLDOWN_SECONDS}"
}

notify_telegram() {
  local text="$1"
  if [[ -z "${TELEGRAM_BOT_TOKEN}" || -z "${TELEGRAM_CHAT_ID}" ]]; then
    return 0
  fi
  curl -sS -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
    -d "chat_id=${TELEGRAM_CHAT_ID}" \
    --data-urlencode "text=${text}" >/dev/null 2>&1 || true
}

fatal() {
  log "$1"
  exit 1
}

jitter_sleep() {
  local base_seconds="$1"
  local jitter_max="${2:-0}"
  local jitter=0
  if [[ "${jitter_max}" -gt 0 ]]; then
    jitter=$(( RANDOM % (jitter_max + 1) ))
  fi
  sleep $(( base_seconds + jitter ))
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fatal "Command '$1' tidak ditemukan."
}

require_non_empty() {
  local name="$1"
  local value="$2"
  [[ -n "${value}" ]] || fatal "Variabel ${name} belum diisi."
}

resolve_ssh_key_file() {
  if [[ -f "${SSH_KEY_FILE}" ]]; then
    return 0
  fi

  if [[ -f "${HOME}/.ssh/authorized_keys" ]]; then
    local candidate
    candidate="$(head -n 1 "${HOME}/.ssh/authorized_keys" | tr -d '\r')"
    if [[ -n "${candidate}" ]]; then
      mkdir -p "$(dirname "${SSH_KEY_FILE}")"
      printf '%s\n' "${candidate}" > "${SSH_KEY_FILE}"
      chmod 600 "${SSH_KEY_FILE}" || true
      log "Pakai SSH key dari authorized_keys: ${SSH_KEY_FILE}"
      return 0
    fi
  fi

  fatal "SSH public key tidak ditemukan: ${SSH_KEY_FILE}"
}

resolve_availability_domain() {
  if [[ -n "${AVAILABILITY_DOMAIN}" ]]; then
    printf '%s\n' "${AVAILABILITY_DOMAIN}"
    return 0
  fi

  local ad
  ad="$(
    oci --config-file "${OCI_CONFIG_FILE}" iam availability-domain list \
      --profile "${OCI_PROFILE}" \
      --compartment-id "${COMPARTMENT_ID}" \
      --output json | jq -r '.data[].name' | awk 'NF' | shuf -n 1
  )"

  [[ -n "${ad}" ]] || fatal "Gagal memilih availability domain."
  printf '%s\n' "${ad}"
}

main() {
  require_cmd oci
  require_cmd jq
  require_cmd curl

  require_non_empty "COMPARTMENT_ID" "${COMPARTMENT_ID}"
  require_non_empty "SUBNET_ID" "${SUBNET_ID}"

  resolve_ssh_key_file
  AVAILABILITY_DOMAIN="$(resolve_availability_domain)"

  export OCI_CLI_SUPPRESS_FILE_PERMISSIONS_WARNING=true
  export SUPPRESS_LABEL_WARNING=True

  mkdir -p "${STATE_DIR}"
  cat > "${SHAPE_CONFIG_FILE}" <<'EOF'
{"ocpus": 1, "memoryInGBs": 6}
EOF
  cat > "${CAPACITY_AVAILABILITIES_FILE}" <<'EOF'
[
  {
    "instanceShape": "VM.Standard.A1.Flex",
    "instanceShapeConfig": {
      "baselineOcpuUtilization": "BASELINE_1_1",
      "memoryInGBs": 6,
      "ocpus": 1
    }
  }
]
EOF

  fetch_arm_image_id() {
    oci --config-file "${OCI_CONFIG_FILE}" compute image list \
      --profile "${OCI_PROFILE}" \
      --compartment-id "${COMPARTMENT_ID}" \
      --operating-system "Canonical Ubuntu" \
      --operating-system-version "24.04" \
      --shape "${SHAPE}" \
      --sort-by TIMECREATED \
      --sort-order DESC \
      --query 'data[0].id' \
      --raw-output 2>/tmp/lazarus_ampere_image.err || true
  }

  check_capacity_status() {
    local capacity_json status
    capacity_json="$(
      oci --config-file "${OCI_CONFIG_FILE}" compute compute-capacity-report create \
        --profile "${OCI_PROFILE}" \
        --availability-domain "${AVAILABILITY_DOMAIN}" \
        --compartment-id "${COMPARTMENT_ID}" \
        --shape-availabilities "file://${CAPACITY_AVAILABILITIES_FILE}" \
        --output json 2>/tmp/lazarus_ampere_capacity.err || true
    )"

    status="$(printf '%s\n' "${capacity_json}" | jq -r '.data["shape-availabilities"][0]["availability-status"] // empty' 2>/dev/null || true)"
    if [[ -z "${status}" ]]; then
      CAPACITY_ERR="$(cat /tmp/lazarus_ampere_capacity.err 2>/dev/null || true)"
      log "Capacity report belum bisa dibaca, retry pelan. ${CAPACITY_ERR}"
      sleep_cooldown
      return 1
    fi

    if [[ "${status}" == "OUT_OF_HOST_CAPACITY" ]]; then
      log "Capacity report: OUT_OF_HOST_CAPACITY di ${AVAILABILITY_DOMAIN}. Tunggu ${CAPACITY_REPORT_RETRY_SECONDS} detik."
      sleep "${CAPACITY_REPORT_RETRY_SECONDS}"
      return 1
    fi

    log "Capacity report: ${status} di ${AVAILABILITY_DOMAIN}."
    return 0
  }

  load_cached_arm_image_id() {
    if [[ ! -f "${IMAGE_CACHE_FILE}" ]]; then
      return 1
    fi
    local cached_id cached_epoch now_epoch age
    cached_id="$(sed -n '1p' "${IMAGE_CACHE_FILE}" 2>/dev/null || true)"
    cached_epoch="$(sed -n '2p' "${IMAGE_CACHE_FILE}" 2>/dev/null || true)"
    [[ -n "${cached_id}" && -n "${cached_epoch}" ]] || return 1
    now_epoch="$(date +%s)"
    age=$(( now_epoch - cached_epoch ))
    if (( age > IMAGE_CACHE_TTL_SECONDS )); then
      return 1
    fi
    printf '%s\n' "${cached_id}"
  }

  refresh_arm_image_id() {
    log_err "Menjemput OCID Ubuntu 24.04 ARM terbaru dari server Oracle..."
    local fetched_id
    fetched_id="$(fetch_arm_image_id)"
    fetched_id="$(printf '%s\n' "${fetched_id}" | tail -n 1 | tr -d '\r')"
    if [[ -z "${fetched_id}" || "${fetched_id}" == "null" || ! "${fetched_id}" =~ ^ocid1\.image\. ]]; then
      IMAGE_ERR="$(cat /tmp/lazarus_ampere_image.err 2>/dev/null || true)"
      fatal "Gagal mengambil ARM image OCID. ${IMAGE_ERR}"
    fi
    printf '%s\n%s\n' "${fetched_id}" "$(date +%s)" > "${IMAGE_CACHE_FILE}"
    log_err "Berhasil! ARM Image OCID: ${fetched_id}"
    printf '%s\n' "${fetched_id}"
  }

  ARM_IMAGE_ID="$(load_cached_arm_image_id || true)"
  if [[ -n "${ARM_IMAGE_ID}" ]]; then
    log "Pakai ARM image cache: ${ARM_IMAGE_ID}"
  else
    ARM_IMAGE_ID="$(refresh_arm_image_id)"
  fi

  rate_limit_level=0

  while true; do
    now_epoch="$(date +%s)"
    if [[ -f "${IMAGE_CACHE_FILE}" ]]; then
      cached_epoch="$(sed -n '2p' "${IMAGE_CACHE_FILE}" 2>/dev/null || true)"
      if [[ -n "${cached_epoch}" ]] && (( now_epoch - cached_epoch > IMAGE_CACHE_TTL_SECONDS )); then
        ARM_IMAGE_ID="$(refresh_arm_image_id)"
      fi
    fi

    if ! check_capacity_status; then
      continue
    fi

    INSTANCE_NAME="${INSTANCE_NAME_PREFIX}-$(date +%Y%m%d-%H%M%S)"
    log "Mencoba membuat instance Ampere: ${INSTANCE_NAME} di ${AVAILABILITY_DOMAIN}"

    set +e
    LAUNCH_OUTPUT="$(oci --config-file "${OCI_CONFIG_FILE}" compute instance launch \
      --profile "${OCI_PROFILE}" \
      --region "${REGION}" \
      --compartment-id "${COMPARTMENT_ID}" \
      --availability-domain "${AVAILABILITY_DOMAIN}" \
      --display-name "${INSTANCE_NAME}" \
      --shape "${SHAPE}" \
      --shape-config "file://${SHAPE_CONFIG_FILE}" \
      --image-id "${ARM_IMAGE_ID}" \
      --boot-volume-size-in-gbs "${BOOT_VOLUME_SIZE_GB}" \
      --subnet-id "${SUBNET_ID}" \
      --assign-public-ip true \
      --ssh-authorized-keys-file "${SSH_KEY_FILE}" \
      2>&1)"
    LAUNCH_EXIT=$?
    set -e

    if grep -Eqi "TooManyRequests|429" <<<"${LAUNCH_OUTPUT}"; then
      rate_limit_level=$(( rate_limit_level + 1 ))
      if (( rate_limit_level > ${#RATE_LIMIT_BACKOFF_SEQUENCE[@]} )); then
        rate_limit_level=${#RATE_LIMIT_BACKOFF_SEQUENCE[@]}
      fi
      cooldown_seconds="${RATE_LIMIT_BACKOFF_SEQUENCE[$(( rate_limit_level - 1 ))]}"
      log "Kena Rate Limit OCI (429)! Cooling down ${cooldown_seconds} detik..."
      sleep_cooldown
      continue
    fi

    if grep -Eqi "Internal Server Error|HTTP 500|500" <<<"${LAUNCH_OUTPUT}"; then
      rate_limit_level=0
      log "OCI Internal Error (500), masuk cooldown..."
      sleep_cooldown
      continue
    fi

    if grep -qi "Out of host capacity" <<<"${LAUNCH_OUTPUT}"; then
      rate_limit_level=0
      log "Kapasitas penuh, mencoba lagi sekitar ${CAPACITY_RETRY_SECONDS} detik..."
      jitter_sleep "${CAPACITY_RETRY_SECONDS}" "${CAPACITY_RETRY_JITTER_MAX}"
      continue
    fi

    if grep -Eqi "timed out|RequestException|connection to endpoint timed out" <<<"${LAUNCH_OUTPUT}"; then
      rate_limit_level=0
      log "OCI timeout, mencoba lagi sekitar ${TIMEOUT_RETRY_SECONDS} detik..."
      sleep_cooldown
      continue
    fi

    if grep -Eqi "Limit Exceeded|Invalid|NotAuthorizedOrNotFound|404|400" <<<"${LAUNCH_OUTPUT}"; then
      fatal "Parameter / limit bermasalah. Output OCI: ${LAUNCH_OUTPUT}"
    fi

    if [[ ${LAUNCH_EXIT} -eq 0 ]] && grep -Eq '"lifecycle-state"[[:space:]]*:[[:space:]]*"PROVISIONING"|"lifecycleState"[[:space:]]*:[[:space:]]*"PROVISIONING"' <<<"${LAUNCH_OUTPUT}"; then
      rate_limit_level=0
      log "ALHAMDULILLAH! INSTANCE AMPERE BERHASIL DIBUAT!"
      notify_telegram "Ampere OK: ${INSTANCE_NAME}"
      break
    fi

    if [[ ${LAUNCH_EXIT} -eq 0 ]] && ! grep -Eqi '"code"|ServiceError|Exception|Error:' <<<"${LAUNCH_OUTPUT}"; then
      rate_limit_level=0
      log "ALHAMDULILLAH! INSTANCE AMPERE BERHASIL DIBUAT!"
      notify_telegram "Ampere OK: ${INSTANCE_NAME}"
      break
    fi

    rate_limit_level=0
    log "Respons belum sukses penuh. Output OCI:"
    printf '%s\n' "${LAUNCH_OUTPUT}"
    sleep_normal_retry
  done
}

main "$@"
