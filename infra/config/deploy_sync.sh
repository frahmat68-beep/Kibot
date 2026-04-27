#!/bin/bash
# KiBot Trinity Deployment & Sync Tool
# Use this to sync local fixes to the remote production nodes.

TARGET_NODE=$1
KEY_PATH=$2

if [ -z "$TARGET_NODE" ] || [ -z "$KEY_PATH" ]; then
    echo "Usage: ./deploy_sync.sh <node_ip> <ssh_key_path>"
    exit 1
fi

echo "🚀 Syncing logic fixes to $TARGET_NODE..."

# 1. Sync .env (Sanitized)
scp -i "$KEY_PATH" .env ubuntu@$TARGET_NODE:/home/ubuntu/KiBot/.env

# 2. Sync Logic Scripts
scp -i "$KEY_PATH" core/kibot_manager.py ubuntu@$TARGET_NODE:/home/ubuntu/KiBot/core/kibot_manager.py
scp -i "$KEY_PATH" core/kibot_engine_v2.py ubuntu@$TARGET_NODE:/home/ubuntu/KiBot/core/kibot_engine_v2.py
scp -i "$KEY_PATH" core/kibot_auditor.py ubuntu@$TARGET_NODE:/home/ubuntu/KiBot/core/kibot_auditor.py

# 3. Restart Services
ssh -i "$KEY_PATH" ubuntu@$TARGET_NODE "sudo systemctl restart kibot-manager"

echo "✅ Deployment complete for $TARGET_NODE"
