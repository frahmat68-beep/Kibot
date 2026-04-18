#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Jalankan sebagai root: sudo bash stop_kibot_engine_oracle.sh"
  exit 1
fi

KIDAX_ENV="/home/ubuntu/KiDax/.env.kidax"
BINANCE_HOST="152.69.218.198"
KIDAX_DROPIN_DIR="/etc/systemd/system/kidax-engine.service.d"
KIDAX_DROPIN_FILE="${KIDAX_DROPIN_DIR}/binance-kibot-routing.conf"

upsert_env() {
  local file="$1"
  local key="$2"
  local value="$3"
  python3 - "$file" "$key" "$value" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
key = sys.argv[2]
value = sys.argv[3]

lines = []
found = False
if path.exists():
    lines = path.read_text(encoding="utf-8").splitlines()

new_lines = []
for line in lines:
    if line.startswith(f"{key}="):
        new_lines.append(f'{key}="{value}"')
        found = True
    else:
        new_lines.append(line)

if not found:
    new_lines.append(f'{key}="{value}"')

path.write_text("\n".join(new_lines) + "\n", encoding="utf-8")
PY
}

echo "[1/4] Redirect KiDax UDP ke KiBot baru di Binance..."
upsert_env "$KIDAX_ENV" "KIBOT_LEAD_LAG_UDP_TARGET_HOST" "$BINANCE_HOST"
upsert_env "$KIDAX_ENV" "KIBOT_LEAD_LAG_UDP_TARGET_PORT" "9999"
upsert_env "$KIDAX_ENV" "KIBOT_HIVE_UDP_PEERS" "${BINANCE_HOST}:9999"
upsert_env "$KIDAX_ENV" "KIBOT_LEAD_LAG_UDP_HEARTBEAT_INTERVAL_MS" "1000"
upsert_env "$KIDAX_ENV" "KIBOT_LEAD_LAG_UDP_HEARTBEAT_TIMEOUT_MS" "5000"

echo "[2/4] Mematikan kibot-engine di Oracle..."
systemctl stop kibot-engine || true
systemctl disable kibot-engine || true

echo "[3/4] Menjaga hanya KiDax yang aktif di Oracle..."
mkdir -p "$KIDAX_DROPIN_DIR"
cat > "$KIDAX_DROPIN_FILE" <<'EOF'
[Service]
Environment=KIBOT_LEAD_LAG_UDP_HEARTBEAT_INTERVAL_MS=1000
Environment=KIBOT_LEAD_LAG_UDP_HEARTBEAT_TIMEOUT_MS=5000
EOF
systemctl daemon-reload
systemctl enable kidax-engine
systemctl restart kidax-engine

echo "[4/4] Verifikasi..."
echo "--- kidax-engine ---"
systemctl is-active kidax-engine
echo "--- kibot-engine ---"
systemctl is-active kibot-engine || true
echo "--- memory ---"
free -h
