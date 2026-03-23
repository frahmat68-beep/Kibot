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
sudo systemctl start kibot-engine

# Check service status
sudo systemctl status kibot-engine --no-pager

# Setup cron job for health check every 5 minutes
CRON_JOB="*/5 * * * * /home/ubuntu/KiBot/kibot-recovery.sh"
(crontab -l 2>/dev/null; echo "$CRON_JOB") | crontab -

echo "Cron job added:"
crontab -l | grep kibot

# Make recovery script executable
chmod +x /home/ubuntu/KiBot/kibot-recovery.sh
chmod +x /home/ubuntu/KiBot/setup-autorecover.sh

echo "Setup complete! KiBot will auto-restart if it crashes."
echo "Dashboard should be available at http://<oracle-ip>:8787"
