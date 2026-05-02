#!/usr/bin/env python3
import os
import sys
import json
from pathlib import Path

def check_file(path, required_text=None):
    p = Path(path)
    if not p.exists():
        print(f"❌ MISSING: {path}")
        return False
    if required_text:
        content = p.read_text()
        if required_text not in content:
            print(f"❌ INVALID: {path} (missing '{required_text}')")
            return False
    print(f"✅ OK: {path}")
    return True

def main():
    print("=== KiBot Trinity Pre-Flight Audit ===")
    
    # 1. Critical Files
    critical = [
        ("scripts/kibot_manager.py", "Fail-Soft v7.3.2"),
        ("scripts/kibot_manager.py", "KiBot_HEARTBEAT_TIMEOUT_SEC = 15.0"),
        ("infra/systemd/kibot-manager.service", "MemoryMax=1G"),
        ("infra/systemd/kibot-manager.service", "OOMScoreAdjust=-500"),
        ("scripts/kibot_polymarket.py", "avg_prob"),
    ]
    
    all_ok = True
    for path, text in critical:
        if not check_file(path, text):
            all_ok = False
            
    # 2. Environment (Local Presence)
    env_files = [".env.server", ".env.kibot", ".env.kibot_manager"]
    for f in env_files:
        if not Path(f).exists():
            print(f"⚠️  WARNING: {f} missing locally (ensure it exists on server)")

    if all_ok:
        print("\n🚀 ALL CRITICAL HARDENING VERIFIED. READY FOR DEPLOYMENT.")
        sys.exit(0)
    else:
        print("\n🛑 AUDIT FAILED. DO NOT DEPLOY.")
        sys.exit(1)

if __name__ == "__main__":
    main()
