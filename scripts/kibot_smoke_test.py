#!/usr/bin/env python3
import os
import sys

def smoke_test():
    """
    KiBot v7.0 Runtime Smoke Test
    Checks the local environment for readiness before deployment/startup.
    """
    print("\n" + "="*50)
    print(" 🛡️  KIBOT TRINITY v7.0 - SMOKE TEST")
    print("="*50)

    # 1. Check Python Version (Target 3.9+)
    version = sys.version_info
    print(f"🐍 Python Version: {version.major}.{version.minor}.{version.micro}")
    if version.major < 3 or (version.major == 3 and version.minor < 9):
        print("❌ CRITICAL: Python 3.9.6 or higher is required.")
        sys.exit(1)

    # 2. Check Core Directory Structure
    required_dirs = ["state", "scripts", "logs"]
    for d in required_dirs:
        if not os.path.exists(d):
            print(f"⚠️ Directory '{d}' missing. Attempting to create...")
            try:
                os.makedirs(d, exist_ok=True)
                print(f"✅ Created '{d}'")
            except Exception as e:
                print(f"❌ FAILED to create '{d}': {e}")
                sys.exit(1)

    # 3. Check Environment Variables (Presence Only)
    # Note: Values are checked inside the engine, but we want to fail fast.
    important_vars = [
        "SUPABASE_URL", 
        "SUPABASE_ANON_KEY", 
        "TELEGRAM_BOT_TOKEN", 
        "TELEGRAM_USER_ID"
    ]
    
    env_ok = True
    for v in important_vars:
        if not os.environ.get(v):
            # Check if we are potentially in CI where these might be masked but present
            # or if they are in a local .env.kibot (this check doesn't parse files)
            print(f"❓ WARNING: {v} not found in shell environment.")
            env_ok = False
    
    if not env_ok:
        print("ℹ️ Note: This warning is normal if you haven't sourced your .env file yet.")

    # 4. Check critical script availability
    core_scripts = ["scripts/kibot_manager.py", "scripts/kibot_engine_v2.py"]
    for s in core_scripts:
        if not os.path.exists(s):
            print(f"❌ CRITICAL: Missing core script: {s}")
            sys.exit(1)
    
    print("="*50)
    print("✅ SMOKE TEST PASSED: Core structure and scripts verified.")
    print("="*50 + "\n")

if __name__ == "__main__":
    smoke_test()
