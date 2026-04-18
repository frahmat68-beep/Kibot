#!/usr/bin/env python3
"""
KiBot Server Guardian
=====================
Infrastructure watchdog for Oracle Micro.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

try:
    import psutil  # type: ignore
except Exception:  # pragma: no cover - runtime fallback
    psutil = None

ROOT = Path(os.getenv("KIBOT_RUNTIME_ROOT", Path(__file__).resolve().parent.parent))
STATE_DIR = ROOT / "state"
EVENTS_DIR = STATE_DIR / "events"
LOGS_DIR = ROOT / "logs"
GUARDIAN_STATE = STATE_DIR / "guardian_state.json"

RAM_WARN_PCT = 80
RAM_CRITICAL_PCT = 90
DISK_WARN_PCT = 75
DISK_CRITICAL_PCT = 90
CPU_WARN_PCT = 90
CPU_WARN_SUSTAINED_S = 300
MAX_SERVICE_RESTARTS_PER_HOUR = 3
restart_counts: Dict[str, List[float]] = {}


def _parse_guardian_service_override(raw: str) -> List[str]:
    return [
        token.strip()
        for token in raw.split(",")
        if token.strip()
    ]


def resolve_services_to_guard() -> List[str]:
    override = os.getenv("KIBOT_GUARDIAN_SERVICES", "").strip()
    if override:
        parsed = _parse_guardian_service_override(override)
        if parsed:
            return parsed
    exchange_kind = (os.getenv("KIBOT_EXCHANGE_KIND") or "").strip().upper()
    bot_id = (os.getenv("BOT_ID") or "").strip().lower()
    profile_key = (os.getenv("BOT_PROFILE_KEY") or "").strip().lower()
    identity_hint = " ".join([exchange_kind.lower(), bot_id, profile_key])
    services = ["kibot-manager"]
    if exchange_kind == "INDODAX":
        services.append("kidax-engine")
    elif exchange_kind in {"BINANCE", "BINANCE_SPOT"}:
        services.append("kinance-engine")
    elif any(token in identity_hint for token in ("kinance", "binance")):
        services.append("kinance-engine")
    elif any(token in identity_hint for token in ("kidax", "indodax", "main")):
        services.append("kidax-engine")
    else:
        # Safe fallback for legacy nodes with mixed runtime roles.
        services.extend(["kidax-engine", "kinance-engine"])
    return services


SERVICES_TO_GUARD = resolve_services_to_guard()


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


def push_event(event_type: str, message: str, severity: str = "WARNING", data: Optional[Dict[str, Any]] = None) -> None:
    EVENTS_DIR.mkdir(parents=True, exist_ok=True)
    payload = {"type": event_type, "ts": now_iso(), "message": message, "severity": severity, "data": data or {}}
    atomic_write(EVENTS_DIR / f"{event_type}_{int(time.time() * 1000)}.json", payload)


def _fallback_memory_snapshot() -> Dict[str, Any]:
    try:
        output = subprocess.check_output(["/usr/bin/env", "sh", "-c", "free -m | awk 'NR==2 {print $2\" \"$3\" \"$7}'"], text=True).strip()
        total_mb, used_mb, available_mb = [int(part) for part in output.split()]
        used_pct = round((used_mb / max(total_mb, 1)) * 100, 1)
        return {"percent": used_pct, "available_mb": available_mb, "swap_used_mb": 0}
    except Exception:
        return {"percent": 0.0, "available_mb": 0, "swap_used_mb": 0}


def check_memory() -> Dict[str, Any]:
    if psutil is None:
        mem_info = _fallback_memory_snapshot()
        ram_pct = mem_info["percent"]
        available_mb = mem_info["available_mb"]
        swap_used_mb = mem_info["swap_used_mb"]
    else:
        mem = psutil.virtual_memory()
        swap = psutil.swap_memory()
        ram_pct = float(mem.percent)
        available_mb = int(mem.available // 1_000_000)
        swap_used_mb = int(swap.used // 1_000_000)
    result = {"ram_pct": ram_pct, "ram_available_mb": available_mb, "swap_used_mb": swap_used_mb, "status": "OK"}
    if ram_pct >= RAM_CRITICAL_PCT:
        result["status"] = "CRITICAL"
        push_event("RAM_CRITICAL", f"RAM {ram_pct:.0f}% — killing non-critical processes", "CRITICAL", result)
        _kill_non_critical_processes()
    elif ram_pct >= RAM_WARN_PCT:
        result["status"] = "WARNING"
        push_event("RAM_WARNING", f"RAM {ram_pct:.0f}% (available: {available_mb}MB)", "WARNING", result)
    if swap_used_mb > 200:
        push_event("SWAP_HIGH", f"Swap {swap_used_mb}MB — RAM sangat terbatas", "WARNING", {"swap_mb": swap_used_mb})
    return result


def check_disk() -> Dict[str, Any]:
    usage = shutil.disk_usage(str(ROOT))
    used_pct = round((usage.used / max(usage.total, 1)) * 100, 1)
    result = {"used_pct": used_pct, "free_gb": round(usage.free / 1_000_000_000, 2), "status": "OK"}
    if used_pct >= DISK_CRITICAL_PCT:
        result["status"] = "CRITICAL"
        push_event("DISK_CRITICAL", f"Disk {used_pct:.0f}% — auto cleanup dimulai", "CRITICAL", result)
        _auto_cleanup_disk()
    elif used_pct >= DISK_WARN_PCT:
        result["status"] = "WARNING"
        push_event("DISK_WARNING", f"Disk {used_pct:.0f}% (free: {result['free_gb']:.1f}GB)", "WARNING", result)
    return result


def check_services() -> Dict[str, Dict[str, Any]]:
    results: Dict[str, Dict[str, Any]] = {}
    for service in SERVICES_TO_GUARD:
        try:
            proc = subprocess.run(["systemctl", "is-active", service], capture_output=True, text=True, timeout=5)
            is_active = proc.stdout.strip() == "active"
            results[service] = {"active": is_active, "status": proc.stdout.strip()}
            if not is_active:
                _handle_service_down(service)
        except Exception as error:
            results[service] = {"active": False, "error": str(error)}
    return results


def _handle_service_down(service: str) -> None:
    now = time.time()
    recent = [timestamp for timestamp in restart_counts.get(service, []) if now - timestamp < 3600]
    restart_counts[service] = recent
    if len(recent) >= MAX_SERVICE_RESTARTS_PER_HOUR:
        push_event(
            "SERVICE_CRASH_LOOP",
            f"{service} crashed {len(recent)}x dalam 1 jam — tidak di-restart otomatis",
            "CRITICAL",
            {"service": service, "restarts_1h": len(recent)},
        )
        return
    backoff = [10, 30, 60][min(len(recent), 2)]
    time.sleep(backoff)
    guard_path = STATE_DIR / "daily_guard.json"
    if guard_path.exists():
        try:
            guard = json.loads(guard_path.read_text(encoding="utf-8"))
            if guard.get("hard_stopped") and service in {"kidax-engine", "kibot-manager"}:
                push_event("SERVICE_DOWN_HARD_STOP", f"{service} down tapi hard stop aktif — skip restart", "INFO")
                return
        except Exception:
            pass
    result = subprocess.run(["sudo", "systemctl", "start", service], capture_output=True, text=True, timeout=30)
    restart_counts.setdefault(service, []).append(now)
    push_event(
        "SERVICE_RESTARTED",
        f"{service} restarted (attempt {len(recent) + 1}/{MAX_SERVICE_RESTARTS_PER_HOUR})",
        "WARNING" if result.returncode == 0 else "CRITICAL",
        {"service": service, "success": result.returncode == 0, "stderr": result.stderr[:200]},
    )


def _kill_non_critical_processes() -> None:
    for pattern in ["python3.*test_", "python3.*kibot_optimizer"]:
        subprocess.run(f"pkill -f '{pattern}' 2>/dev/null || true", shell=True)


def _auto_cleanup_disk() -> None:
    subprocess.run(f"find {LOGS_DIR} -type f -mtime +7 -delete 2>/dev/null || true", shell=True)
    subprocess.run(f"find {STATE_DIR} -name '*.tmp.*' -delete 2>/dev/null || true", shell=True)
    subprocess.run(f"find {STATE_DIR / 'analyst'} -name '*.jsonl' -size +10M -exec gzip -f {{}} \\; 2>/dev/null || true", shell=True)


def check_network() -> Dict[str, Dict[str, Any]]:
    results: Dict[str, Dict[str, Any]] = {}
    targets = {
        "indodax": "https://indodax.com/api/ping",
        "binance": "https://api.binance.com/api/v3/ping",
        "supabase": os.getenv("SUPABASE_URL", "").rstrip("/") + "/rest/v1/" if os.getenv("SUPABASE_URL") else "",
    }
    for name, url in targets.items():
        if not url:
            results[name] = {"ok": False, "error": "not_configured"}
            continue
        start = time.time()
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                latency_ms = int((time.time() - start) * 1000)
                results[name] = {"ok": response.status == 200, "latency_ms": latency_ms}
        except Exception as error:
            results[name] = {"ok": False, "error": str(error)[:100]}
            push_event(f"NETWORK_{name.upper()}_DOWN", f"Koneksi ke {name} gagal: {str(error)[:100]}", "WARNING", {"target": name})
    return results


def save_state(metrics: Dict[str, Any]) -> None:
    atomic_write(GUARDIAN_STATE, {"ts": now_iso(), **metrics})


def run_guardian_loop() -> None:
    print(f"[GUARDIAN] KiBot Server Guardian started. Guarding services: {', '.join(SERVICES_TO_GUARD)}")
    cpu_high_since: Optional[float] = None
    while True:
        try:
            metrics = {
                "memory": check_memory(),
                "disk": check_disk(),
                "services": check_services(),
                "network": check_network(),
            }
            cpu_pct = float(psutil.cpu_percent(interval=5) if psutil else 0.0)
            metrics["cpu_pct"] = cpu_pct
            if cpu_pct >= CPU_WARN_PCT:
                if cpu_high_since is None:
                    cpu_high_since = time.time()
                elif time.time() - cpu_high_since >= CPU_WARN_SUSTAINED_S:
                    push_event("CPU_SUSTAINED_HIGH", f"CPU {cpu_pct:.0f}% sustained {CPU_WARN_SUSTAINED_S}s", "WARNING", {"cpu_pct": cpu_pct})
                    cpu_high_since = None
            else:
                cpu_high_since = None
            save_state(metrics)
            time.sleep(60)
        except Exception as error:
            push_event("GUARDIAN_LOOP_ERROR", str(error), "WARNING")
            time.sleep(30)


if __name__ == "__main__":
    run_guardian_loop()
