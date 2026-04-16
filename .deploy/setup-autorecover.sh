#!/bin/bash

set -euo pipefail

RUNTIME_ROOT="${KICRYP_RUNTIME_ROOT:-/home/ubuntu/KiDax}"
SERVICE_NAME="${KICRYP_SERVICE_NAME:-kidax-engine}"
SERVICE_FILE_PATH="${KICRYP_SERVICE_FILE_PATH:-${RUNTIME_ROOT}/infra/systemd/${SERVICE_NAME}.service}"
DASHBOARD_PORT="${KICRYP_DASHBOARD_PORT:-8787}"
RECOVERY_SCRIPT_PATH="${KICRYP_RECOVERY_SCRIPT_PATH:-${RUNTIME_ROOT}/engine-recovery.sh}"
AI_SCRIPT_PATH="${KICRYP_AI_SCRIPT_PATH:-${RUNTIME_ROOT}/scripts/ai_learning_cycle.sh}"

mkdir -p "${RUNTIME_ROOT}/server"

sudo cp "$SERVICE_FILE_PATH" "/etc/systemd/system/${SERVICE_NAME}.service"
sudo chmod 644 "/etc/systemd/system/${SERVICE_NAME}.service"
sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"
sudo systemctl stop "$SERVICE_NAME" || true
sudo pkill -f 'gradle.*:apps:mac-engine:run' || true
sudo pkill -f "${RUNTIME_ROOT}/server/mac-engine-all.jar" || true
sudo fuser -k "${DASHBOARD_PORT}/tcp" || true
sudo systemctl daemon-reload
sudo systemctl start "$SERVICE_NAME"

sudo systemctl status "$SERVICE_NAME" --no-pager || true

CRON_JOB="*/5 * * * * ${RECOVERY_SCRIPT_PATH}"
AI_CRON_JOB="5 * * * * ${AI_SCRIPT_PATH}"
(crontab -l 2>/dev/null; echo "$CRON_JOB"; echo "$AI_CRON_JOB") | awk '!seen[$0]++' | crontab -

chmod +x "$RECOVERY_SCRIPT_PATH" "${RUNTIME_ROOT}/setup-autorecover.sh" "$AI_SCRIPT_PATH"

echo "Setup complete for ${SERVICE_NAME}."
echo "Dashboard should be available at http://<server-ip>:${DASHBOARD_PORT}"
