#!/bin/bash

# Setup automatic recovery for KiBot
# Run this script after server is online

set -e

echo "Setting up KiBot automatic recovery..."

mkdir -p /home/ubuntu/KiBot/server

# Copy service file
sudo cp /home/ubuntu/KiBot/kibot-engine.service /etc/systemd/system/
sudo chmod 644 /etc/systemd/system/kibot-engine.service

# Reload systemd
sudo systemctl daemon-reload

# Enable and start service
sudo systemctl enable kibot-engine
sudo systemctl stop kibot.service || true
sudo systemctl disable kibot.service || true
sudo rm -f /etc/systemd/system/kibot.service
sudo systemctl stop kibot-engine || true
sudo pkill -f 'gradle.*:apps:mac-engine:run' || true
sudo pkill -f '/home/ubuntu/KiBot/apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar' || true
sudo pkill -f '/home/ubuntu/mac-engine-0.1.0-all.jar' || true
sudo pkill -f '/home/ubuntu/KiBot/server/mac-engine-all.jar' || true
sudo fuser -k "${KIBOT_PORT:-8789}"/tcp || true
sudo systemctl daemon-reload
sudo systemctl start kibot-engine

# Check service status
sudo systemctl status kibot-engine --no-pager

# Setup cron job for health check every 5 minutes
CRON_JOB="*/5 * * * * /home/ubuntu/KiBot/kibot-recovery.sh"
AI_CRON_JOB="5 * * * * /home/ubuntu/KiBot/scripts/ai_learning_cycle.sh"
(crontab -l 2>/dev/null; echo "$CRON_JOB"; echo "$AI_CRON_JOB") | awk '!seen[$0]++' | crontab -

echo "Cron job added:"
crontab -l | grep kibot

# Make recovery script executable
chmod +x /home/ubuntu/KiBot/kibot-recovery.sh
chmod +x /home/ubuntu/KiBot/setup-autorecover.sh
chmod +x /home/ubuntu/KiBot/scripts/ai_learning_cycle.sh

echo "Setup complete! KiBot will auto-restart if it crashes."
echo "Dashboard should be available at http://<oracle-ip>:8787"
