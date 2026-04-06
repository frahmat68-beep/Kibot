#!/usr/bin/env bash

set -uo pipefail

###############################################################################
# PROJECT LAZARUS V2 - THE AMPERE INVASION
###############################################################################

# Isi dari script lama / Oracle console nanti.
COMPARTMENT_ID=""
SUBNET_ID=""
AVAILABILITY_DOMAIN=""
SSH_KEY_FILE="${HOME}/.ssh/id_rsa.pub" # wajib file public key (.pub), bukan private key

# Opsional tapi aman buat dibikin eksplisit.
OCI_CONFIG_FILE="${OCI_CONFIG_FILE:-${HOME}/.oci/config}"
REGION="ap-singapore-1"
SHAPE="VM.Standard.A1.Flex"
SHAPE_CONFIG='{"ocpus": 4, "memoryInGBs": 24}'
BOOT_VOLUME_SIZE_GB=50
INSTANCE_NAME_PREFIX="lazarus-ampere"
CAPACITY_RETRY_SECONDS=40
CAPACITY_RETRY_JITTER_MAX=12
TIMEOUT_RETRY_SECONDS=45
TIMEOUT_RETRY_JITTER_MAX=15
RATE_LIMIT_BACKOFF_SEQUENCE=(90 180 300)
IMAGE_CACHE_TTL_SECONDS=$((6 * 60 * 60))
STATE_DIR="${HOME}/ampere-hunt/state"
IMAGE_CACHE_FILE="${STATE_DIR}/arm_image_id.cache"

# Opsional: kalau diisi, script kirim notif Telegram saat sukses / fatal.
TELEGRAM_BOT_TOKEN="${KIBOT_TELEGRAM_BOT_TOKEN:-}"
TELEGRAM_CHAT_ID="${KIBOT_TELEGRAM_CHAT_ID:-}"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

log_err() {
  log "$@" >&2
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
  notify_telegram "Lazarus Ampere berhenti: $1"
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

require_cmd oci
require_cmd jq
require_cmd curl

require_non_empty "COMPARTMENT_ID" "${COMPARTMENT_ID}"
require_non_empty "SUBNET_ID" "${SUBNET_ID}"
require_non_empty "AVAILABILITY_DOMAIN" "${AVAILABILITY_DOMAIN}"

[[ -f "${SSH_KEY_FILE}" ]] || fatal "SSH public key tidak ditemukan: ${SSH_KEY_FILE}"

export OCI_CLI_SUPPRESS_FILE_PERMISSIONS_WARNING=true
export SUPPRESS_LABEL_WARNING=True

mkdir -p "${STATE_DIR}"

fetch_arm_image_id() {
  oci --config-file "${OCI_CONFIG_FILE}" compute image list \
    --compartment-id "${COMPARTMENT_ID}" \
    --operating-system "Canonical Ubuntu" \
    --operating-system-version "24.04" \
    --shape "${SHAPE}" \
    --sort-by TIMECREATED \
    --sort-order DESC \
    --query 'data[0].id' \
    --raw-output 2>/tmp/lazarus_ampere_image.err || true
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

  INSTANCE_NAME="${INSTANCE_NAME_PREFIX}-$(date +%Y%m%d-%H%M%S)"
  log "Mencoba membuat instance Ampere: ${INSTANCE_NAME}"

  set +e
  LAUNCH_OUTPUT="$(oci --config-file "${OCI_CONFIG_FILE}" compute instance launch \
    --region "${REGION}" \
    --compartment-id "${COMPARTMENT_ID}" \
    --availability-domain "${AVAILABILITY_DOMAIN}" \
    --display-name "${INSTANCE_NAME}" \
    --shape "${SHAPE}" \
    --shape-config "${SHAPE_CONFIG}" \
    --image-id "${ARM_IMAGE_ID}" \
    --boot-volume-size-in-gbs "${BOOT_VOLUME_SIZE_GB}" \
    --subnet-id "${SUBNET_ID}" \
    --assign-public-ip true \
    --metadata "{\"ssh_authorized_keys\":\"$(cat "${SSH_KEY_FILE}")\"}" \
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
    jitter_sleep "${cooldown_seconds}" 20
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
    jitter_sleep "${TIMEOUT_RETRY_SECONDS}" "${TIMEOUT_RETRY_JITTER_MAX}"
    continue
  fi

  if grep -Eqi "Limit Exceeded|Invalid|NotAuthorizedOrNotFound|404|400" <<<"${LAUNCH_OUTPUT}"; then
    fatal "Parameter / limit bermasalah. Output OCI: ${LAUNCH_OUTPUT}"
  fi

  if [[ ${LAUNCH_EXIT} -eq 0 ]] && grep -Eq '"lifecycle-state"[[:space:]]*:[[:space:]]*"PROVISIONING"|"lifecycleState"[[:space:]]*:[[:space:]]*"PROVISIONING"' <<<"${LAUNCH_OUTPUT}"; then
    rate_limit_level=0
    log "ALHAMDULILLAH! INSTANCE AMPERE BERHASIL DIBUAT!"
    printf '\a'
    notify_telegram "ALHAMDULILLAH! Ampere A1 berhasil dibuat. Nama instance: ${INSTANCE_NAME}"
    break
  fi

  if [[ ${LAUNCH_EXIT} -eq 0 ]] && ! grep -Eqi '"code"|ServiceError|Exception|Error:' <<<"${LAUNCH_OUTPUT}"; then
    rate_limit_level=0
    log "ALHAMDULILLAH! INSTANCE AMPERE BERHASIL DIBUAT!"
    printf '\a'
    notify_telegram "ALHAMDULILLAH! Ampere A1 berhasil dibuat. Nama instance: ${INSTANCE_NAME}"
    break
  fi

  rate_limit_level=0
  log "Respons belum sukses penuh. Output OCI:"
  printf '%s\n' "${LAUNCH_OUTPUT}"
  log "Mencoba lagi sekitar ${CAPACITY_RETRY_SECONDS} detik..."
  jitter_sleep "${CAPACITY_RETRY_SECONDS}" "${CAPACITY_RETRY_JITTER_MAX}"
done
