"""
KiBot Issue Reporter
====================
Dijalankan sebagai cron job tiap 6 jam di Node A (Singapore).
Tugasnya: buat GitHub Issue berisi laporan lengkap status bot,
dengan label rotasi bud-1 s/d bud-N biar tiap Bud account giliran.

Setup cron (jalankan: crontab -e):
  0 0,6,12,18 * * * /usr/bin/python3 /home/ubuntu/KiBot/scripts/ki_issue_reporter.py >> /home/ubuntu/KiBot/logs/issue_reporter.log 2>&1

Env vars (tambah ke .env):
  GITHUB_TOKEN          — Personal Access Token (repo scope)
  GITHUB_OWNER          — username GitHub (frahmat68-beep)
  GITHUB_REPO           — nama repo (Kibot)
  KIBOT_BUD_ACCOUNTS    — jumlah account Bud yang rotasi (default: 8)
  TELEGRAM_BOT_TOKEN    — token bot Telegram
  TELEGRAM_CHAT_ID      — group ID Telegram
"""

import os
import json
import requests
from datetime import datetime, timezone, timedelta
from pathlib import Path

# ── Timezone WIB ───────────────────────────────────────────────────────────────
WIB = timezone(timedelta(hours=7))
def now_wib(): return datetime.now(WIB)
def ts_wib(): return now_wib().strftime("%Y-%m-%d %H:%M WIB")

# ── Config ─────────────────────────────────────────────────────────────────────
BASE_DIR      = Path(__file__).parent.parent
DOTENV_PATH   = BASE_DIR / ".env"

def _load_env():
    if DOTENV_PATH.exists():
        for line in DOTENV_PATH.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, _, v = line.partition("=")
                if k.strip() not in os.environ:
                    os.environ[k.strip()] = v.strip().strip('"').strip("'")

_load_env()

GITHUB_TOKEN  = os.getenv("GITHUB_TOKEN", "").strip()
GITHUB_OWNER  = os.getenv("GITHUB_OWNER", "frahmat68-beep").strip()
GITHUB_REPO   = os.getenv("GITHUB_REPO",  "Kibot").strip()
NUM_ACCOUNTS  = max(1, int(os.getenv("KIBOT_BUD_ACCOUNTS", "8")))
TG_TOKEN      = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
TG_CHAT_ID    = os.getenv("TELEGRAM_CHAT_ID", "-1001346696386").strip()

# File counter rotasi
COUNTER_FILE  = BASE_DIR / "state" / "issue_reporter_counter.json"

# ── Baca state bot ──────────────────────────────────────────────────────────────
def read_json(path):
    try:
        if Path(path).exists():
            return json.loads(Path(path).read_text())
    except Exception:
        pass
    return {}

def read_recent_trades(n=10):
    trades = []
    log_path = BASE_DIR / "state" / "trade_log.jsonl"
    try:
        if log_path.exists():
            lines = log_path.read_text().strip().splitlines()
            for line in reversed(lines[-100:]):
                try:
                    trades.append(json.loads(line))
                    if len(trades) >= n:
                        break
                except Exception:
                    pass
    except Exception:
        pass
    return trades

def get_bot_status():
    """Kumpulkan semua data status bot dari state files."""
    state       = read_json(BASE_DIR / "state" / "daily_state.json")
    main_state  = read_json(BASE_DIR / "state" / "state.json")
    guardian    = read_json(BASE_DIR / "state" / "guardian_state.json")
    brain       = read_json(BASE_DIR / "state" / "brain_status.json")
    ai_state    = read_json(BASE_DIR / "state" / "ai_provider_state.json")
    gate        = read_json(BASE_DIR / "state" / "manager_gate.json")
    trades      = read_recent_trades(10)

    # Hitung stats trade
    total   = len(trades)
    wins    = sum(1 for t in trades if t.get("netPnlPct", t.get("net_profit_idr", 0)) > 0)
    losses  = total - wins
    wr      = (wins / total * 100) if total > 0 else 0
    total_pnl = sum(t.get("netPnlIdr", t.get("net_profit_idr", 0)) for t in trades)

    current_equity = (
        state.get("current_equity")
        or state.get("equity")
        or main_state.get("equity")
        or state.get("initial_capital_idr")
        or 0.0
    )

    return {
        "pnl_pct":        state.get("daily_pnl_pct", 0.0),
        "pnl_idr":        state.get("daily_pnl_idr", total_pnl),
        "equity":         current_equity,
        "risk_mode":      main_state.get("risk_mode", gate.get("risk_mode", "UNKNOWN")),
        "total_trades":   main_state.get("total_trades", total),
        "wins":           wins,
        "losses":         losses,
        "win_rate":       wr,
        "hard_stop":      state.get("hard_stop_triggered", False),
        "guardian_ok":    guardian.get("status", "UNKNOWN"),
        "ai_ok":          not ai_state.get("all_failed", False),
        "recent_trades":  trades[:5],
        "brain_mode":     brain.get("mode", "UNKNOWN"),
        "date":           state.get("date", now_wib().strftime("%Y-%m-%d")),
    }

