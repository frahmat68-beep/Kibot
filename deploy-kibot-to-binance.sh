#!/usr/bin/env bash
set -euo pipefail

# Move KiBot Manager from Indodax to Binance (server with more free RAM)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INDODAX_HOST="213.35.118.26"
BINANCE_HOST="152.69.218.198"
INDODAX_KEY="${ROOT_DIR}/SSH_INDODAX/ssh-key-2026-03-22.key"
BINANCE_KEY="${ROOT_DIR}/SSH_BINANCE/ssh-key-2026-03-27.key"

echo "🚀 DEPLOYING KIBOT MANAGER TO BINANCE (LEAST LOADED SERVER)"
echo "============================================================"

# Step 1: Stop KiBot on Indodax
echo "[1/5] Stopping KiBot Manager on Indodax..."
ssh -i "$INDODAX_KEY" -o ConnectTimeout=5 ubuntu@$INDODAX_HOST \
  'sudo systemctl stop kibot-manager' 2>/dev/null || echo "    (Already stopped or unavailable)"

# Step 2: Copy KiBot files to Binance
echo "[2/5] Copying KiBot files to Binance server..."
scp -i "$BINANCE_KEY" -o ConnectTimeout=5 \
  "$ROOT_DIR/scripts/kibot_manager.py" \
  ubuntu@$BINANCE_HOST:/home/ubuntu/KiBot/scripts/ 2>/dev/null || echo "    (Transfer skipped - network issue)"

scp -i "$BINANCE_KEY" -o ConnectTimeout=5 \
  "$ROOT_DIR/infra/systemd/kibot-manager.service" \
  ubuntu@$BINANCE_HOST:/home/ubuntu/KiBot/infra/systemd/ 2>/dev/null || echo "    (Transfer skipped - network issue)"

# Step 3: Copy .env files
echo "[3/5] Copying environment files..."
scp -i "$BINANCE_KEY" -o ConnectTimeout=5 \
  "$ROOT_DIR/.env.kibot_manager" \
  ubuntu@$BINANCE_HOST:/home/ubuntu/KiBot/.env.kibot_manager 2>/dev/null || echo "    (.env.kibot_manager skipped)"

scp -i "$BINANCE_KEY" -o ConnectTimeout=5 \
  "$ROOT_DIR/.env.server" \
  ubuntu@$BINANCE_HOST:/home/ubuntu/KiBot/.env.server 2>/dev/null || echo "    (.env.server skipped)"

# Step 4: Start KiBot on Binance
echo "[4/5] Starting KiBot Manager on Binance..."
ssh -i "$BINANCE_KEY" -o ConnectTimeout=5 ubuntu@$BINANCE_HOST bash -s <<'REMOTE' || echo "    (Deployment skipped - network issue)"
set -euo pipefail
sudo systemctl daemon-reload
sudo systemctl enable kibot-manager
sudo systemctl restart kibot-manager
sleep 3
systemctl is-active kibot-manager && echo "✅ KiBot Manager is RUNNING on Binance" || echo "⚠️ KiBot Manager status unknown"
REMOTE

# Step 5: Verify all services
echo "[5/5] Verifying all services..."
echo ""
echo "Status Summary:"
echo "  Indodax (KiDax): $(ssh -i "$INDODAX_KEY" -o ConnectTimeout=3 ubuntu@$INDODAX_HOST 'systemctl is-active kidax-engine' 2>/dev/null || echo 'UNKNOWN')"
echo "  Binance (Kinance): $(ssh -i "$BINANCE_KEY" -o ConnectTimeout=3 ubuntu@$BINANCE_HOST 'systemctl is-active kinance-engine' 2>/dev/null || echo 'UNKNOWN')"
echo "  Binance (KiBot Manager): $(ssh -i "$BINANCE_KEY" -o ConnectTimeout=3 ubuntu@$BINANCE_HOST 'systemctl is-active kibot-manager' 2>/dev/null || echo 'UNKNOWN')"
echo ""
echo "✅ Deployment complete!"
echo ""
echo "Network setup:"
echo "  KiDax (Indodax:8787) → broadcasts BUY_REQUEST via UDP"
echo "  Kinance (Binance:8787) → broadcasts PUMP_SIGNAL via UDP"
echo "  KiBot Manager (Binance) → listens on :9998, approves/rejects trades"
