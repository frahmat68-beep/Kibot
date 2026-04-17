#!/usr/bin/env bash
# KiBot v7.1 Service Installer
# ============================
# Installs sub-system agents as individual systemd services.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_DIR="/etc/systemd/system"
USER_NAME="$(whoami)"

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

# 3. AI Coordinator (Team IT Proxy)
create_service "kibot-ai-coordinator" "kibot_ai_coordinator.py" "AI Rate-Limit Hub"

# 4. Telegram Notifier
create_service "kibot-notifier" "kibot_notifier.py" "Telemetry Notifier"

# 5. Server Guardian
create_service "kibot-guardian" "kibot_guardian.py" "Risk & Safety Guardian"

# 6. Orchestrator
create_service "kibot-orchestrator" "kibot_orchestrator.py" "System Coordinator"

# 7. Security Monitor
create_service "kibot-security" "kibot_security.py" "Security Monitor"

echo "Reloading systemd..."
sudo systemctl daemon-reload

echo "Starting Trinity Agentic Core..."
sudo systemctl enable kibot-analyst kibot-auditor kibot-ai-coordinator kibot-notifier kibot-guardian kibot-orchestrator kibot-security
sudo systemctl start kibot-analyst kibot-auditor kibot-ai-coordinator kibot-notifier kibot-guardian kibot-orchestrator kibot-security

echo "v7.1 Services Installed Successfully."