# ── Rotasi counter ───────────────────────────────────────────────────────────────
def get_next_account():
    data = read_json(COUNTER_FILE)
    current = data.get("counter", 0)
    next_acc = (current % NUM_ACCOUNTS) + 1
    return next_acc


def commit_account(account_num: int):
    data = read_json(COUNTER_FILE)
    current = int(data.get("counter", 0))
    COUNTER_FILE.parent.mkdir(parents=True, exist_ok=True)
    COUNTER_FILE.write_text(json.dumps({
        "counter": current + 1,
        "last_account": account_num,
        "last_run": ts_wib()
    }, indent=2))

# ── Format Issue ─────────────────────────────────────────────────────────────────
def build_issue(status: dict, account_num: int) -> dict:
    ts        = ts_wib()
    pnl_pct   = status["pnl_pct"]
    pnl_idr   = status["pnl_idr"]
    equity    = status["equity"]
    risk      = status["risk_mode"]
    wr        = status["win_rate"]
    trades    = status["total_trades"]
    wins      = status["wins"]
    losses    = status["losses"]
    hard_stop = status["hard_stop"]
    ai_ok     = status["ai_ok"]
    guardian  = status["guardian_ok"]
    brain     = status["brain_mode"]

    pnl_emoji  = "🟢" if pnl_pct >= 0 else "🔴"
    risk_emoji = {"GROWTH":"🚀","CAUTION":"⚠️","DEFENSIVE":"🛡️",
                  "RESTRICTED":"🔒","HARD_STOP":"🛑"}.get(risk, "❓")
    hs_status  = "🛑 **YA — ENTRY DIBLOKIR**" if hard_stop else "✅ Tidak"
    ai_status  = "✅ Online" if ai_ok else "🔴 **OFFLINE**"

    # Recent trades table
    trade_rows = ""
    for t in status["recent_trades"]:
        pair   = t.get("pair", t.get("pair_id", "?")).upper()
        side   = t.get("side", "?")
        pnl    = t.get("netPnlIdr", t.get("net_profit_idr", 0))
        pct    = t.get("netPnlPct", t.get("profit_pct", 0))
        reason = t.get("exitReason", t.get("exit_reason", "-"))[:40]
        emoji  = "🟢" if pnl > 0 else "🔴"
        trade_rows += f"| {emoji} {pair} | {side} | {pct:+.2f}% | Rp{pnl:+,.0f} | {reason} |\n"

    if not trade_rows:
        trade_rows = "| — | — | — | — | Belum ada trade |\n"

    title = f"[AUTO-REPORT] {ts} | bud-{account_num} | PnL: {pnl_pct:+.2f}%"

    body = f"""## 📊 KiBot Auto-Report — {ts}

> **Laporan otomatis untuk:** @bud-account-{account_num}
> **Filosofi:** SEDIKIT DEMI SEDIKIT LAMA LAMA JADI BUKIT | TEKAN KERUGIAN, MAKSIMALKAN PROBABILITAS

---

## 💰 Performa Hari Ini

| Metrik | Nilai |
|--------|-------|
| {pnl_emoji} PnL | {pnl_pct:+.2f}% (Rp{pnl_idr:+,.0f}) |
| 💼 Modal | Rp{equity:,.0f} |
| {risk_emoji} Risk Mode | **{risk}** |
| 📈 Total Trades | {trades} ({wins}W / {losses}L) |
| 🎯 Win Rate | {wr:.1f}% |
| 🛑 Hard Stop | {hs_status} |
| 🤖 AI Provider | {ai_status} |
| 🧠 Brain Mode | {brain} |
| 🛡️ Guardian | {guardian} |

---

## 📋 5 Trade Terakhir

| | Pair | Side | PnL % | PnL IDR | Exit Reason |
|-|------|------|--------|---------|-------------|
{trade_rows}
---

## 🎯 Instruksi untuk Bud Account {account_num}

Kamu adalah **autonomous trading assistant** dengan otoritas penuh. Evaluasi data di atas dan ambil tindakan berdasarkan filosofi sistem:

**FILOSOFI CORE:**
1. **SEDIKIT DEMI SEDIKIT LAMA LAMA JADI BUKIT** — Utamakan konsistensi, bukan profit besar sekali jalan
2. **TEKAN KERUGIAN, MAKSIMALKAN PROBABILITAS** — Cut loss cepat, entry hanya kalau probabilitas tinggi

**YANG HARUS KAMU LAKUKAN (tanpa tunggu konfirmasi):**
- SSH ke Node A: `ssh -i SSH_MANAGEMENT/ssh-key-2026-03-22.key ubuntu@213.35.118.26`
- SSH ke Node B: `ssh -i SSH_SCANNER/ssh-key-2026-03-27.key ubuntu@152.69.218.198`
- Baca log terbaru, state, dan konfigurasi
- Evaluasi apakah strategi sesuai filosofi
- Jika ada service mati → restart langsung
- Jika parameter perlu diubah → ubah dan deploy
- Jika ada bug → fix dan push ke repo
- Setelah selesai → kirim laporan ke Telegram group: `-1001346696386`
- Close issue ini setelah semua action selesai

**PRIORITAS TINDAKAN:**
1. 🚨 Service mati → restart immediate
2. 🔴 Hard stop aktif + kondisi market membaik → evaluasi reset
3. ⚠️ Win rate < 40% → review dan perketat conviction threshold
4. 📉 PnL < -1.5% → switch ke DEFENSIVE mode
5. 🟢 Semua aman → optimasi kecil, lapor status ke Telegram

**SSH KEYS ada di repo:** `SSH_MANAGEMENT/` dan `SSH_SCANNER/`

---
*Issue ini dibuat otomatis oleh ki_issue_reporter.py*
*Repo: https://github.com/{GITHUB_OWNER}/{GITHUB_REPO}*
"""

    return {"title": title, "body": body, "account_num": account_num}

