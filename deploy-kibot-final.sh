#!/bin/bash
# 🚀 DEPLOY KICRYP KE BINANCE - COPY PASTE VERSION
# Jalanin dari /Users/kiki/Documents/Web\ Develop/KiCryp

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
sudo systemctl stop kicryp-manager || true
sudo systemctl disable kicryp-manager || true
sudo rm -f /etc/systemd/system/kicryp-manager.service
sudo rm -rf /etc/systemd/system/kicryp-manager.service.d
sudo systemctl daemon-reload
echo "✅ Legacy kicryp-manager removed from Binance"
REMOTE

echo "✅ Setup OK"

echo ""
echo "✅ STEP 5: PASTIKAN KICRYP ENGINE DI ORACLE"
echo "=========================================="
ssh -i SSH_INDODAX/ssh-key-2026-03-22.key ubuntu@213.35.118.26 \
  "sudo systemctl enable kicryp-engine && sudo systemctl restart kicryp-engine && systemctl is-active kicryp-engine"

echo ""
echo "✅ STEP 6: STOP LEGACY KICRYP MANAGER"
echo "===================================="
ssh -i SSH_INDODAX/ssh-key-2026-03-22.key ubuntu@213.35.118.26 << 'REMOTE2'
sudo systemctl stop kicryp-manager
sudo systemctl disable kicryp-manager
sudo rm -f /etc/systemd/system/kicryp-manager.service
sudo rm -rf /etc/systemd/system/kicryp-manager.service.d
sudo systemctl daemon-reload
echo "✅ Legacy kicryp-manager removed from Indodax"
REMOTE2

echo ""
echo "✅ STEP 7: VERIFY FINAL STATUS"
echo "=============================="
echo ""
echo "📍 INDODAX:"
ssh -i SSH_INDODAX/ssh-key-2026-03-22.key ubuntu@213.35.118.26 \
  "echo -n 'KiDax: '; systemctl is-active kidax-engine; echo -n 'KiCryp: '; systemctl is-active kicryp-engine || echo 'inactive'"

echo ""
echo "📍 BINANCE:"
ssh -i SSH_BINANCE/ssh-key-2026-03-27.key ubuntu@152.69.218.198 \
  "echo -n 'Kinance: '; systemctl is-active kinance-engine; echo -n 'KiCryp legacy: '; systemctl is-active kicryp-manager || echo 'inactive'"

echo ""
echo "========================================"
echo "✅ DEPLOY SELESAI!"
echo "========================================"
echo ""
echo "📊 HASIL:"
echo "  ✅ kicryp-engine aktif di Oracle"
echo "  ✅ Legacy kicryp-manager dimatikan"
echo "  ✅ RAM Binance fokus ke Kinance"
echo ""
echo "🔄 Alur Trading:"
echo "  Kinance (scan) → KiCryp Engine (veto) → KiDax (entry/exit)"
echo ""
