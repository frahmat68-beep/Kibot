#!/bin/bash

set -euo pipefail

RUNTIME_ROOT="${KICRYP_RUNTIME_ROOT:-/home/ubuntu/KiDax}"
SERVICE_NAME="${KICRYP_SERVICE_NAME:-kidax-engine}"
SERVICE_FILE_PATH="${KICRYP_SERVICE_FILE_PATH:-${RUNTIME_ROOT}/infra/systemd/${SERVICE_NAME}.service}"
DASHBOARD_PORT="${KICRYP_DASHBOARD_PORT:-8787}"
RECOVERY_SCRIPT_PATH="${KICRYP_RECOVERY_SCRIPT_PATH:-${RUNTIME_ROOT}/engine-recovery.sh}"
AI_SCRIPT_PATH="${KICRYP_AI_SCRIPT_PATH:-${RUNTIME_ROOT}/scripts/ai_learning_cycle.sh}"
RECOVERY_INTERVAL_SECONDS="${KICRYP_RECOVERY_INTERVAL_SECONDS:-90}"
RECOVERY_TIMER_NAME="${SERVICE_NAME}-recovery.timer"
RECOVERY_SERVICE_NAME="${SERVICE_NAME}-recovery.service"

mkdir -p "${RUNTIME_ROOT}/server"
mkdir -p "${RUNTIME_ROOT}/bin" "${RUNTIME_ROOT}/lib"
mkdir -p "${RUNTIME_ROOT}/state"

if [[ ! -f "$SERVICE_FILE_PATH" ]]; then
  echo "Service file not found: $SERVICE_FILE_PATH" >&2
  exit 1
fi

if [[ ! -f "$RECOVERY_SCRIPT_PATH" ]]; then
  echo "Recovery script not found: $RECOVERY_SCRIPT_PATH" >&2
  exit 1
fi

sudo cp "$SERVICE_FILE_PATH" "/etc/systemd/system/${SERVICE_NAME}.service"
sudo chmod 644 "/etc/systemd/system/${SERVICE_NAME}.service"
sudo mkdir -p "/etc/systemd/system/${SERVICE_NAME}.service.d"
sudo rm -f "/etc/systemd/system/${SERVICE_NAME}.service.d/memory-optimize.conf"
sudo tee "/etc/systemd/system/${SERVICE_NAME}.service.d/00-kicryp-memory.conf" >/dev/null <<'EOF'
[Service]
Environment="JAVA_OPTS=-XX:+UseSerialGC -Xms96m -Xmx256m -XX:MaxMetaspaceSize=80m -Dkotlinx.coroutines.scheduler.core.pool.size=2 -Dkotlinx.coroutines.scheduler.max.pool.size=3 -Dfile.encoding=UTF-8"
EOF
sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"
sudo systemctl stop "$SERVICE_NAME" || true
sudo pkill -f 'gradle.*:apps:mac-engine:run' || true
sudo pkill -f "${RUNTIME_ROOT}/bin/mac-engine" || true
sudo fuser -k "${DASHBOARD_PORT}/tcp" || true
sudo systemctl daemon-reload
sudo systemctl start "$SERVICE_NAME"

sudo systemctl status "$SERVICE_NAME" --no-pager || true

sudo tee "/etc/systemd/system/${RECOVERY_SERVICE_NAME}" >/dev/null <<EOF
[Unit]
Description=${SERVICE_NAME} recovery watchdog
After=network-online.target ${SERVICE_NAME}.service
Wants=network-online.target ${SERVICE_NAME}.service

[Service]
Type=oneshot
Environment=KICRYP_RUNTIME_ROOT=${RUNTIME_ROOT}
Environment=KICRYP_SERVICE_NAME=${SERVICE_NAME}
Environment=KICRYP_DASHBOARD_PORT=${DASHBOARD_PORT}
Environment=KICRYP_ENV_FILE=${KICRYP_ENV_FILE:-}
Environment=KICRYP_EXPECT_LIVE_EXECUTION=${KICRYP_EXPECT_LIVE_EXECUTION:-true}
Environment=KICRYP_RECOVERY_HTTP_TIMEOUT_SECONDS=${KICRYP_RECOVERY_HTTP_TIMEOUT_SECONDS:-20}
Environment=KICRYP_RECOVERY_ALLOW_SLOW_PORT_FALLBACK=${KICRYP_RECOVERY_ALLOW_SLOW_PORT_FALLBACK:-true}
Environment=KICRYP_RECOVERY_WARMUP_GRACE_SECONDS=${KICRYP_RECOVERY_WARMUP_GRACE_SECONDS:-300}
ExecStart=${RECOVERY_SCRIPT_PATH}
EOF

sudo tee "/etc/systemd/system/${RECOVERY_TIMER_NAME}" >/dev/null <<EOF
[Unit]
Description=${SERVICE_NAME} recovery watchdog timer

[Timer]
OnBootSec=20s
OnUnitActiveSec=${RECOVERY_INTERVAL_SECONDS}s
AccuracySec=5s
Persistent=true
Unit=${RECOVERY_SERVICE_NAME}

[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now "${RECOVERY_TIMER_NAME}"
sudo systemctl restart "$SERVICE_NAME"
sudo systemctl is-active --quiet "$SERVICE_NAME"

if crontab -l 2>/dev/null | grep -vF "${RECOVERY_SCRIPT_PATH}" | grep -vF "engine-recovery.sh" | sed '/^$/d' | crontab - 2>/dev/null; then
  echo "[ok] existing recovery cron entries removed"
else
  echo "[info] no recovery cron entries to remove"
fi

if [[ -f "$AI_SCRIPT_PATH" ]]; then
  AI_CRON_JOB="5 * * * * ${AI_SCRIPT_PATH}"
  (crontab -l 2>/dev/null; echo "$AI_CRON_JOB") | awk '!seen[$0]++' | crontab -
  chmod +x "$AI_SCRIPT_PATH"
else
  true
fi

chmod +x "$RECOVERY_SCRIPT_PATH"

echo "Setup complete for ${SERVICE_NAME}."
echo "Dashboard should be available at http://<server-ip>:${DASHBOARD_PORT}"
echo "Recovery timer: ${RECOVERY_TIMER_NAME} every ${RECOVERY_INTERVAL_SECONDS}s"
