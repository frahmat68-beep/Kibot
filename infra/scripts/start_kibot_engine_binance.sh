#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Jalankan sebagai root: sudo bash start_kibot_engine_binance.sh"
  exit 1
fi

KINANCE_ENV="/home/ubuntu/Kinance/.env.kinance"
KIBOT_ENV="/home/ubuntu/KiBot/.env.kibot"
DROPIN_DIR="/etc/systemd/system/kibot-engine.service.d"
DROPIN_FILE="${DROPIN_DIR}/binance-relocation.conf"
KINANCE_DROPIN_DIR="/etc/systemd/system/kinance-engine.service.d"
KINANCE_DROPIN_FILE="${KINANCE_DROPIN_DIR}/kibot-local-routing.conf"
ORACLE_HOST="213.35.118.26"

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

echo "[1/5] Redirect Kinance -> localhost KiBot -> Oracle KiDax..."
upsert_env "$KINANCE_ENV" "KIBOT_LEAD_LAG_UDP_LISTEN_PORT" "9998"
upsert_env "$KINANCE_ENV" "KIBOT_LEAD_LAG_UDP_TARGET_HOST" "127.0.0.1"
upsert_env "$KINANCE_ENV" "KIBOT_LEAD_LAG_UDP_TARGET_PORT" "9999"
upsert_env "$KINANCE_ENV" "KIBOT_HIVE_UDP_PEERS" "${ORACLE_HOST}:9997"
upsert_env "$KINANCE_ENV" "KIBOT_LEAD_LAG_UDP_HEARTBEAT_INTERVAL_MS" "1000"
upsert_env "$KINANCE_ENV" "KIBOT_LEAD_LAG_UDP_HEARTBEAT_TIMEOUT_MS" "5000"

echo "[2/5] Set KiBot Binance output ke KiDax Oracle..."
upsert_env "$KIBOT_ENV" "KIBOT_LEAD_LAG_UDP_ENABLED" "true"
upsert_env "$KIBOT_ENV" "KIBOT_LEAD_LAG_UDP_LISTEN_PORT" "9999"
upsert_env "$KIBOT_ENV" "KIBOT_LEAD_LAG_UDP_TARGET_HOST" "$ORACLE_HOST"
upsert_env "$KIBOT_ENV" "KIBOT_LEAD_LAG_UDP_TARGET_PORT" "9997"
upsert_env "$KIBOT_ENV" "KIBOT_HIVE_UDP_PEERS" "127.0.0.1:9998,${ORACLE_HOST}:9997"
upsert_env "$KIBOT_ENV" "KIBOT_HIVE_EXPECTED_BOT_IDS" "kinance,kidax"
upsert_env "$KIBOT_ENV" "KIBOT_LEAD_LAG_UDP_HEARTBEAT_INTERVAL_MS" "1000"
upsert_env "$KIBOT_ENV" "KIBOT_LEAD_LAG_UDP_HEARTBEAT_TIMEOUT_MS" "5000"

echo "[3/5] Pasang throttling + JVM cap KiBot..."
mkdir -p "$DROPIN_DIR"
cat > "$DROPIN_FILE" <<'EOF'
[Service]
Environment=DEVICE_ID=kibot-binance-sg
Environment="DEVICE_DISPLAY_NAME=KiBot Binance Singapore"
Environment=DEVICE_ROLE=PRIMARY
Environment=KIBOT_LEAD_LAG_UDP_HEARTBEAT_INTERVAL_MS=1000
Environment=KIBOT_LEAD_LAG_UDP_HEARTBEAT_TIMEOUT_MS=5000
Environment=BOT_POLL_INTERVAL_MS=8000
Environment=BOT_EXCHANGE_PING_REFRESH_INTERVAL_MS=8000
Environment=BOT_BALANCE_REFRESH_INTERVAL_MS=8000
Environment=BOT_OPEN_ORDERS_REFRESH_INTERVAL_MS=10000
Environment=BOT_COMMANDS_REFRESH_INTERVAL_MS=5000
Environment=BOT_RECENT_ORDERS_REFRESH_INTERVAL_MS=15000
Environment=BOT_RECENT_FILLS_REFRESH_INTERVAL_MS=15000
Environment=BOT_ANALYSIS_PUBLISH_INTERVAL_MS=600000
Environment=BOT_STRATEGY_METRICS_PUBLISH_INTERVAL_MS=1800000
Environment=MAC_DASHBOARD_STATE_POLL_INTERVAL_MS=3000
Environment=MAC_DASHBOARD_LOG_POLL_INTERVAL_MS=5000
Environment="JAVA_OPTS=-XX:+UseSerialGC -Xms96m -Xmx256m -XX:MaxMetaspaceSize=80m -Dkotlinx.coroutines.scheduler.core.pool.size=2 -Dkotlinx.coroutines.scheduler.max.pool.size=3 -Dfile.encoding=UTF-8"
EOF

mkdir -p "$KINANCE_DROPIN_DIR"
cat > "$KINANCE_DROPIN_FILE" <<'EOF'
[Service]
Environment=KIBOT_LEAD_LAG_UDP_HEARTBEAT_INTERVAL_MS=1000
Environment=KIBOT_LEAD_LAG_UDP_HEARTBEAT_TIMEOUT_MS=5000
EOF

echo "[4/5] Reload + start services..."
systemctl daemon-reload
systemctl enable kibot-engine kinance-engine
systemctl restart kibot-engine kinance-engine

echo "[5/5] Verifikasi..."
echo "--- kinance-engine ---"
systemctl is-active kinance-engine
echo "--- kibot-engine ---"
systemctl is-active kibot-engine
echo "--- memory ---"
free -h
