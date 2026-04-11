#!/bin/bash
# KiBot Trinity - Staging Deployment Script
# Purpose: Deploy TIER 1 complete system to Oracle staging environment

set -e

echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║                                                                            ║"
echo "║              KiBot Trinity - Staging Deployment (PHASE A)                  ║"
echo "║                                                                            ║"
echo "║                  TIER 1 Complete + Daily Profit Ready                      ║"
echo "║                                                                            ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""

# Configuration
REPO_ROOT="$(pwd)"
STAGING_BRANCH="staging"
MAIN_BRANCH="main"
DEPLOY_USER="${DEPLOY_USER:-root}"
DEPLOY_HOST="${DEPLOY_HOST:-staging.kibot.oracle}"
DEPLOY_PATH="/opt/kibot/trinity"

echo "📋 PRE-DEPLOYMENT CHECKLIST"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "Current Status:"
git status --short
echo ""
echo "Recent Commits:"
git log --oneline -5
echo ""

# Prompt for confirmation
read -p "✅ Continue with deployment? (y/n) " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "❌ Deployment cancelled"
    exit 1
fi

echo ""
echo "📦 STEP 1: Build System"
echo "════════════════════════════════════════════════════════════════════════════"
echo "Building Kotlin/JVM components..."
./gradlew clean build -x test 2>&1 | tail -20
BUILD_STATUS=${PIPESTATUS[0]}

if [ $BUILD_STATUS -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi
echo "✅ Build successful"

echo ""
echo "🌿 STEP 2: Prepare Staging Branch"
echo "════════════════════════════════════════════════════════════════════════════"

# Create or update staging branch
if git show-ref --quiet refs/heads/$STAGING_BRANCH; then
    echo "Switching to existing staging branch..."
    git checkout $STAGING_BRANCH
    git pull origin $STAGING_BRANCH
else
    echo "Creating new staging branch..."
    git checkout -b $STAGING_BRANCH
fi

# Merge latest TIER 1 features
echo "Merging blackboxai/fix-problems-phase1 → staging..."
git merge blackboxai/fix-problems-phase1 -m "Merge TIER 1: Emergency stop, 12h timeout, alerts, state recovery"
echo "✅ Merge successful"

echo ""
echo "🚀 STEP 3: Push to Remote"
echo "════════════════════════════════════════════════════════════════════════════"
git push origin $STAGING_BRANCH
echo "✅ Pushed to remote"

echo ""
echo "🏗️  STEP 4: Deploy to Staging Server"
echo "════════════════════════════════════════════════════════════════════════════"
echo "Deploying to: $DEPLOY_HOST"
echo ""

# SSH deployment command
ssh $DEPLOY_USER@$DEPLOY_HOST << 'DEPLOY_SCRIPT'
set -e

echo "📂 Preparing deployment directory..."
cd /opt/kibot/trinity || mkdir -p /opt/kibot/trinity && cd /opt/kibot/trinity

echo "📥 Pulling latest code..."
git fetch origin
git checkout staging
git pull origin staging

echo "🔨 Building on server..."
./gradlew clean build -x test

echo "🛑 Stopping existing services..."
systemctl stop kinance-engine.service || true
systemctl stop kidax-engine.service || true
systemctl stop kibot-manager.service || true
systemctl stop kibot-engine.service || true
sleep 2

echo "📦 Installing artifacts..."
cp build/libs/*.jar /opt/kibot/trinity/lib/ 2>/dev/null || true
cp scripts/*.py /opt/kibot/trinity/scripts/ || true

echo "🟢 Starting services..."
systemctl start kibot-engine.service
sleep 3
systemctl start kinance-engine.service
sleep 3
systemctl start kidax-engine.service
sleep 3

echo "✅ Services started"
sleep 5

echo "🔍 Checking service status..."
systemctl status kibot-engine.service --no-pager | head -5
systemctl status kinance-engine.service --no-pager | head -5
systemctl status kidax-engine.service --no-pager | head -5

echo "📊 Initial system check..."
curl -s http://localhost:8787/health || echo "KiDax health endpoint not responding yet"
curl -s http://localhost:8788/health || echo "Kinance health endpoint not responding yet"

DEPLOY_SCRIPT

DEPLOY_STATUS=$?
if [ $DEPLOY_STATUS -ne 0 ]; then
    echo "❌ Deployment failed!"
    exit 1
fi

echo ""
echo "✅ Deployment Complete!"
echo ""
echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║                                                                            ║"
echo "║                   STAGING DEPLOYMENT SUCCESSFUL ✅                         ║"
echo "║                                                                            ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "🔍 NEXT STEPS:"
echo "─────────────"
echo "1. Monitor logs: ssh $DEPLOY_USER@$DEPLOY_HOST 'tail -f /var/log/kibot/*.log'"
echo "2. Check alerts: Monitor your Telegram for bot status updates"
echo "3. Run tests: ./scripts/test-staging.sh"
echo "4. Monitor 1+ hour before moving to production"
echo ""
echo "📊 Commands:"
echo "   Check status:    ssh $DEPLOY_USER@$DEPLOY_HOST 'systemctl status kibot-*'"
echo "   View logs:       ssh $DEPLOY_USER@$DEPLOY_HOST 'journalctl -u kibot-engine'"
echo ""
