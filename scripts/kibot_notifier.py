#!/usr/bin/env python3
"""
KiBot Notifier
==============
Central notification hub for event-bus driven Telegram alerts.
"""

from __future__ import annotations

import json
import os
import queue
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

ROOT = Path(os.getenv("KIBOT_RUNTIME_ROOT", Path(__file__).resolve().parent.parent))
STATE_DIR = ROOT / "state"
EVENTS_DIR = STATE_DIR / "events"
NOTIFY_STATE = STATE_DIR / "notifier_state.json"
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
TELEGRAM_CHAT_ID = os.getenv("TELEGRAM_CHAT_ID", os.getenv("TELEGRAM_USER_ID", "")).strip()

MAX_MSGS_PER_MINUTE = 5
message_queue: "queue.Queue[Dict[str, Any]]" = queue.Queue()
last_send_times: List[float] = []


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


def send_telegram(message: str, parse_mode: str = "Markdown") -> bool:
    if not TELEGRAM_BOT_TOKEN or not TELEGRAM_CHAT_ID:
        print(f"[NOTIFIER] Telegram not configured. Message: {message[:160]}")
        return False
    payload = {"chat_id": TELEGRAM_CHAT_ID, "text": message, "parse_mode": parse_mode}
    request = urllib.request.Request(
        f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendMessage",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status == 200
    except Exception as error:
        print(f"[NOTIFIER] Telegram error: {error}")
        return False


def _can_send_now() -> bool:
    now = time.time()
    global last_send_times
    last_send_times = [timestamp for timestamp in last_send_times if now - timestamp < 60]
    return len(last_send_times) < MAX_MSGS_PER_MINUTE


def notify(message: str, severity: str = "INFO", bypass_rate: bool = False) -> None:
    if severity == "CRITICAL" or bypass_rate:
        if _can_send_now() or bypass_rate:
            if send_telegram(message):
                last_send_times.append(time.time())
        return
    message_queue.put({"message": message, "severity": severity, "ts": time.time()})


def _format_trade_notification(event: Dict[str, Any]) -> str:
    data = event.get("data", {})
    pair = data.get("pair", "?")
    side = data.get("side", "?")
    pnl = float(data.get("net_pnl_pct", 0.0)) * 100
    idr = float(data.get("filled_idr", 0.0))
    if side == "BUY":
        return f"🟢 *BUY {pair}*\nSize: Rp{idr:,.0f}\nOrder: {data.get('order_type', '?')}"
    emoji = "📈" if pnl >= 0 else "📉"
    return f"{emoji} *SELL {pair}*\nPnL: `{pnl:+.2f}%`\nExit: {data.get('exit_reason', '?')}\nOrder: {data.get('order_type', '?')}"


def _format_event_message(event: Dict[str, Any]) -> Optional[str]:
    event_type = event.get("type", "")
    severity = event.get("severity", "INFO")
    data = event.get("data", {})
    if event_type in {"TRADE_BUY", "TRADE_SELL"}:
        return _format_trade_notification(event)
    if event_type == "HARD_STOP":
        pnl = float(data.get("daily_pnl_pct", 0.0)) * 100
        return f"🛑 *HARD STOP TRIGGERED*\nPnL Hari Ini: `{pnl:.2f}%`\nBot berhenti trading sampai reset harian"
    if event_type == "RAM_CRITICAL":
        return f"🚨 *RAM CRITICAL*\nUsage: {data.get('ram_pct', '?')}%\nAvailable: {data.get('ram_available_mb', '?')}MB"
    if event_type == "DISK_CRITICAL":
        return f"🚨 *DISK CRITICAL*\nUsage: {data.get('used_pct', '?')}%\nFree: {data.get('free_gb', '?')}GB"
    if event_type == "SERVICE_CRASH_LOOP":
        return f"🚨 *SERVICE CRASH LOOP*\n{data.get('service', '?')} crashed {data.get('restarts_1h', '?')}x dalam 1 jam"
    if event_type == "JAR_CORRUPT":
        return f"🚨 *JAR CORRUPT*\n{event.get('message', '?')}"
    if event_type == "DAILY_DRAWDOWN_ALERT":
        pnl = float(data.get("daily_pnl_pct", 0.0)) * 100
        return f"⚠️ *DRAWDOWN ALERT*\nPnL Hari Ini: `{pnl:.2f}%`"
    if event_type in {"SYMLINK_FIXED", "STATE_RESTORED", "CRITICAL_FAILURE", "CRITICAL_SERVICE_DOWN", "SECURITY_SECRETS_FOUND"}:
        prefix = "🚨" if severity == "CRITICAL" else "🔧"
        return f"{prefix} *{event_type.replace('_', ' ')}*\n{event.get('message', 'No details')}"
    return None


def process_events() -> None:
    EVENTS_DIR.mkdir(parents=True, exist_ok=True)
    for event_file in sorted(EVENTS_DIR.glob("*.json")):
        try:
            event = json.loads(event_file.read_text(encoding="utf-8"))
            message = _format_event_message(event)
            if message:
                notify(message, severity=event.get("severity", "INFO"), bypass_rate=event.get("severity") == "CRITICAL")
            event_file.unlink()
        except Exception as error:
            print(f"[NOTIFIER] Error processing {event_file}: {error}")
            try:
                event_file.unlink()
            except Exception:
                pass


def flush_message_queue() -> None:
    while not message_queue.empty() and _can_send_now():
        item = message_queue.get()
        if send_telegram(item["message"]):
            last_send_times.append(time.time())


def _send_daily_report() -> None:
    try:
        from kibot_analyst import generate_daily_report

        notify(generate_daily_report(), bypass_rate=True)
    except Exception as error:
        notify(f"⚠️ Daily report error: {error}", "WARNING")


def _send_weekly_report() -> None:
    notify("📅 *Weekly Summary* sedang diproses...", bypass_rate=True)


def run_notifier_loop() -> None:
    print("[NOTIFIER] KiBot Notifier started")
    send_telegram("🤖 *KiBot Trinity* online dan siap trading!")
    last_daily_report = 0.0
    last_weekly_report = 0.0
    while True:
        try:
            process_events()
            flush_message_queue()
            now = datetime.now(timezone.utc)
            day_seconds = now.hour * 3600 + now.minute * 60 + now.second
            if 36000 <= day_seconds <= 36060 and time.time() - last_daily_report > 3600:
                _send_daily_report()
                last_daily_report = time.time()
            if now.weekday() == 0 and 36000 <= day_seconds <= 36060 and time.time() - last_weekly_report > 86400:
                _send_weekly_report()
                last_weekly_report = time.time()
            atomic_write(NOTIFY_STATE, {"ts": now.isoformat(), "queued": message_queue.qsize(), "last_send_count_1m": len(last_send_times)})
            time.sleep(5)
        except Exception as error:
            print(f"[NOTIFIER] Loop error: {error}")
            time.sleep(10)


if __name__ == "__main__":
    run_notifier_loop()
