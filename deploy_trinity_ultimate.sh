#!/bin/bash
# KIBOT TRINITY ULTIMATE DEPLOYMENT SCRIPT
set -e
echo "🚀 Starting KiBot Trinity Ultimate Deployment..."
git pull origin main
chmod +x gradlew
./gradlew :apps:mac-engine:fatJar
sudo systemctl stop kidax-engine || true
sudo systemctl stop kibot-manager || true
cp apps/mac-engine/build/libs/mac-engine-all.jar /home/ubuntu/KiBot/runtime/mac-engine.jar
sudo systemctl start kidax-engine
sudo systemctl start kibot-manager
echo "✅ DEPLOYMENT COMPLETE"
