#!/bin/bash

# Setup automatic recovery for KiCryp
# Run this script after server is online

set -e

echo "Setting up KiCryp automatic recovery..."

mkdir -p /home/ubuntu/KiCryp/server

# Copy service file
sudo cp /home/ubuntu/KiCryp/kicryp-engine.service /etc/systemd/system/
sudo chmod 644 /etc/systemd/system/kicryp-engine.service

# Reload systemd
sudo systemctl daemon-reload

# Enable and start service
sudo systemctl enable kicryp-engine
sudo systemctl stop kicryp.service || true
sudo systemctl disable kicryp.service || true
sudo rm -f /etc/systemd/system/kicryp.service
sudo systemctl stop kicryp-engine || true
sudo pkill -f 'gradle.*:apps:mac-engine:run' || true
sudo pkill -f '/home/ubuntu/KiCryp/apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar' || true
sudo pkill -f '/home/ubuntu/mac-engine-0.1.0-all.jar' || true
sudo pkill -f '/home/ubuntu/KiCryp/server/mac-engine-all.jar' || true
sudo fuser -k "${KICRYP_PORT:-8789}"/tcp || true
sudo systemctl daemon-reload
sudo systemctl start kicryp-engine

# Check service status
sudo systemctl status kicryp-engine --no-pager

sudo tee /etc/systemd/system/kicryp-recovery.service >/dev/null <<'EOF'
[Unit]
Description=KiCryp recovery watchdog
After=network-online.target kicryp-engine.service
Wants=network-online.target kicryp-engine.service

[Service]
Type=oneshot
Environment=KICRYP_RUNTIME_ROOT=/home/ubuntu/KiCryp
Environment=KICRYP_SERVICE_NAME=kicryp-engine
Environment=KICRYP_DASHBOARD_PORT=8789
Environment=KICRYP_ENV_FILE=/home/ubuntu/KiCryp/.env.kicryp
Environment=KICRYP_EXPECT_LIVE_EXECUTION=true
ExecStart=/home/ubuntu/KiCryp/kicryp-recovery.sh
EOF

sudo tee /etc/systemd/system/kicryp-recovery.timer >/dev/null <<'EOF'
[Unit]
Description=KiCryp recovery watchdog timer

[Timer]
OnBootSec=20s
OnUnitActiveSec=45s
AccuracySec=5s
Persistent=true
Unit=kicryp-recovery.service

[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now kicryp-recovery.timer

if crontab -l 2>/dev/null | grep -vF "/home/ubuntu/KiCryp/kicryp-recovery.sh" | sed '/^$/d' | crontab - 2>/dev/null; then
  echo "Legacy recovery cron removed"
else
  echo "No legacy recovery cron found"
fi

AI_CRON_JOB="5 * * * * /home/ubuntu/KiCryp/scripts/ai_learning_cycle.sh"
(crontab -l 2>/dev/null; echo "$AI_CRON_JOB") | awk '!seen[$0]++' | crontab -

# Make recovery script executable
chmod +x /home/ubuntu/KiCryp/kicryp-recovery.sh
chmod +x /home/ubuntu/KiCryp/setup-autorecover.sh
chmod +x /home/ubuntu/KiCryp/scripts/ai_learning_cycle.sh

echo "Setup complete! KiCryp will auto-restart if it crashes."
echo "Dashboard should be available at http://<oracle-ip>:8787"
