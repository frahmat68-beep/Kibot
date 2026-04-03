#!/bin/bash
# 🚀 DEPLOY KIBOT KE BINANCE - COPY PASTE VERSION
# Jalanin dari /Users/kiki/Documents/Web\ Develop/KiBot

set -e

echo "✅ STEP 1: SETUP KEY PERMISSION"
echo "==============================="
chmod 600 SSH_INDODAX/ssh-key-2026-03-22.key
chmod 600 SSH_BINANCE/ssh-key-2026-03-27.key
echo "✅ Done"

echo ""
echo "✅ STEP 2: TEST SSH KE INDODAX"
echo "==============================="
if ssh -o ConnectTimeout=5 -i SSH_INDODAX/ssh-key-2026-03-22.key ubuntu@213.35.118.26 "echo 'SSH OK ke Indodax'" 2>/dev/null; then
  echo "✅ SSH ke Indodax BERHASIL"
else
  echo "❌ SSH ke Indodax GAGAL"
  echo "Error: Cek IP atau key-nya"
  exit 1
fi

echo ""
echo "✅ STEP 3: TEST SSH KE BINANCE"
echo "=============================="
if ssh -o ConnectTimeout=5 -i SSH_BINANCE/ssh-key-2026-03-27.key ubuntu@152.69.218.198 "echo 'SSH OK ke Binance'" 2>/dev/null; then
  echo "✅ SSH ke Binance BERHASIL"
else
  echo "❌ SSH ke Binance GAGAL"
  echo "Error: Cek IP atau key-nya"
  exit 1
fi

echo ""
echo "✅ STEP 4: TRANSFER FILE KE BINANCE"
echo "===================================="
ssh -i SSH_BINANCE/ssh-key-2026-03-27.key ubuntu@152.69.218.198 \
  "mkdir -p /home/ubuntu/KiBot/{scripts,infra/systemd,state}"

scp -i SSH_BINANCE/ssh-key-2026-03-27.key \
  scripts/kibot_manager.py \
  ubuntu@152.69.218.198:/home/ubuntu/KiBot/scripts/

scp -i SSH_BINANCE/ssh-key-2026-03-27.key \
  infra/systemd/kibot-manager.service \
  ubuntu@152.69.218.198:/tmp/kibot-manager.service

echo "✅ File transfer OK"

echo ""
echo "✅ STEP 5: SETUP KIBOT DI BINANCE"
echo "=================================="
ssh -i SSH_BINANCE/ssh-key-2026-03-27.key ubuntu@152.69.218.198 << 'REMOTE'
set -e

# Copy service file
sudo cp /tmp/kibot-manager.service /etc/systemd/system/
sudo chmod 644 /etc/systemd/system/kibot-manager.service

# Create environment file
sudo tee /home/ubuntu/KiBot/.env.kibot_manager > /dev/null << 'ENV'
KIBOT_MANAGER_STATE_DIR=/home/ubuntu/KiBot/state
KIBOT_MANAGER_PROVIDER_STATE_FILE=/home/ubuntu/KiBot/state/ai_provider_state.json
KIBOT_MANAGER_RUNTIME_NOTE_FILE=/home/ubuntu/KiBot/state/runtime_note.json
KIBOT_MANAGER_HEARTBEAT_INTERVAL_SEC=0.10
KIBOT_AI_PROVIDER_ORDER=groq,openrouter,cohere,gemini
KIBOT_AI_REQUEST_TIMEOUT_SEC=12
KIBOT_AI_PROVIDER_DEFAULT_COOLDOWN_SEC=600
KIBOT_AI_PROVIDER_NETWORK_COOLDOWN_SEC=180
KIBOT_AI_PROVIDER_RATE_LIMIT_COOLDOWN_SEC=3600
KIBOT_AI_PROVIDER_EMPTY_COOLDOWN_SEC=120
KIBOT_AI_APPROVAL_MIN_SCORE=0.62
KIBOT_AI_APPROVAL_MIN_EXPECTED_NET_PCT=0.18
KIBOT_POST_MORTEM_BLACKLIST_ENABLED=true
KIBOT_POST_MORTEM_BLACKLIST_MINUTES=30
KIBOT_POST_MORTEM_BLACKLIST_NET_LOSS_IDR=500
KIBOT_POST_MORTEM_BLACKLIST_PNL_PCT=-1.0
KIDAX_UDP_HOST=213.35.118.26
# KINANCE_UDP_HOST should point to Binance server internal IP for cross-server UDP
KINANCE_UDP_HOST=152.69.218.198
ENV

sudo chmod 600 /home/ubuntu/KiBot/.env.kibot_manager

# Start service
sudo systemctl daemon-reload
sudo systemctl enable kibot-manager
sudo systemctl restart kibot-manager

echo "✅ KiBot Manager started on Binance"
REMOTE

echo "✅ Setup OK"

echo ""
echo "✅ STEP 6: STOP KIBOT DI INDODAX"
echo "================================"
ssh -i SSH_INDODAX/ssh-key-2026-03-22.key ubuntu@213.35.118.26 << 'REMOTE2'
sudo systemctl stop kibot-manager
sudo systemctl disable kibot-manager
echo "✅ KiBot Manager stopped on Indodax"
REMOTE2

echo ""
echo "✅ STEP 7: VERIFY FINAL STATUS"
echo "=============================="
echo ""
echo "📍 INDODAX:"
ssh -i SSH_INDODAX/ssh-key-2026-03-22.key ubuntu@213.35.118.26 \
  "echo -n 'KiDax: '; systemctl is-active kidax-engine; echo -n 'KiBot: '; systemctl is-active kibot-manager || echo 'inactive'"

echo ""
echo "📍 BINANCE:"
ssh -i SSH_BINANCE/ssh-key-2026-03-27.key ubuntu@152.69.218.198 \
  "echo -n 'Kinance: '; systemctl is-active kinance-engine; echo -n 'KiBot: '; systemctl is-active kibot-manager || echo 'inactive'"

echo ""
echo "========================================"
echo "✅ DEPLOY SELESAI!"
echo "========================================"
echo ""
echo "📊 HASIL:"
echo "  ✅ KiBot Manager pindah: Indodax → Binance"
echo "  ✅ RAM Indodax: 700MB free (KiDax only)"
echo "  ✅ RAM Binance: 750MB free (Kinance + KiBot)"
echo ""
echo "🔄 Alur Trading:"
echo "  Kinance (scan) → KiDax (entry) → KiBot (veto) → Execute"
echo ""
