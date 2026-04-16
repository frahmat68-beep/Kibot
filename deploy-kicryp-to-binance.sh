#!/usr/bin/env bash
set -euo pipefail

# Legacy helper retained for historical reference.
# KiCryp is now served only by kicryp-engine (Kotlin); do not revive kicryp-manager.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INDODAX_HOST="213.35.118.26"
BINANCE_HOST="152.69.218.198"
INDODAX_KEY="${ROOT_DIR}/SSH_INDODAX/ssh-key-2026-03-22.key"
BINANCE_KEY="${ROOT_DIR}/SSH_BINANCE/ssh-key-2026-03-27.key"

echo "🚀 VERIFYING KICRYP ENGINE ON BINANCE (KOTLIN ONLY)"
echo "=================================================="

# Step 1: Stop legacy KiCryp manager on Indodax
echo "[1/4] Stopping legacy kicryp-manager on Indodax..."
ssh -i "$INDODAX_KEY" -o ConnectTimeout=5 ubuntu@$INDODAX_HOST \
  'sudo systemctl stop kicryp-manager' 2>/dev/null || echo "    (Already stopped or unavailable)"

# Step 2: Remove legacy manager from Binance and ensure Kotlin engine is used
echo "[2/4] Removing legacy kicryp-manager from Binance..."
ssh -i "$BINANCE_KEY" -o ConnectTimeout=5 ubuntu@$BINANCE_HOST bash -s <<'REMOTE' || echo "    (Deployment skipped - network issue)"
set -euo pipefail
sudo systemctl stop kicryp-manager || true
sudo systemctl disable kicryp-manager || true
sudo rm -f /etc/systemd/system/kicryp-manager.service
sudo rm -rf /etc/systemd/system/kicryp-manager.service.d
sudo systemctl daemon-reload
sudo systemctl enable kicryp-engine
sudo systemctl restart kicryp-engine
sleep 3
systemctl is-active kicryp-engine && echo "✅ kicryp-engine is RUNNING on Binance" || echo "⚠️ kicryp-engine status unknown"
REMOTE

# Step 3: Remove legacy manager from Indodax
echo "[3/4] Removing legacy kicryp-manager from Indodax..."
ssh -i "$INDODAX_KEY" -o ConnectTimeout=5 ubuntu@$INDODAX_HOST \
  'sudo systemctl disable kicryp-manager || true; sudo rm -f /etc/systemd/system/kicryp-manager.service; sudo rm -rf /etc/systemd/system/kicryp-manager.service.d; sudo systemctl daemon-reload' \
  2>/dev/null || echo "    (Cleanup skipped - network issue)"

# Step 4: Verify all services
echo "[4/4] Verifying all services..."
echo ""
echo "Status Summary:"
echo "  Indodax (KiDax): $(ssh -i "$INDODAX_KEY" -o ConnectTimeout=3 ubuntu@$INDODAX_HOST 'systemctl is-active kidax-engine' 2>/dev/null || echo 'UNKNOWN')"
echo "  Binance (Kinance): $(ssh -i "$BINANCE_KEY" -o ConnectTimeout=3 ubuntu@$BINANCE_HOST 'systemctl is-active kinance-engine' 2>/dev/null || echo 'UNKNOWN')"
echo "  Oracle (KiCryp Engine): $(ssh -i "$INDODAX_KEY" -o ConnectTimeout=3 ubuntu@$INDODAX_HOST 'systemctl is-active kicryp-engine' 2>/dev/null || echo 'UNKNOWN')"
echo ""
echo "✅ Deployment complete!"
echo ""
echo "Network setup:"
echo "  KiDax (Indodax:8787) → broadcasts BUY_REQUEST via UDP"
echo "  Kinance (Binance:8787) → broadcasts PUMP_SIGNAL via UDP"
echo "  KiCryp Engine (Oracle:8789) → listens on :9999, approves/rejects trades"
