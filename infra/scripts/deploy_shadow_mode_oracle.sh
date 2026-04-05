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
  ssh -i "$key" -p "$port" -o StrictHostKeyChecking=accept-new "$user@$host" "$cmd"
}

deploy_kinance() {
  echo "==> Deploy Kinance ($KINANCE_HOST)"
  ssh_run "$KINANCE_KEY" "$KINANCE_USER" "$KINANCE_HOST" "$KINANCE_PORT" "bash -se" <<'REMOTE'
set -euo pipefail
APP_ROOT="/home/ubuntu/Kinance"
REPO_ROOT="/home/ubuntu/Kinance/repo"
ENV_FILE="/home/ubuntu/Kinance/.env.kinance"
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
  git checkout "$BRANCH"
  git pull --ff-only origin "$BRANCH"
fi

touch "$ENV_FILE"
  if grep -q '^SHADOW_MODE=' "$ENV_FILE"; then
    sed -i 's/^SHADOW_MODE=.*/SHADOW_MODE=false/' "$ENV_FILE"
  else
    printf '\nSHADOW_MODE=false\n' >> "$ENV_FILE"
  fi

  if grep -q '^KIBOT_HIVE_UDP_PEERS=' "$ENV_FILE"; then
    sed -i 's|^KIBOT_HIVE_UDP_PEERS=.*|KIBOT_HIVE_UDP_PEERS=213.35.118.26:9997,213.35.118.26:9999|' "$ENV_FILE"
  else
    printf '\nKIBOT_HIVE_UDP_PEERS=213.35.118.26:9997,213.35.118.26:9999\n' >> "$ENV_FILE"
  fi

  if grep -q '^KIBOT_HIVE_EXPECTED_BOT_IDS=' "$ENV_FILE"; then
    sed -i 's/^KIBOT_HIVE_EXPECTED_BOT_IDS=.*/KIBOT_HIVE_EXPECTED_BOT_IDS=kidax,kibot/' "$ENV_FILE"
  else
    printf '\nKIBOT_HIVE_EXPECTED_BOT_IDS=kidax,kibot\n' >> "$ENV_FILE"
  fi

  if ! grep -q '^SHADOW_MODE=false$' "$ENV_FILE"; then
    echo "[FATAL] SHADOW_MODE lock failed on Kinance."
    exit 1
  fi

cd "$REPO_ROOT"
./gradlew :apps:mac-engine:fatJar -q
install -m 0644 apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar "$APP_ROOT/server/mac-engine-all.jar"

sudo systemctl daemon-reload
sudo systemctl restart "$SERVICE_NAME"
sudo systemctl is-active --quiet "$SERVICE_NAME"
curl -fsS --retry 10 --retry-delay 2 "http://127.0.0.1:${RUNTIME_PORT}/api/state" >/tmp/kinance-state.json
echo "Kinance state: $(head -c 220 /tmp/kinance-state.json)"
REMOTE
}

deploy_kidax() {
  echo "==> Deploy KiDax ($KIDAX_HOST)"
  ssh_run "$KIDAX_KEY" "$KIDAX_USER" "$KIDAX_HOST" "$KIDAX_PORT" "bash -se" <<'REMOTE'
set -euo pipefail
APP_ROOT="/home/ubuntu/KiDax"
ENV_FILE="/home/ubuntu/KiDax/.env.kidax"
SERVICE_NAME="kidax-engine"
RUNTIME_PORT="8787"
BRANCH="main"

cd "$APP_ROOT"
git fetch origin "$BRANCH"
git checkout "$BRANCH"
git pull --ff-only origin "$BRANCH"

touch "$ENV_FILE"
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

  if ! grep -q '^SHADOW_MODE=false$' "$ENV_FILE"; then
    echo "[FATAL] SHADOW_MODE lock failed on KiDax."
    exit 1
  fi

./gradlew :apps:mac-engine:fatJar -q
install -m 0644 apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar "$APP_ROOT/server/mac-engine-all.jar"

sudo systemctl daemon-reload
sudo systemctl restart "$SERVICE_NAME"
sudo systemctl is-active --quiet "$SERVICE_NAME"
curl -fsS --retry 10 --retry-delay 2 "http://127.0.0.1:${RUNTIME_PORT}/api/state" >/tmp/kidax-state.json
echo "KiDax state: $(head -c 220 /tmp/kidax-state.json)"
REMOTE
}

deploy_kinance
deploy_kidax

echo "==> Deploy done. Live mode locked on both nodes."
echo "Live log watch KiDax:"
echo "ssh -i \"$KIDAX_KEY\" -p \"$KIDAX_PORT\" \"$KIDAX_USER@$KIDAX_HOST\" 'sudo journalctl -u kidax-engine -f -n 200 | grep --line-buffered -E \"SHADOW MODE|LEAD_LAG|ORDER|Executed\"'"
