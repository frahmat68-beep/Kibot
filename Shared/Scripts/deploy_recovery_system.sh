#!/bin/bash
# deploy_recovery_system.sh - Deploy all fixes to production

set -e

echo "🚀 TRINITY RECOVERY SYSTEM DEPLOYMENT"
echo "======================================"

# Configuration
INDODAX_SERVER="213.35.118.26"
BINANCE_SERVER="152.69.218.198"
INDODAX_KEY="SSH_INDODAX/ssh-key-2026-03-22.key"
BINANCE_KEY="SSH_BINANCE/ssh-key-2026-03-27.key"

# Step 1: Build
echo -e "\n[1/6] Building mac-engine fat JAR..."
./gradlew clean :apps:mac-engine:fatJar -q || {
    echo "❌ Build failed!"
    exit 1
}
JAR_FILE="apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "❌ JAR not found: $JAR_FILE"
    exit 1
fi
echo "✅ Build successful: $JAR_FILE"

# Step 2: Deploy to Indodax
echo -e "\n[2/6] Deploying to Indodax server (213.35.118.26)..."
scp -i "$INDODAX_KEY" "$JAR_FILE" ubuntu@$INDODAX_SERVER:~/KiBot/
echo "✅ Indodax deployment complete"

# Step 3: Deploy to Binance
echo -e "\n[3/6] Deploying to Binance server (152.69.218.198)..."
scp -i "$BINANCE_KEY" "$JAR_FILE" ubuntu@$BINANCE_SERVER:~/KiBot/
echo "✅ Binance deployment complete"

# Step 4: Restart Indodax services
echo -e "\n[4/6] Restarting Indodax services..."
ssh -i "$INDODAX_KEY" ubuntu@$INDODAX_SERVER << 'EOF'
    echo "Reloading daemon..."
    sudo -n systemctl daemon-reload
    echo "Stopping KiBot..."
    sudo -n systemctl stop kibot-executor-indodax || true
    sleep 2
    echo "Starting KiBot..."
    sudo -n systemctl start kibot-executor-indodax
    sleep 3
    systemctl status kibot-executor-indodax --no-pager || echo "Status check failed"
    echo "✅ KiBot restarted"
    
    echo "✅ KiBot restarted"
EOF

# Step 5: Restart Binance services
echo -e "\n[5/6] Restarting Binance services..."
ssh -i "$BINANCE_KEY" ubuntu@$BINANCE_SERVER << 'EOF'
    echo "Reloading daemon..."
    sudo -n systemctl daemon-reload
    echo "Stopping KiBot..."
    sudo -n systemctl stop kibot-executor-indodax || true
    sleep 2
    echo "Starting KiBot..."
    sudo -n systemctl start kibot-executor-indodax
    sleep 3
    systemctl status kibot-executor-indodax --no-pager || echo "Status check failed"
    echo "✅ KiBot restarted"
EOF

# Step 6: Verify deployment
echo -e "\n[6/6] Verifying deployment..."
sleep 5

echo -e "\n📊 KiBot Status:"
ssh -i "$INDODAX_KEY" ubuntu@$INDODAX_SERVER 'curl -fsS http://localhost:8787/api/state | python3 -c "import sys, json; d=json.load(sys.stdin); print(f\"  Equity: Rp{d.get(\"portfolioValueIdr\", 0)}\"); print(f\"  Free: Rp{d.get(\"freeIdrLabel\", 0)}\"); print(f\"  Status: {d.get(\"effectiveState\", \"unknown\")}\"); print(f\"  AI: {d.get(\"aiProviderSummary\", \"unknown\")}\")" 2>/dev/null || echo "  (API not yet ready)"'

echo -e "\n📊 KiBot Status:"
ssh -i "$BINANCE_KEY" ubuntu@$BINANCE_SERVER 'curl -fsS http://localhost:8788/api/state | python3 -c "import sys, json; d=json.load(sys.stdin); print(f\"  Status: {d.get(\"effectiveState\", \"unknown\")}\"); print(f\"  Scan Universe: {d.get(\"scanUniverseCount\", 0)}\"); print(f\"  Top Candidate: {d.get(\"topCandidate\", \"N/A\")}\")" 2>/dev/null || echo "  (API not yet ready)"'

echo -e "\n✅ DEPLOYMENT COMPLETE!"
echo "======================================"
echo "Recovery system deployed to both servers."
echo ""
echo "Monitor logs:"
echo "  KiBot:   ssh -i $INDODAX_KEY ubuntu@$INDODAX_SERVER 'journalctl -u kibot-executor-indodax -f'"
echo "  KiBot: ssh -i $BINANCE_KEY ubuntu@$BINANCE_SERVER 'journalctl -u kibot-executor-indodax -f'"
echo ""
echo "Check Indodax API:"
echo "  curl http://$INDODAX_SERVER:8787/api/state | python3 -m json.tool"
echo ""
echo "Recovery targets:"
echo "  - Exit stagnan coins (TRX, XLM)"
echo "  - Deploy idle capital (Rp41k+)"
echo "  - Detect pump signals"
echo "  - Reduce loss, aim for break-even"
