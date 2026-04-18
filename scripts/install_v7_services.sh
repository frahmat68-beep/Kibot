#!/usr/bin/env bash
# KiBot v7.1 Service Installer
# ============================
# Installs sub-system agents as individual systemd services.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_DIR="/etc/systemd/system"
USER_NAME="$(whoami)"
ENABLE_AI_COORDINATOR="${ENABLE_AI_COORDINATOR:-false}"

disable_legacy_unit() {
    local unit="$1"
    if sudo systemctl list-unit-files "$unit" >/dev/null 2>&1; then
        echo "Disabling legacy unit $unit..."
        sudo systemctl disable --now "$unit" >/dev/null 2>&1 || true
    fi
}

create_service() {
    local name=$1
    local script=$2
    local desc=$3
    local type=${4:-simple}

    echo "Creating $name..."
    cat <<EOF | sudo tee "$SERVICE_DIR/$name.service" > /dev/null
[Unit]
Description=KiBot v7.1 $desc
After=network.target

[Service]
Type=$type
User=$USER_NAME
WorkingDirectory=$ROOT_DIR
EnvironmentFile=$ROOT_DIR/.env
ExecStart=/usr/bin/python3 $ROOT_DIR/scripts/$script
Restart=always
RestartSec=10
StandardOutput=syslog
StandardError=syslog
SyslogIdentifier=$name

# Resource limits for 1GB VPS stability
MemoryHigh=80M
MemoryMax=120M
CPUQuota=15%

[Install]
WantedBy=multi-user.target
EOF
}

# 1. Analyst Agent
create_service "kibot-analyst" "kibot_analyst.py" "Trade Analyst & Journaler"

# 2. Infrastructure Auditor
create_service "kibot-auditor" "kibot_auditor.py" "Self-Healing Infra Auditor"

# 3. Telegram Notifier
create_service "kibot-notifier" "kibot_notifier.py" "Telemetry Notifier"

# 4. Server Guardian
create_service "kibot-guardian" "kibot_guardian.py" "Risk & Safety Guardian"

# 5. Orchestrator
create_service "kibot-orchestrator" "kibot_orchestrator.py" "System Coordinator"

# 6. Security Monitor
create_service "kibot-security" "kibot_security.py" "Security Monitor"

if [[ "$ENABLE_AI_COORDINATOR" == "true" ]]; then
    create_service "kibot-ai-coordinator" "kibot_ai_coordinator.py" "AI Rate-Limit Hub"
fi

disable_legacy_unit "kibot-coordinator.service"
disable_legacy_unit "kibot-local-scanner.service"
disable_legacy_unit "kibot-recovery.service"
disable_legacy_unit "kibot-engine.service"
disable_legacy_unit "kibot-engine-recovery.timer"
disable_legacy_unit "kibot-engine-recovery.service"

echo "Reloading systemd..."
sudo systemctl daemon-reload

echo "Starting Trinity Agentic Core..."
SERVICES=(kibot-analyst kibot-auditor kibot-notifier kibot-guardian kibot-orchestrator kibot-security)
if [[ "$ENABLE_AI_COORDINATOR" == "true" ]]; then
    SERVICES+=(kibot-ai-coordinator)
fi
sudo systemctl enable "${SERVICES[@]}"
sudo systemctl start "${SERVICES[@]}"

echo "v7.1 Services Installed Successfully."
