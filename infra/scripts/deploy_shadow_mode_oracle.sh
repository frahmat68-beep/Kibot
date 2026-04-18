#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

KINANCE_HOST="${KINANCE_HOST:-152.69.218.198}"
KINANCE_USER="${KINANCE_USER:-ubuntu}"
KINANCE_KEY="${KINANCE_KEY:-$ROOT_DIR/SSH_BINANCE/ssh-key-2026-03-27.key}"
KINANCE_PORT="${KINANCE_PORT:-22}"

KIDAX_HOST="${KIDAX_HOST:-213.35.118.26}"
KIDAX_USER="${KIDAX_USER:-ubuntu}"
KIDAX_KEY="${KIDAX_KEY:-$ROOT_DIR/SSH_INDODAX/ssh-key-2026-03-22.key}"
KIDAX_PORT="${KIDAX_PORT:-22}"

REPO_URL="${REPO_URL:-https://github.com/frahmat68-beep/Kibot.git}"
BRANCH="${BRANCH:-main}"

ssh_run() {
  local key="$1" user="$2" host="$3" port="$4" cmd="$5"
  ssh -i "$key" -p "$port" \
    -o BatchMode=yes \
    -o ConnectTimeout=8 \
    -o ServerAliveInterval=5 \
    -o ServerAliveCountMax=2 \
    -o StrictHostKeyChecking=accept-new \
    "$user@$host" "$cmd"
}

deploy_kinance() {
  echo "==> Deploy KiNance ($KINANCE_HOST)"
  ssh_run "$KINANCE_KEY" "$KINANCE_USER" "$KINANCE_HOST" "$KINANCE_PORT" "bash -se" <<'REMOTE'
set -euo pipefail
APP_ROOT="/home/ubuntu/KiNance"
REPO_ROOT="/home/ubuntu/KiNance/repo"
ENV_FILE="/home/ubuntu/KiNance/.env.kinance"
SERVICE_NAME="kinance-engine"
RUNTIME_PORT="8788"
REPO_URL="https://github.com/frahmat68-beep/Kibot.git"
BRANCH="main"

mkdir -p "$APP_ROOT" "$REPO_ROOT"

if [ ! -d "$REPO_ROOT/.git" ]; then
  rm -rf "$REPO_ROOT"
  git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$REPO_ROOT"
else
  cd "$REPO_ROOT"
  git fetch origin "$BRANCH"
  git checkout -f "$BRANCH"
  git reset --hard "origin/$BRANCH"
  git clean -fd
fi

touch "$ENV_FILE"
  if grep -q '^BOT_ID=' "$ENV_FILE"; then
    sed -i 's/^BOT_ID=.*/BOT_ID=kinance/' "$ENV_FILE"
  else
    printf '\nBOT_ID=kinance\n' >> "$ENV_FILE"
  fi

  if grep -q '^BOT_PROFILE_KEY=' "$ENV_FILE"; then
    sed -i 's/^BOT_PROFILE_KEY=.*/BOT_PROFILE_KEY=kinance/' "$ENV_FILE"
  else
    printf '\nBOT_PROFILE_KEY=kinance\n' >> "$ENV_FILE"
  fi

  if grep -q '^MAC_ENGINE_BIND_HOST=' "$ENV_FILE"; then
    sed -i 's/^MAC_ENGINE_BIND_HOST=.*/MAC_ENGINE_BIND_HOST=0.0.0.0/' "$ENV_FILE"
  else
    printf '\nMAC_ENGINE_BIND_HOST=0.0.0.0\n' >> "$ENV_FILE"
  fi

  if grep -q '^MAC_ENGINE_PORT=' "$ENV_FILE"; then
    sed -i 's/^MAC_ENGINE_PORT=.*/MAC_ENGINE_PORT=8788/' "$ENV_FILE"
  else
    printf '\nMAC_ENGINE_PORT=8788\n' >> "$ENV_FILE"
  fi

  if grep -q '^DEVICE_ID=' "$ENV_FILE"; then
    sed -i 's/^DEVICE_ID=.*/DEVICE_ID=kinance-oracle-sg/' "$ENV_FILE"
  else
    printf '\nDEVICE_ID=kinance-oracle-sg\n' >> "$ENV_FILE"
  fi

  if grep -q '^BOT_POLL_INTERVAL_MS=' "$ENV_FILE"; then
    sed -i 's/^BOT_POLL_INTERVAL_MS=.*/BOT_POLL_INTERVAL_MS=2000/' "$ENV_FILE"
  else
    printf '\nBOT_POLL_INTERVAL_MS=2000\n' >> "$ENV_FILE"
  fi

  if grep -q '^MAC_DASHBOARD_STATE_POLL_INTERVAL_MS=' "$ENV_FILE"; then
    sed -i 's/^MAC_DASHBOARD_STATE_POLL_INTERVAL_MS=.*/MAC_DASHBOARD_STATE_POLL_INTERVAL_MS=2000/' "$ENV_FILE"
  else
    printf '\nMAC_DASHBOARD_STATE_POLL_INTERVAL_MS=2000\n' >> "$ENV_FILE"
  fi

  if grep -q '^MAC_DASHBOARD_LOG_POLL_INTERVAL_MS=' "$ENV_FILE"; then
    sed -i 's/^MAC_DASHBOARD_LOG_POLL_INTERVAL_MS=.*/MAC_DASHBOARD_LOG_POLL_INTERVAL_MS=5000/' "$ENV_FILE"
  else
    printf '\nMAC_DASHBOARD_LOG_POLL_INTERVAL_MS=5000\n' >> "$ENV_FILE"
  fi

  if grep -q '^SHADOW_MODE=' "$ENV_FILE"; then
    sed -i 's/^SHADOW_MODE=.*/SHADOW_MODE=false/' "$ENV_FILE"
  else
    printf '\nSHADOW_MODE=false\n' >> "$ENV_FILE"
  fi

  if grep -q '^KIBOT_HIVE_UDP_PEERS=' "$ENV_FILE"; then
    sed -i 's|^KIBOT_HIVE_UDP_PEERS=.*|KIBOT_HIVE_UDP_PEERS=213.35.118.26:9999|' "$ENV_FILE"
  else
    printf '\nKIBOT_HIVE_UDP_PEERS=213.35.118.26:9999\n' >> "$ENV_FILE"
  fi

  if grep -q '^KIBOT_HIVE_EXPECTED_BOT_IDS=' "$ENV_FILE"; then
    sed -i 's/^KIBOT_HIVE_EXPECTED_BOT_IDS=.*/KIBOT_HIVE_EXPECTED_BOT_IDS=main/' "$ENV_FILE"
  else
    printf '\nKIBOT_HIVE_EXPECTED_BOT_IDS=main\n' >> "$ENV_FILE"
  fi

  if ! grep -q '^SHADOW_MODE=false$' "$ENV_FILE"; then
    echo "[FATAL] SHADOW_MODE lock failed on KiNance."
    exit 1
  fi

cd "$REPO_ROOT"
./gradlew :apps:mac-engine:fatJar -q
install -m 0644 apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar "$APP_ROOT/server/mac-engine-all.jar"

sudo -n systemctl daemon-reload
sudo -n systemctl restart "$SERVICE_NAME"
sudo -n systemctl is-active --quiet "$SERVICE_NAME"
curl -fsS --max-time 5 --retry 5 --retry-delay 2 "http://127.0.0.1:${RUNTIME_PORT}/api/state" >/tmp/kinance-state.json
echo "KiNance state: $(head -c 220 /tmp/kinance-state.json)"
REMOTE
}

