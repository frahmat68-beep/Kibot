#!/bin/bash
# KiCryp Emergency Fix & Push Script
# Purpose: Resolve CI/CD port mismatch and deployment failures.

BASE_DIR="/Users/kiki/Documents/Web Develop/KiCryp"
cd "$BASE_DIR"

echo "🚀 Starting Emergency Fix and Push..."

# 1. Verify files exist
if [ ! -f ".github/workflows/deploy-kidax.yml" ]; then
    echo "❌ Error: Could not find deploy-kidax.yml. Are you in the right directory?"
    exit 1
fi

# 2. Add all local changes (fixes already applied by Antigravity)
git add .

# 3. Create a clean commit
COMMIT_MSG="fix: resolve CI/CD port mismatch and deployment brittleness

- Removed hardcoded KICRYP_DASHBOARD_PORT and MAC_ENGINE_PORT from .service files
- Updated workflows to manage MAC_ENGINE_PORT correctly via .env
- Added detailed logging and netstat check on health check failure
- Fixed KiDax using port 8787 in workflow but 8788 in service"

git commit -m "$COMMIT_MSG" || echo "⚠️ Nothing to commit (already committed?)"

echo "------------------------------------------------"
echo "📡 Attempting to push to GitHub..."
echo "If this fails with 'Device not configured', please run this in Terminal.app:"
echo "cd '$BASE_DIR' && git push origin main"
echo "------------------------------------------------"

git push origin main

if [ $? -eq 0 ]; then
    echo "✅ SUCCESS! Changes pushed to GitHub."
    echo "Check actions here: https://github.com/frahmat68-beep/Kibot/actions"
else
    echo "❌ PUSH FAILED."
    echo "This is likely a macOS sandbox restriction. PLEASE OPEN YOUR TERMINAL and run:"
    echo "cd \"$BASE_DIR\" && git push origin main"
fi
