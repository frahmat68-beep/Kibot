#!/bin/bash
# KiBot Trinity v6.0 — Automated Manual Deployment Helper
# Repo: https://github.com/frahmat68-beep/Kibot

INDODAX_IP="213.35.118.26"
BINANCE_IP="152.69.218.198"
REMOTE_USER="ubuntu"
REMOTE_DIR="/home/ubuntu/KiBot/scripts"

INDODAX_KEY="SSH_INDODAX/ssh-key-2026-03-22.key"
BINANCE_KEY="SSH_BINANCE/ssh-key-2026-03-27.key"

FILES=(
    "scripts/kibot_manager.py"
    "scripts/dashboard_template.py"
    "scripts/kibot_whatif_engine.py"
    "scripts/kibot_timeframe_analyzer.py"
    "scripts/kibot_learning_engine.py"
)

echo "🚀 Starting KiBot v6.0 Deployment..."

deploy_to_server() {
    local ip=$1
    local name=$2
    local key=$3
    echo "--------------------------------------------------"
    echo "📦 Deploying to $name ($ip)..."
    
    # Check if key exists
    if [ ! -f "$key" ]; then
        echo "   ❌ Error: Key file $key not found!"
        return
    fi

    # 1. SCP transmission
    for file in "${FILES[@]}"; do
        if [ -f "$file" ]; then
            echo "   Uploading $file..."
            scp -o ConnectTimeout=5 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i "$key" "$file" "$REMOTE_USER@$ip:$REMOTE_DIR/"
        else
            echo "   ⚠️ Warning: $file not found locally."
        fi
    done

    # 2. Remote Restart
    echo "   🔄 Restarting kibot-engine service..."
    ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i "$key" "$REMOTE_USER@$ip" "sudo systemctl restart kibot-engine && sudo systemctl status kibot-engine --no-pager"
    echo "   ✅ $name Deployment Complete!"
}

# Main Execution
deploy_to_server "$INDODAX_IP" "INDODAX SERVER" "$INDODAX_KEY"
deploy_to_server "$BINANCE_IP" "BINANCE SERVER" "$BINANCE_KEY"

echo "--------------------------------------------------"
echo "🎉 Trinity v6.0 Deployment Finished!"
echo "Check your dashboard at http://$INDODAX_IP:8787"
