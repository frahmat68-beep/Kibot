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
CAPACITY_RETRY_SECONDS=45
RATE_LIMIT_RETRY_SECONDS=300

# Opsional: kalau diisi, script kirim notif Telegram saat sukses / fatal.
TELEGRAM_BOT_TOKEN="${KIBOT_TELEGRAM_BOT_TOKEN:-}"
TELEGRAM_CHAT_ID="${KIBOT_TELEGRAM_CHAT_ID:-}"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
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

log "Menjemput OCID Ubuntu 24.04 ARM terbaru dari server Oracle..."
ARM_IMAGE_ID="$(oci --config-file "${OCI_CONFIG_FILE}" compute image list \
  --compartment-id "${COMPARTMENT_ID}" \
  --operating-system "Canonical Ubuntu" \
  --operating-system-version "24.04" \
  --shape "${SHAPE}" \
  --sort-by TIMECREATED \
  --sort-order DESC \
  --query 'data[0].id' \
  --raw-output 2>/tmp/lazarus_ampere_image.err || true)"

if [[ -z "${ARM_IMAGE_ID}" || "${ARM_IMAGE_ID}" == "null" ]]; then
  IMAGE_ERR="$(cat /tmp/lazarus_ampere_image.err 2>/dev/null || true)"
  fatal "Gagal mengambil ARM image OCID. ${IMAGE_ERR}"
fi

log "Berhasil! ARM Image OCID: ${ARM_IMAGE_ID}"

while true; do
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
    --subnet-id "${SUBNET_ID}" \
    --assign-public-ip true \
    --metadata "{\"ssh_authorized_keys\":\"$(cat "${SSH_KEY_FILE}")\"}" \
    --source-details "{\"sourceType\":\"image\",\"imageId\":\"${ARM_IMAGE_ID}\",\"bootVolumeSizeInGBs\":${BOOT_VOLUME_SIZE_GB}}" \
    2>&1)"
  LAUNCH_EXIT=$?
  set -e

  if grep -Eqi "TooManyRequests|429" <<<"${LAUNCH_OUTPUT}"; then
    log "Kena Rate Limit OCI (429)! Mendinginkan mesin selama 5 menit..."
    sleep "${RATE_LIMIT_RETRY_SECONDS}"
    continue
  fi

  if grep -qi "Out of host capacity" <<<"${LAUNCH_OUTPUT}"; then
    log "Kapasitas penuh, mencoba lagi dalam ${CAPACITY_RETRY_SECONDS} detik..."
    sleep "${CAPACITY_RETRY_SECONDS}"
    continue
  fi

  if grep -Eqi "timed out|RequestException|connection to endpoint timed out" <<<"${LAUNCH_OUTPUT}"; then
    log "OCI timeout, mencoba lagi dalam ${CAPACITY_RETRY_SECONDS} detik..."
    sleep "${CAPACITY_RETRY_SECONDS}"
    continue
  fi

  if grep -Eqi "Limit Exceeded|Invalid|NotAuthorizedOrNotFound|404|400" <<<"${LAUNCH_OUTPUT}"; then
    fatal "Parameter / limit bermasalah. Output OCI: ${LAUNCH_OUTPUT}"
  fi

  if [[ ${LAUNCH_EXIT} -eq 0 ]] && grep -Eq '"lifecycle-state"[[:space:]]*:[[:space:]]*"PROVISIONING"|"lifecycleState"[[:space:]]*:[[:space:]]*"PROVISIONING"' <<<"${LAUNCH_OUTPUT}"; then
    log "ALHAMDULILLAH! INSTANCE AMPERE BERHASIL DIBUAT!"
    printf '\a'
    notify_telegram "ALHAMDULILLAH! Ampere A1 berhasil dibuat. Nama instance: ${INSTANCE_NAME}"
    break
  fi

  if [[ ${LAUNCH_EXIT} -eq 0 ]] && ! grep -Eqi '"code"|ServiceError|Exception|Error:' <<<"${LAUNCH_OUTPUT}"; then
    log "ALHAMDULILLAH! INSTANCE AMPERE BERHASIL DIBUAT!"
    printf '\a'
    notify_telegram "ALHAMDULILLAH! Ampere A1 berhasil dibuat. Nama instance: ${INSTANCE_NAME}"
    break
  fi

  log "Respons belum sukses penuh. Output OCI:"
  printf '%s\n' "${LAUNCH_OUTPUT}"
  log "Mencoba lagi dalam ${CAPACITY_RETRY_SECONDS} detik..."
  sleep "${CAPACITY_RETRY_SECONDS}"
done
