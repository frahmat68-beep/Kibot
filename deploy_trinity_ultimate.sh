#!/bin/bash
# KIBOT TRINITY ULTIMATE DEPLOYMENT SCRIPT
# Run this on the Production Server (Oracle Cloud SG / Tokyo)

set -e

echo "🚀 Starting KiBot Trinity Ultimate Deployment..."

# 1. Pull latest changes
echo "📥 pulling changes from GitHub..."
git pull origin main

# 2. Build MacEngine Daemon (Fat JAR)
echo "🔨 Building MacEngine fatJar..."
chmod +x gradlew
./gradlew :apps:mac-engine:fatJar

# 3. Stop running services
echo "🛑 Stopping current bot services..."
sudo systemctl stop kidax-engine || true
sudo systemctl stop kibot-manager || true

# 4. Copy new build to runtime location
echo "📂 Deploying new build..."
cp apps/mac-engine/build/libs/mac-engine-all.jar /home/ubuntu/KiBot/runtime/mac-engine.jar

# 5. Start services
echo "⚡ Restarting services..."
sudo systemctl start kidax-engine
sudo systemctl start kibot-manager

# 6. Verify Logs
echo "🔍 Verifying logs..."
sleep 2
echo "--- ENGINE LOGS ---"
sudo journalctl -u kidax-engine -n 20 --no-pager
echo "--- MANAGER LOGS ---"
sudo journalctl -u kibot-manager -n 20 --no-pager

echo "✅ TRINITY ULTIMATE IS LIVE!"
echo "Note: Monitor Telegram for (Real) PnL sync status."
