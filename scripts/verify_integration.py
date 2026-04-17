#!/usr/bin/env python3
import os
import socket
from pathlib import Path

ROOT = Path(os.getenv("KIBOT_RUNTIME_ROOT", Path(__file__).resolve().parent.parent))
ENV_PATH = Path(os.getenv("KIBOT_MANAGER_ENV_FILE", ROOT / ".env"))
API_BASE = os.getenv("KIBOT_API_BASE", "http://127.0.0.1:8787")
MANAGER_PORT = int(os.getenv("KIBOT_MANAGER_PORT", "9998"))

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
    # Source-level validation: confirm expected watchdog loops are still present.
    manager_path = ROOT / "scripts" / "kibot_manager.py"
    content = manager_path.read_text(encoding="utf-8")
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


def check_runtime_endpoint():
    try:
        import urllib.request

        with urllib.request.urlopen(f"{API_BASE}/api/health", timeout=2) as response:
            return f"RUNNING ({response.status})"
    except Exception as exc:
        return f"NOT_REACHABLE ({exc})"


def check_udp_port():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.bind(("127.0.0.1", MANAGER_PORT))
        return "AVAILABLE"
    except Exception as exc:
        return f"IN_USE ({exc})"
    finally:
        sock.close()

def main():
    print("=== Trinity v7.1 Integration Handshake ===")
    load_env()
    
    print("\n1. Key Provisoning:")
    for k, v in check_keys().items():
        print(f"  [{'OK' if v == 'PRESENT' else '!!'}] {k}: {v}")
        
    print("\n2. Watchdog Definitions (Manager Source):")
    for k, v in check_manager_threads().items():
        print(f"  [{'OK' if v == 'FOUND' else '!!'}] {k}: {v}")
        
    print("\n3. Runtime Health:")
    runtime_status = check_runtime_endpoint()
    print(f"  [{'OK' if runtime_status.startswith('RUNNING') else '..'}] {API_BASE}/api/health: {runtime_status}")

    print("\n4. Manager UDP Port:")
    udp_status = check_udp_port()
    print(f"  [{'OK' if udp_status == 'AVAILABLE' else '..'}] UDP {MANAGER_PORT}: {udp_status}")

    print("\n=== Integration Check Complete ===")

if __name__ == "__main__":
    main()
