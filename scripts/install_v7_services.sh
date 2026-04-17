#!/usr/bin/env bash
# KiBot v7.0 Service Installer
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
Description=KiBot v7.0 $desc
After=network.target kibot-manager.service

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
create_service "kibot-coordinator" "kibot_ai_coordinator.py" "AI Rate-Limit Hub"

# 4. Telegram Notifier
create_service "kibot-notifier" "kibot_telegram.py" "Telemetry Notifier"

# 5. Security Guardian
create_service "kibot-guardian" "kibot_guardian.py" "Risk & Safety Guardian"

# 6. Security Auditor (Veto Logic)
create_service "kibot-security" "kibot_security.py" "Veto Security Logic"

echo "Reloading systemd..."
sudo systemctl daemon-reload

echo "Starting Trinity Agentic Core..."
sudo systemctl enable kibot-analyst kibot-auditor kibot-coordinator kibot-notifier kibot-guardian kibot-security
sudo systemctl start kibot-analyst kibot-auditor kibot-coordinator kibot-notifier kibot-guardian kibot-security

echo "v7.0 Services Installed Successfully."
