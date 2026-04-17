#!/usr/bin/env python3
"""
KiBot Orchestrator
==================
Coordinator that aggregates subsystem health without becoming a SPOF.
"""

from __future__ import annotations

import json
import os
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict

ROOT = Path(os.getenv("KIBOT_RUNTIME_ROOT", Path(__file__).resolve().parent.parent))
STATE_DIR = ROOT / "state"
ORCH_STATE = STATE_DIR / "orchestrator_state.json"
EVENTS_DIR = STATE_DIR / "events"

SUBSYSTEMS = {
    "kibot-manager": {"port": 9998, "critical": True},
    "kidax-engine": {"port": 8787, "critical": True},
    "kinance-engine": {"port": 8788, "critical": False},
    "kibot-analyst": {"port": None, "critical": False},
    "kibot-guardian": {"port": None, "critical": False},
    "kibot-auditor": {"port": None, "critical": False},
    "kibot-notifier": {"port": None, "critical": False},
    "kibot-orchestrator": {"port": None, "critical": False},
    "kibot-security": {"port": None, "critical": False},
}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def atomic_write(path: Path, data: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.tmp.{os.getpid()}")
    try:
        with open(tmp, "w", encoding="utf-8") as handle:
            json.dump(data, handle, indent=2)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()


def check_all_subsystems() -> Dict[str, Dict[str, Any]]:
    results: Dict[str, Dict[str, Any]] = {}
    for service, config in SUBSYSTEMS.items():
        try:
            proc = subprocess.run(["systemctl", "is-active", service], capture_output=True, text=True, timeout=5)
            results[service] = {"active": proc.stdout.strip() == "active", "critical": config["critical"], "port": config.get("port")}
        except Exception:
            results[service] = {"active": False, "critical": config["critical"], "port": config.get("port")}
    return results


def get_system_summary() -> Dict[str, Any]:
    summary: Dict[str, Any] = {"ts": now_iso(), "subsystems": check_all_subsystems()}
    for rel_path, key in [
        ("daily_guard.json", "trading_guard"),
        ("manager_gate.json", "trading_gate"),
        ("guardian_state.json", "server_health"),
        ("analyst/daily_summary.json", "daily_trading"),
    ]:
        try:
            summary[key] = json.loads((STATE_DIR / rel_path).read_text(encoding="utf-8"))
        except Exception:
            summary[key] = {}
    return summary


def run_orchestrator() -> None:
    print("[ORCH] KiBot Orchestrator started")
    EVENTS_DIR.mkdir(parents=True, exist_ok=True)
    while True:
        try:
            state = get_system_summary()
            atomic_write(ORCH_STATE, state)
            for service, status in state["subsystems"].items():
                if status.get("critical") and not status.get("active"):
                    atomic_write(
                        EVENTS_DIR / f"CRITICAL_SERVICE_DOWN_{int(time.time() * 1000)}.json",
                        {
                            "type": "CRITICAL_SERVICE_DOWN",
                            "service": service,
                            "ts": now_iso(),
                            "message": f"Critical service {service} is DOWN",
                            "severity": "CRITICAL",
                        },
                    )
            time.sleep(30)
        except Exception as error:
            print(f"[ORCH] Error: {error}")
            time.sleep(15)


if __name__ == "__main__":
    run_orchestrator()
