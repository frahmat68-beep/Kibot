"""
KiBot Trinity — Telegram Monitor & Command Handler
===================================================
Script ini berjalan sebagai daemon terpisah di server lu.
Tugasnya:
  1. Poll status bot (PnL, posisi, risk mode) tiap interval
  2. Kirim alert proaktif ke Telegram jika ada anomali
  3. Terima command dari Telegram buat kontrol bot

Cara pakai:
  python3 ki_telegram_monitor.py

Env vars yang dibutuhkan (taruh di .env):
  TELEGRAM_BOT_TOKEN    — token bot Telegram lu
  TELEGRAM_CHAT_ID      — chat ID lu (bisa dapat dari @userinfobot)
  KIBOT_STATE_FILE      — path ke file state JSON bot (default: state/daily_state.json)
  KIBOT_LOG_FILE        — path ke file log trade (default: logs/trades.jsonl)
  KIBOT_MANAGER_PID_FILE — path ke PID file manager (default: state/manager.pid)
"""

import os
import json
import time
import signal
import logging
import threading
import subprocess
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Optional

import requests

# ── Timezone WIB ───────────────────────────────────────────────────────────────
WIB = timezone(timedelta(hours=7))

def now_wib() -> datetime:
    return datetime.now(WIB)

def ts_wib() -> str:
    return now_wib().strftime("%H:%M:%S WIB")

# ── Config dari env ─────────────────────────────────────────────────────────────
TELEGRAM_TOKEN   = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
TELEGRAM_CHAT_ID = (
    os.getenv("TELEGRAM_CHAT_ID")
    or os.getenv("TELEGRAM_USER_ID")
    or os.getenv("KIBOT_TELEGRAM_CHAT_ID")
    or ""
).strip()

BASE_DIR         = Path(__file__).parent.parent
STATE_FILE       = Path(os.getenv("KIBOT_STATE_FILE",  str(BASE_DIR / "state" / "daily_state.json")))
LOG_FILE         = Path(os.getenv("KIBOT_LOG_FILE",    str(BASE_DIR / "logs"  / "trades.jsonl")))
PID_FILE         = Path(os.getenv("KIBOT_MANAGER_PID_FILE", str(BASE_DIR / "state" / "manager.pid")))
OPS_LOG          = BASE_DIR / "logs" / "OPS_UPDATE_LOG.md"

POLL_INTERVAL    = int(os.getenv("KIBOT_MONITOR_POLL_SEC",  "60"))   # cek status tiap N detik
ALERT_LOSS_PCT   = float(os.getenv("KIBOT_ALERT_LOSS_PCT",  "-1.0")) # alert kalau rugi > ini
ALERT_WIN_PCT    = float(os.getenv("KIBOT_ALERT_WIN_PCT",   "1.5"))  # alert kalau untung > ini

# ── Logger ──────────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [MONITOR] %(levelname)s %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("ki_monitor")

# ── State internal monitor ──────────────────────────────────────────────────────
_last_pnl_pct:      float = 0.0
_last_risk_mode:    str   = ""
_last_alert_ts:     float = 0.0
_alert_cooldown:    float = 300.0   # jangan spam alert, min 5 menit antar alert sejenis
_last_update_id:    int   = 0
_running:           bool  = True

# ── Telegram helper ─────────────────────────────────────────────────────────────
def _tg(method: str, **kwargs) -> Optional[dict]:
    if not TELEGRAM_TOKEN:
        log.warning("TELEGRAM_BOT_TOKEN tidak diset — notif dimatiin")
        return None
    try:
        r = requests.post(
            f"https://api.telegram.org/bot{TELEGRAM_TOKEN}/{method}",
            json=kwargs,
            timeout=10,
        )
        return r.json()
    except Exception as e:
        log.error(f"Telegram {method} gagal: {e}")
        return None

