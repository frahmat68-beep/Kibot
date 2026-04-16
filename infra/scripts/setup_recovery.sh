#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Jalankan sebagai root: sudo bash setup_recovery.sh"
  exit 1
fi

PRIMARY_SERVICES=("kinance-engine" "kicryp-engine" "kidax-engine")
OPTIONAL_SERVICES=()
SWAPFILE="/swapfile"
SWAPSIZE="2G"

service_file_path() {
  local service_name="$1"
  echo "/etc/systemd/system/${service_name}.service"
}

ensure_restart_policy() {
  local service_name="$1"
  local service_file
  service_file="$(service_file_path "$service_name")"

  if [[ ! -f "$service_file" ]]; then
    echo "[skip] ${service_name}: unit file tidak ada di ${service_file}"
    return 0
  fi

  cp "$service_file" "${service_file}.bak.$(date +%Y%m%d%H%M%S)"

  python3 - "$service_file" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
lines = path.read_text().splitlines()

out = []
in_service = False
service_seen = False
restart_seen = False
restartsec_seen = False

for line in lines:
    stripped = line.strip()

    if stripped.startswith("[") and stripped.endswith("]"):
        if in_service:
            if not restart_seen:
                out.append("Restart=always")
            if not restartsec_seen:
                out.append("RestartSec=10")
        in_service = stripped == "[Service]"
        if in_service:
            service_seen = True
        out.append(line)
        continue

    if in_service and stripped.startswith("Restart="):
        if not restart_seen:
            out.append("Restart=always")
            restart_seen = True
        continue

    if in_service and stripped.startswith("RestartSec="):
        if not restartsec_seen:
            out.append("RestartSec=10")
            restartsec_seen = True
        continue

    out.append(line)

if in_service:
    if not restart_seen:
        out.append("Restart=always")
    if not restartsec_seen:
        out.append("RestartSec=10")

if not service_seen:
    out.append("[Service]")
    out.append("Restart=always")
    out.append("RestartSec=10")

path.write_text("\n".join(out) + "\n")
PY

  echo "[ok] ${service_name}: Restart=always dan RestartSec=10 terpasang"
}

setup_swap() {
  if swapon --show=NAME --noheadings | grep -qx "$SWAPFILE"; then
    echo "[ok] swap ${SWAPFILE} sudah aktif"
  else
    if [[ ! -f "$SWAPFILE" ]]; then
      fallocate -l "$SWAPSIZE" "$SWAPFILE"
      echo "[ok] swapfile ${SWAPFILE} dibuat ${SWAPSIZE}"
    else
      echo "[info] swapfile ${SWAPFILE} sudah ada"
    fi

    chmod 600 "$SWAPFILE"
    mkswap "$SWAPFILE" >/dev/null
    swapon "$SWAPFILE"
    echo "[ok] swap ${SWAPFILE} aktif"
  fi

  if ! grep -qE "^${SWAPFILE}[[:space:]]+none[[:space:]]+swap[[:space:]]+sw[[:space:]]+0[[:space:]]+0$" /etc/fstab; then
    echo "${SWAPFILE} none swap sw 0 0" | tee -a /etc/fstab >/dev/null
    echo "[ok] entry swap ditambahkan ke /etc/fstab"
  else
    echo "[ok] entry swap di /etc/fstab sudah ada"
  fi
}

collect_existing_services() {
  local existing=()
  local service_name
  for service_name in "${PRIMARY_SERVICES[@]}" "${OPTIONAL_SERVICES[@]}"; do
    if [[ -f "$(service_file_path "$service_name")" ]]; then
      existing+=("$service_name")
    fi
  done
  printf '%s\n' "${existing[@]}"
}

main() {
  local services=()
  while IFS= read -r line; do
    [[ -n "$line" ]] && services+=("$line")
  done < <(collect_existing_services)

  if [[ "${#services[@]}" -eq 0 ]]; then
    echo "Tidak ada unit target yang ditemukan."
    exit 1
  fi

  echo "Target service: ${services[*]}"
  local service_name
  for service_name in "${services[@]}"; do
    ensure_restart_policy "$service_name"
  done

  setup_swap

  systemctl daemon-reload
  systemctl restart "${services[@]}"

  echo
  echo "=== STATUS SERVICE ==="
  systemctl --no-pager --full status "${services[@]}" | sed -n '1,120p'
  echo
  echo "=== STATUS SWAP ==="
  swapon --show
  echo
  echo "=== MEMORY RINGKAS ==="
  free -h
}

main "$@"
