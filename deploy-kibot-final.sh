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
echo "✅ STEP 4: CLEANUP LEGACY MANAGER DI BINANCE"
echo "============================================="
ssh -i SSH_BINANCE/ssh-key-2026-03-27.key ubuntu@152.69.218.198 << 'REMOTE'
set -e
sudo systemctl stop kibot-manager || true
sudo systemctl disable kibot-manager || true
sudo rm -f /etc/systemd/system/kibot-manager.service
sudo rm -rf /etc/systemd/system/kibot-manager.service.d
sudo systemctl daemon-reload
echo "✅ Legacy kibot-manager removed from Binance"
REMOTE

echo "✅ Setup OK"

echo ""
echo "✅ STEP 5: PASTIKAN KIBOT ENGINE DI ORACLE"
echo "=========================================="
ssh -i SSH_INDODAX/ssh-key-2026-03-22.key ubuntu@213.35.118.26 \
  "sudo systemctl enable kibot-engine && sudo systemctl restart kibot-engine && systemctl is-active kibot-engine"

echo ""
echo "✅ STEP 6: STOP LEGACY KIBOT MANAGER"
echo "===================================="
ssh -i SSH_INDODAX/ssh-key-2026-03-22.key ubuntu@213.35.118.26 << 'REMOTE2'
sudo systemctl stop kibot-manager
sudo systemctl disable kibot-manager
sudo rm -f /etc/systemd/system/kibot-manager.service
sudo rm -rf /etc/systemd/system/kibot-manager.service.d
sudo systemctl daemon-reload
echo "✅ Legacy kibot-manager removed from Indodax"
REMOTE2

echo ""
echo "✅ STEP 7: VERIFY FINAL STATUS"
echo "=============================="
echo ""
echo "📍 INDODAX:"
ssh -i SSH_INDODAX/ssh-key-2026-03-22.key ubuntu@213.35.118.26 \
  "echo -n 'KiDax: '; systemctl is-active kidax-engine; echo -n 'KiBot: '; systemctl is-active kibot-engine || echo 'inactive'"

echo ""
echo "📍 BINANCE:"
ssh -i SSH_BINANCE/ssh-key-2026-03-27.key ubuntu@152.69.218.198 \
  "echo -n 'Kinance: '; systemctl is-active kinance-engine; echo -n 'KiBot legacy: '; systemctl is-active kibot-manager || echo 'inactive'"

echo ""
echo "========================================"
echo "✅ DEPLOY SELESAI!"
echo "========================================"
echo ""
echo "📊 HASIL:"
echo "  ✅ kibot-engine aktif di Oracle"
echo "  ✅ Legacy kibot-manager dimatikan"
echo "  ✅ RAM Binance fokus ke Kinance"
echo ""
echo "🔄 Alur Trading:"
echo "  Kinance (scan) → KiBot Engine (veto) → KiDax (entry/exit)"
echo ""