def send(text: str, parse_mode: str = "HTML") -> None:
    if not TELEGRAM_CHAT_ID:
        log.warning("TELEGRAM_CHAT_ID tidak diset")
        return
    _tg("sendMessage", chat_id=TELEGRAM_CHAT_ID, text=text,
        parse_mode=parse_mode, disable_web_page_preview=True)

def get_updates(offset: int = 0) -> list:
    resp = _tg("getUpdates", offset=offset, timeout=30, allowed_updates=["message"])
    if resp and resp.get("ok"):
        return resp.get("result", [])
    return []

# ── Baca state bot ──────────────────────────────────────────────────────────────
def read_state() -> dict:
    try:
        if STATE_FILE.exists():
            return json.loads(STATE_FILE.read_text())
    except Exception as e:
        log.error(f"Gagal baca state: {e}")
    return {}

def read_recent_trades(n: int = 5) -> list:
    trades = []
    try:
        if LOG_FILE.exists():
            lines = LOG_FILE.read_text().strip().splitlines()
            for line in reversed(lines[-50:]):
                try:
                    trades.append(json.loads(line))
                    if len(trades) >= n:
                        break
                except Exception:
                    pass
    except Exception as e:
        log.error(f"Gagal baca log trade: {e}")
    return trades

def get_manager_pid() -> Optional[int]:
    try:
        if PID_FILE.exists():
            return int(PID_FILE.read_text().strip())
    except Exception:
        pass
    return None

def is_manager_alive() -> bool:
    pid = get_manager_pid()
    if pid is None:
        return False
    try:
        os.kill(pid, 0)
        return True
    except (ProcessLookupError, PermissionError):
        return False

# ── Format pesan ────────────────────────────────────────────────────────────────
def fmt_status(state: dict) -> str:
    ts       = ts_wib()
    pnl_pct  = state.get("daily_pnl_pct", 0.0)
    equity   = state.get("equity_idr", 0.0)
    risk     = state.get("risk_mode", "UNKNOWN")
    trades   = state.get("total_trades_today", 0)
    wins     = state.get("wins_today", 0)
    losses   = state.get("losses_today", 0)
    wr       = (wins / trades * 100) if trades > 0 else 0.0
    alive    = "✅ Running" if is_manager_alive() else "🔴 <b>MATI!</b>"

    pnl_emoji = "🟢" if pnl_pct >= 0 else "🔴"
    risk_emoji = {"GROWTH": "🚀", "CAUTION": "⚠️", "DEFENSIVE": "🛡️",
                  "RESTRICTED": "🔒", "HARD_STOP": "🛑"}.get(risk, "❓")

    return (
        f"📊 <b>KiBot Status</b> — {ts}\n"
        f"━━━━━━━━━━━━━━━━━━━━\n"
        f"Bot       : {alive}\n"
        f"{pnl_emoji} PnL Hari  : {pnl_pct:+.2f}%\n"
        f"💰 Equity  : Rp{equity:,.0f}\n"
        f"{risk_emoji} Risk Mode : {risk}\n"
        f"📈 Trades  : {trades} ({wins}W/{losses}L | WR {wr:.0f}%)\n"
    )

def fmt_trade(t: dict) -> str:
    pair     = t.get("pair_id", "?").upper()
    side     = t.get("side", "?")
    profit   = t.get("net_profit_idr", 0.0)
    pct      = t.get("profit_pct", 0.0)
    bucket   = t.get("bucket_type", "?")
    emoji    = "🟢" if profit >= 0 else "🔴"
    return f"{emoji} <b>{pair}</b> {side} | {pct:+.2f}% | Rp{profit:+,.0f} [{bucket}]"

# ── Perintah dari Telegram ───────────────────────────────────────────────────────
HELP_TEXT = """
🤖 <b>KiBot Monitor Commands</b>

/status  — Status bot real-time
/trades  — 5 trade terakhir
/pnl     — Ringkasan PnL hari ini
/alive   — Cek bot masih jalan ga
/stop    — STOP bot (kirim SIGTERM ke manager)
/restart — Restart manager (jalanin ulang)
/help    — Tampilkan menu ini
"""

