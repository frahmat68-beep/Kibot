#!/usr/bin/env python3
import os
import sys
import socket
import json
import threading
import time
from pathlib import Path

# Load absolute path to .env
ENV_PATH = Path("/Users/kiki/Documents/Web Develop/KiBot/.env")

def load_env():
    if ENV_PATH.exists():
        for line in ENV_PATH.read_text().splitlines():
            if "=" in line and not line.startswith("#"):
                key, val = line.split("=", 1)
                os.environ[key.strip()] = val.strip()

def check_keys():
    keys = ["NVIDIA_API_KEY", "GEMINI_API_KEY", "GROQ_API_KEY", "TELEGRAM_BOT_TOKEN"]
    results = {}
    for k in keys:
        val = os.environ.get(k)
        results[k] = "PRESENT" if val and "__" not in val else "MISSING/PLACEHOLDER"
    return results

def check_manager_threads():
    # This is a simulation check. In a real environment, we'd check against the running process.
    # Here we verify the manager script has the necessary thread definitions.
    MANAGER_PATH = Path("/Users/kiki/Documents/Web Develop/KiBot/scripts/kibot_manager.py")
    content = MANAGER_PATH.read_text()
    checks = {
        "News Watchdog": "_news_watchdog_loop",
        "PnL Watchdog": "_pnl_watchdog_loop",
        "Log Maintenance": "_log_maintenance_loop",
        "Screener": "_pair_screen_loop",
        "AI Review": "_ai_batch_review_loop"
    }
    results = {}
    for name, func in checks.items():
        results[name] = "FOUND" if func in content else "NOT FOUND"
    return results

def main():
    print("=== Trinity v7.1 Integration Handshake ===")
    load_env()
    
    print("\n1. Key Provisoning:")
    for k, v in check_keys().items():
        print(f"  [{'OK' if v == 'PRESENT' else '!!'}] {k}: {v}")
        
    print("\n2. Watchdog Definitions (Manager Source):")
    for k, v in check_manager_threads().items():
        print(f"  [{'OK' if v == 'FOUND' else '!!'}] {k}: {v}")
        
    print("\n3. Network Bindings (Check UDP port 8787):")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.bind(("127.0.0.1", 8787))
        print("  [OK] Port 8787 is available for binding.")
        sock.close()
    except Exception as e:
        print(f"  [!!] Port 8787 binding failed: {e}")

    print("\n=== Integration Check Complete ===")

if __name__ == "__main__":
    main()
