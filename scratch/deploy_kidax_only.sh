#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/Users/kiki/Documents/Web Develop/KiBot"
KIDAX_HOST="213.35.118.26"
KIDAX_USER="ubuntu"
KIDAX_KEY="$ROOT_DIR/SSH_INDODAX/ssh-key-2026-03-22.key"
KIDAX_PORT="22"

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

echo "==> Deploy KiDax ($KIDAX_HOST)"
ssh_run "$KIDAX_KEY" "$KIDAX_USER" "$KIDAX_HOST" "$KIDAX_PORT" "bash -se" <<'REMOTE'
set -euo pipefail
APP_ROOT="/home/ubuntu/KiDax"
ENV_FILE="/home/ubuntu/KiDax/.env.kidax"
SERVICE_NAME="kidax-engine"
RUNTIME_PORT="8788"
BRANCH="main"
REPO_URL="https://github.com/frahmat68-beep/Kibot.git"

mkdir -p "$APP_ROOT"

if [ ! -d "$APP_ROOT/.git" ]; then
  echo "Cloning fresh repo..."
  rm -rf "$APP_ROOT"
  git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$APP_ROOT"
  cd "$APP_ROOT"
else
  echo "Updating existing repo..."
  cd "$APP_ROOT"
  git fetch origin "$BRANCH"
  git checkout -f "$BRANCH"
  git reset --hard "origin/$BRANCH"
  git clean -fd
fi

touch "$ENV_FILE"
# Thorough rebrand of existing env vars
[[ -f "$ENV_FILE" ]] && sed -i 's/KICRYP/KIBOT/g' "$ENV_FILE"
[[ -f "$ENV_FILE" ]] && sed -i 's/kicryp/kibot/g' "$ENV_FILE"

echo "Building fatJar with Gradle 8.7..."
~/gradle-8.7/bin/gradle :apps:mac-engine:fatJar -q
install -m 0644 apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar "$APP_ROOT/server/mac-engine-all.jar"

sudo -n systemctl daemon-reload
echo "Restarting $SERVICE_NAME..."
sudo -n systemctl restart "$SERVICE_NAME"
sudo -n systemctl is-active --quiet "$SERVICE_NAME"

# Check state
curl -fsS --max-time 5 --retry 5 --retry-delay 2 "http://127.0.0.1:${RUNTIME_PORT}/api/state" >/tmp/kidax-state.json
echo "KiDax state (first 200 chars):"
head -c 200 /tmp/kidax-state.json
REMOTE