def handle_command(text: str) -> str:
    cmd = text.strip().split()[0].lower().lstrip("/")

    if cmd == "status":
        state = read_state()
        return fmt_status(state)

    elif cmd == "trades":
        trades = read_recent_trades(5)
        if not trades:
            return "📭 Belum ada trade hari ini."
        lines = ["📋 <b>5 Trade Terakhir:</b>\n"]
        for t in trades:
            lines.append(fmt_trade(t))
        return "\n".join(lines)

    elif cmd == "pnl":
        state = read_state()
        pnl      = state.get("daily_pnl_idr", 0.0)
        pnl_pct  = state.get("daily_pnl_pct", 0.0)
        equity   = state.get("equity_idr", 0.0)
        trades   = state.get("total_trades_today", 0)
        wins     = state.get("wins_today", 0)
        ev       = state.get("expected_value_idr", 0.0)
        emoji    = "🟢" if pnl >= 0 else "🔴"
        return (
            f"{emoji} <b>PnL Hari Ini</b>\n"
            f"Profit    : Rp{pnl:+,.0f} ({pnl_pct:+.2f}%)\n"
            f"Equity    : Rp{equity:,.0f}\n"
            f"Trades    : {trades} ({wins} menang)\n"
            f"EV/trade  : Rp{ev:+,.0f}\n"
            f"📅 Reset tengah malam WIB"
        )

    elif cmd == "alive":
        alive = is_manager_alive()
        if alive:
            pid = get_manager_pid()
            return f"✅ Manager hidup (PID {pid})"
        return "🔴 <b>Manager TIDAK berjalan!</b> Gunakan /restart"

    elif cmd == "stop":
        pid = get_manager_pid()
        if pid is None:
            return "⚠️ PID file tidak ditemukan."
        try:
            os.kill(pid, signal.SIGTERM)
            return f"🛑 SIGTERM dikirim ke PID {pid}. Bot akan berhenti."
        except Exception as e:
            return f"❌ Gagal stop: {e}"

    elif cmd == "restart":
        manager_script = BASE_DIR / "scripts" / "kibot_manager.py"
        if not manager_script.exists():
            return "❌ kibot_manager.py tidak ditemukan."
        # Stop dulu kalau masih jalan
        pid = get_manager_pid()
        if pid:
            try:
                os.kill(pid, signal.SIGTERM)
                time.sleep(3)
            except Exception:
                pass
        try:
            subprocess.Popen(
                ["python3", str(manager_script)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
            return "🔄 Manager di-restart. Cek /alive dalam 10 detik."
        except Exception as e:
            return f"❌ Gagal restart: {e}"

    elif cmd in ("help", "start"):
        return HELP_TEXT

    else:
        return f"❓ Command tidak dikenal: /{cmd}\n{HELP_TEXT}"

# ── Loop terima command ──────────────────────────────────────────────────────────
def command_loop() -> None:
    global _last_update_id
    log.info("Command listener dimulai...")
    while _running:
        try:
            updates = get_updates(offset=_last_update_id + 1)
            for upd in updates:
                _last_update_id = upd["update_id"]
                msg = upd.get("message", {})
                text = msg.get("text", "")
                chat_id = str(msg.get("chat", {}).get("id", ""))
                if text.startswith("/") and chat_id == TELEGRAM_CHAT_ID:
                    log.info(f"Command diterima: {text}")
                    reply = handle_command(text)
                    send(reply)
        except Exception as e:
            log.error(f"command_loop error: {e}")
        time.sleep(2)

# ── Loop monitoring proaktif ─────────────────────────────────────────────────────
def monitor_loop() -> None:
    global _last_pnl_pct, _last_risk_mode, _last_alert_ts
    log.info("Monitor loop dimulai...")

    # Kirim startup message
    send(
        f"🟢 <b>KiBot Monitor aktif</b> — {ts_wib()}\n"
        f"Ketik /help untuk daftar perintah."
    )

    prev_alive = True

    while _running:
        try:
            state = read_state()
            pnl_pct   = state.get("daily_pnl_pct", 0.0)
            risk_mode = state.get("risk_mode", "")
            alive     = is_manager_alive()
            now_ts    = time.time()

            # ── Alert: bot mati ─────────────────────────────────────────────
            if prev_alive and not alive:
                send(
                    f"🔴 <b>ALERT: KiBot Manager MATI!</b>\n"
                    f"⏰ {ts_wib()}\n"
                    f"Gunakan /restart untuk hidupkan kembali."
                )
            elif not prev_alive and alive:
                send(f"✅ <b>KiBot Manager hidup kembali</b> — {ts_wib()}")
            prev_alive = alive

            cooldown_ok = (now_ts - _last_alert_ts) > _alert_cooldown

            # ── Alert: loss besar ────────────────────────────────────────────
            if pnl_pct <= ALERT_LOSS_PCT and cooldown_ok:
                equity = state.get("equity_idr", 0.0)
                send(
                    f"⚠️ <b>ALERT RUGI!</b> — {ts_wib()}\n"
                    f"PnL hari ini: {pnl_pct:+.2f}%\n"
                    f"Equity: Rp{equity:,.0f}\n"
                    f"Risk Mode: {risk_mode}"
                )
                _last_alert_ts = now_ts

            # ── Alert: profit besar ──────────────────────────────────────────
            if pnl_pct >= ALERT_WIN_PCT and cooldown_ok and pnl_pct > _last_pnl_pct:
                equity = state.get("equity_idr", 0.0)
                send(
                    f"🎯 <b>TARGET PROFIT TERCAPAI!</b> — {ts_wib()}\n"
                    f"PnL hari ini: {pnl_pct:+.2f}%\n"
                    f"Equity: Rp{equity:,.0f}"
                )
                _last_alert_ts = now_ts

            # ── Alert: risk mode berubah ─────────────────────────────────────
            if risk_mode and risk_mode != _last_risk_mode and _last_risk_mode:
                emoji_map = {
                    "GROWTH": "🚀", "CAUTION": "⚠️", "DEFENSIVE": "🛡️",
                    "RESTRICTED": "🔒", "HARD_STOP": "🛑"
                }
                emoji = emoji_map.get(risk_mode, "❓")
                send(
                    f"{emoji} <b>Risk Mode berubah!</b>\n"
                    f"{_last_risk_mode} → <b>{risk_mode}</b>\n"
                    f"⏰ {ts_wib()}"
                )

            _last_pnl_pct  = pnl_pct
            _last_risk_mode = risk_mode

        except Exception as e:
            log.error(f"monitor_loop error: {e}")

        time.sleep(POLL_INTERVAL)

# ── Main ─────────────────────────────────────────────────────────────────────────
def main():
    global _running

    if not TELEGRAM_TOKEN:
        log.error("TELEGRAM_BOT_TOKEN wajib diisi di .env!")
        return
    if not TELEGRAM_CHAT_ID:
        log.error("TELEGRAM_CHAT_ID wajib diisi di .env!")
        return

    log.info(f"KiBot Telegram Monitor v1.0 starting | poll={POLL_INTERVAL}s")

    def _on_signal(sig, frame):
        global _running
        log.info(f"Signal {sig} diterima — shutting down...")
        _running = False
        send(f"🔌 <b>Monitor dimatikan</b> — {ts_wib()}")

    signal.signal(signal.SIGTERM, _on_signal)
    signal.signal(signal.SIGINT, _on_signal)

    t_cmd = threading.Thread(target=command_loop, daemon=True, name="cmd-loop")
    t_cmd.start()

    monitor_loop()

if __name__ == "__main__":
    main()