deploy_kidax() {
  echo "==> Deploy KiDax ($KIDAX_HOST)"
  ssh_run "$KIDAX_KEY" "$KIDAX_USER" "$KIDAX_HOST" "$KIDAX_PORT" "bash -se" <<'REMOTE'
set -euo pipefail
APP_ROOT="/home/ubuntu/KiDax"
ENV_FILE="/home/ubuntu/KiDax/.env.kidax"
SERVICE_NAME="kidax-engine"
RUNTIME_PORT="8788"
BRANCH="main"

cd "$APP_ROOT"
git fetch origin "$BRANCH"
git checkout -f "$BRANCH"
git reset --hard "origin/$BRANCH"
git clean -fd

touch "$ENV_FILE"
  if grep -q '^BOT_ID=' "$ENV_FILE"; then
    sed -i 's/^BOT_ID=.*/BOT_ID=kidax/' "$ENV_FILE"
  else
    printf '\nBOT_ID=kidax\n' >> "$ENV_FILE"
  fi

  if grep -q '^BOT_PROFILE_KEY=' "$ENV_FILE"; then
    sed -i 's/^BOT_PROFILE_KEY=.*/BOT_PROFILE_KEY=kidax/' "$ENV_FILE"
  else
    printf '\nBOT_PROFILE_KEY=kidax\n' >> "$ENV_FILE"
  fi

  if grep -q '^MAC_ENGINE_BIND_HOST=' "$ENV_FILE"; then
    sed -i 's/^MAC_ENGINE_BIND_HOST=.*/MAC_ENGINE_BIND_HOST=0.0.0.0/' "$ENV_FILE"
  else
    printf '\nMAC_ENGINE_BIND_HOST=0.0.0.0\n' >> "$ENV_FILE"
  fi

  if grep -q '^MAC_ENGINE_PORT=' "$ENV_FILE"; then
    sed -i 's/^MAC_ENGINE_PORT=.*/MAC_ENGINE_PORT=8788/' "$ENV_FILE"
  else
    printf '\nMAC_ENGINE_PORT=8788\n' >> "$ENV_FILE"
  fi

  if grep -q '^DEVICE_ID=' "$ENV_FILE"; then
    sed -i 's/^DEVICE_ID=.*/DEVICE_ID=kidax-oracle-sg/' "$ENV_FILE"
  else
    printf '\nDEVICE_ID=kidax-oracle-sg\n' >> "$ENV_FILE"
  fi

  if grep -q '^BOT_POLL_INTERVAL_MS=' "$ENV_FILE"; then
    sed -i 's/^BOT_POLL_INTERVAL_MS=.*/BOT_POLL_INTERVAL_MS=2000/' "$ENV_FILE"
  else
    printf '\nBOT_POLL_INTERVAL_MS=2000\n' >> "$ENV_FILE"
  fi

  if grep -q '^MAC_DASHBOARD_STATE_POLL_INTERVAL_MS=' "$ENV_FILE"; then
    sed -i 's/^MAC_DASHBOARD_STATE_POLL_INTERVAL_MS=.*/MAC_DASHBOARD_STATE_POLL_INTERVAL_MS=2000/' "$ENV_FILE"
  else
    printf '\nMAC_DASHBOARD_STATE_POLL_INTERVAL_MS=2000\n' >> "$ENV_FILE"
  fi

  if grep -q '^MAC_DASHBOARD_LOG_POLL_INTERVAL_MS=' "$ENV_FILE"; then
    sed -i 's/^MAC_DASHBOARD_LOG_POLL_INTERVAL_MS=.*/MAC_DASHBOARD_LOG_POLL_INTERVAL_MS=5000/' "$ENV_FILE"
  else
    printf '\nMAC_DASHBOARD_LOG_POLL_INTERVAL_MS=5000\n' >> "$ENV_FILE"
  fi

  if grep -q '^SHADOW_MODE=' "$ENV_FILE"; then
    sed -i 's/^SHADOW_MODE=.*/SHADOW_MODE=false/' "$ENV_FILE"
  else
    printf '\nSHADOW_MODE=false\n' >> "$ENV_FILE"
  fi

  if grep -q '^KIBOT_HIVE_UDP_PEERS=' "$ENV_FILE"; then
    sed -i 's|^KIBOT_HIVE_UDP_PEERS=.*|KIBOT_HIVE_UDP_PEERS=152.69.218.198:9999|' "$ENV_FILE"
  else
    printf '\nKIBOT_HIVE_UDP_PEERS=152.69.218.198:9999\n' >> "$ENV_FILE"
  fi

  if grep -q '^KIBOT_HIVE_EXPECTED_BOT_IDS=' "$ENV_FILE"; then
    sed -i 's/^KIBOT_HIVE_EXPECTED_BOT_IDS=.*/KIBOT_HIVE_EXPECTED_BOT_IDS=kinance/' "$ENV_FILE"
  else
    printf '\nKIBOT_HIVE_EXPECTED_BOT_IDS=kinance\n' >> "$ENV_FILE"
  fi

  if ! grep -q '^SHADOW_MODE=false$' "$ENV_FILE"; then
    echo "[FATAL] SHADOW_MODE lock failed on KiDax."
    exit 1
  fi

./gradlew :apps:mac-engine:fatJar -q
install -m 0644 apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar "$APP_ROOT/server/mac-engine-all.jar"

sudo -n systemctl daemon-reload
sudo -n systemctl restart "$SERVICE_NAME"
sudo -n systemctl is-active --quiet "$SERVICE_NAME"
curl -fsS --max-time 5 --retry 5 --retry-delay 2 "http://127.0.0.1:${RUNTIME_PORT}/api/state" >/tmp/kidax-state.json
echo "KiDax state: $(head -c 220 /tmp/kidax-state.json)"
REMOTE
}

deploy_kinance
deploy_kidax

echo "==> Deploy done. Live mode locked on both nodes."
echo "Live log watch KiDax:"
echo "ssh -i \"$KIDAX_KEY\" -p \"$KIDAX_PORT\" \"$KIDAX_USER@$KIDAX_HOST\" 'sudo journalctl -u kidax-engine -f -n 200 | grep --line-buffered -E \"SHADOW MODE|LEAD_LAG|ORDER|Executed\"'"