# ── GitHub API ───────────────────────────────────────────────────────────────────
def gh_request(method: str, path: str, *, payload: dict | None = None, timeout: int = 15) -> dict:
    headers = {
        "Authorization": f"Bearer {GITHUB_TOKEN}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28"
    }
    url = f"https://api.github.com/repos/{GITHUB_OWNER}/{GITHUB_REPO}/{path.lstrip('/')}"

    try:
        resp = requests.request(method, url, headers=headers, json=payload, timeout=timeout)
        data = {}
        try:
            data = resp.json()
        except Exception:
            data = {"raw": resp.text}
        return {"ok": 200 <= resp.status_code < 300, "status": resp.status_code, "data": data}
    except Exception as error:
        return {"ok": False, "status": 0, "data": {"error": str(error)}}


def ensure_label(label: str):
    """Buat label kalau belum ada."""
    colors = ["0075ca","e4e669","d93f0b","0e8a16","5319e7","b60205","1d76db","0052cc"]
    parts  = label.split("-")
    idx    = 0
    if len(parts) == 2 and parts[1].isdigit():
        idx = max(0, int(parts[1]) - 1)
    color  = colors[idx % len(colors)]

    exists = gh_request("GET", f"labels/{label}", timeout=10)
    if exists["ok"]:
        return
    if exists["status"] not in (404,):
        print(f"[WARN] Cek label gagal ({label}): {exists}")
        return
    created = gh_request(
        "POST",
        "labels",
        payload={"name": label, "color": color, "description": f"Handled by Bud account {label}"},
        timeout=10,
    )
    if not created["ok"]:
        print(f"[WARN] Buat label gagal ({label}): {created}")

def create_issue(title: str, body: str, account_num: int) -> dict:
    label = f"bud-{account_num}"
    ensure_label(label)
    ensure_label("auto-report")
    resp = gh_request(
        "POST",
        "issues",
        payload={"title": title, "body": body, "labels": [label, "auto-report"]},
        timeout=15,
    )
    return resp["data"]

# ── Telegram notif ───────────────────────────────────────────────────────────────
def tg_notify(status: dict, issue_url: str, account_num: int):
    if not TG_TOKEN or not TG_CHAT_ID:
        return
    pnl_pct  = status["pnl_pct"]
    risk     = status["risk_mode"]
    emoji    = "🟢" if pnl_pct >= 0 else "🔴"
    msg = (
        f"📋 <b>Auto-Report #{account_num} dikirim</b> — {ts_wib()}\n"
        f"{emoji} PnL: {pnl_pct:+.2f}% | Risk: {risk}\n"
        f"🤖 Bud-{account_num} sedang evaluasi...\n"
        f"🔗 <a href='{issue_url}'>Lihat Issue</a>"
    )
    try:
        requests.post(
            f"https://api.telegram.org/bot{TG_TOKEN}/sendMessage",
            json={"chat_id": TG_CHAT_ID, "text": msg,
                  "parse_mode": "HTML", "disable_web_page_preview": False},
            timeout=10
        )
    except Exception as e:
        print(f"[WARN] Telegram notif gagal: {e}")

# ── Main ─────────────────────────────────────────────────────────────────────────
def main():
    print(f"[{ts_wib()}] KiBot Issue Reporter starting...")

    if not GITHUB_TOKEN:
        print("[ERROR] GITHUB_TOKEN tidak diset di .env!")
        return

    # Ambil data bot
    print("[INFO] Membaca status bot...")
    status      = get_bot_status()
    account_num = get_next_account()

    print(f"[INFO] Giliran: bud-{account_num} | PnL: {status['pnl_pct']:+.2f}%")

    # Build & create issue
    issue_data  = build_issue(status, account_num)
    print(f"[INFO] Membuat GitHub Issue: {issue_data['title']}")
    result      = create_issue(issue_data["title"], issue_data["body"], account_num)

    issue_url   = result.get("html_url", "")
    issue_num   = result.get("number", "?")

    if issue_url:
        print(f"[OK] Issue #{issue_num} dibuat: {issue_url}")
        commit_account(account_num)
        tg_notify(status, issue_url, account_num)
    else:
        print(f"[ERROR] Gagal buat issue: {result}")

if __name__ == "__main__":
    main()
