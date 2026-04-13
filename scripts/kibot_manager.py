#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import signal
import socket
import sys
import threading
import time
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests

# Use defusedxml to prevent XXE attacks
try:
    import defusedxml.ElementTree as ET
except ImportError:
    from xml.etree import ElementTree as ET
    print("[KIBOT][WARN] defusedxml not installed, using stdlib xml (XXE risk)", flush=True)

# Global lock for thread-safe access to shared state
_state_lock = threading.RLock()
_shutdown_event = threading.Event()
_main_socket: Optional[socket.socket] = None
_http_server: Optional[ThreadingHTTPServer] = None
_bot_start_time = time.time()
_last_daily_guard_check_at = 0.0
_learning_engine = None
_regime_detector = None
_learning_enabled = False
_metrics: Dict[str, float | int] = {
    "market_orders_today": 0,
    "limit_orders_today": 0,
    "entries_blocked_hard_stop": 0,
    "entries_blocked_learn_gate": 0,
    "entries_blocked_whatif": 0,
    "fee_bleed_est_idr": 0.0,
    "whatif_skips_today": 0,
    "whatif_enters_today": 0,
}
_last_math_review_at = 0.0
_math_review_last_action = "INIT"
_math_review_last_reason = ""
_math_review_trade_journal: list[dict[str, Any]] = []


def _load_dotenv_if_exists() -> None:
    candidates = []
    explicit = os.getenv("KIBOT_MANAGER_ENV_FILE")
    if explicit:
        candidates.append(Path(explicit))
    cwd = Path.cwd()
    candidates.extend([cwd / ".env", cwd.parent / ".env", cwd / "apps/mac-engine/.env"])
    for path in candidates:
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            raw = line.strip()
            if not raw or raw.startswith("#") or "=" not in raw:
                continue
            key, val = raw.split("=", 1)
            key = key.strip()
            val = val.strip().strip("'").strip('"')
            if key and key not in os.environ:
                os.environ[key] = val


_load_dotenv_if_exists()

try:
    from kibot_learning_engine import get_engine as _get_learning_engine, get_regime_detector as _get_regime_detector

    _learning_engine = _get_learning_engine()
    _regime_detector = _get_regime_detector()
    _learning_enabled = True
    print("[KIBOT][LEARN] mathematical learning engine loaded", flush=True)
except Exception as _learning_error:
    _learning_enabled = False
    _learning_engine = None
    _regime_detector = None
    print(f"[KIBOT][LEARN][WARN] learning engine unavailable: {_learning_error}", flush=True)

def _load_json_file(path: Path, default: Any) -> Any:
    try:
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default
    return default


def _write_json_file(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _metric_inc(name: str, amount: int = 1) -> None:
    current = _metrics.get(name, 0)
    if isinstance(current, (int, float)):
        _metrics[name] = current + amount


def _metric_add(name: str, amount: float) -> None:
    current = _metrics.get(name, 0.0)
    if isinstance(current, (int, float)):
        _metrics[name] = float(current) + float(amount)


def _telegram_send(message: str) -> None:
    token = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
    chat_id = os.getenv("TELEGRAM_USER_ID", "").strip()
    if not token or not chat_id:
        return
    try:
        requests.post(
            f"https://api.telegram.org/bot{token}/sendMessage",
            json={
                "chat_id": chat_id,
                "text": message,
                "disable_web_page_preview": True,
            },
            timeout=10,
        )
    except Exception as error:
        print(f"[KIBOT][TELEGRAM][WARN] {error}", flush=True)


def _record_trade_result(pair_id: str, *, gross_pnl_pct: float, entry_time: float) -> None:
    pair_key = pair_id.lower().strip()
    if not pair_key:
        pair_key = "unknown"
    _math_review_trade_journal.append(
        {
            "pair": pair_key,
            "gross_pnl_pct": float(gross_pnl_pct),
            "entry_time": float(entry_time),
        }
    )
    if len(_math_review_trade_journal) > 500:
        del _math_review_trade_journal[:-500]


def _safe_isoformat(epoch_seconds: Optional[float] = None) -> str:
    dt = datetime.fromtimestamp(epoch_seconds if epoch_seconds is not None else time.time(), tz=timezone.utc)
    return dt.isoformat().replace("+00:00", "Z")


def _env_first(*keys: str, default: str = "") -> str:
    for key in keys:
        value = os.getenv(key, "").strip()
        if value:
            return value
    return default

SUPABASE_URL = os.getenv("SUPABASE_URL", "").rstrip("/")
SUPABASE_KEY = os.getenv("SUPABASE_SERVICE_ROLE_KEY") or os.getenv("SUPABASE_ANON_KEY") or ""
TIMEOUT = float(os.getenv("KIBOT_MANAGER_HTTP_TIMEOUT_SEC", "12"))
UDP_BIND_HOST = os.getenv("KIBOT_MANAGER_UDP_BIND_HOST", "0.0.0.0")
UDP_BIND_PORT = int(os.getenv("KIBOT_MANAGER_UDP_BIND_PORT", "9998"))
KINANCE_UDP_HOST = os.getenv("KINANCE_UDP_HOST", "")
KINANCE_UDP_PORT = int(os.getenv("KINANCE_UDP_PORT", "9999"))
KIDAX_UDP_HOST = os.getenv("KIDAX_UDP_HOST", "")
KIDAX_UDP_PORT = int(os.getenv("KIDAX_UDP_PORT", "9999"))
MANAGER_HEARTBEAT_INTERVAL_SEC = float(os.getenv("KIBOT_MANAGER_HEARTBEAT_INTERVAL_SEC", "1.0"))
TAKER_FEE_PCT = float(os.getenv("KIDAX_TAKER_FEE_PCT", "0.51"))
STALE_SIGNAL_ABORT_MS = int(os.getenv("KIBOT_STALE_SIGNAL_ABORT_MS", "500"))
FOMO_GUARD_PCT = float(os.getenv("KIBOT_FOMO_GUARD_PCT", "15.0"))
FOMO_LIMIT_CORRECTION_PCT = float(os.getenv("KIBOT_FOMO_LIMIT_CORRECTION_PCT", "4.0"))
COINGECKO_BASE = os.getenv("COINGECKO_BASE_URL", "https://api.coingecko.com/api/v3")
NEWS_SCAN_INTERVAL_SEC = int(os.getenv("KIBOT_NEWS_SCAN_INTERVAL_SEC", "45"))
BINANCE_ANNOUNCEMENT_RSS = os.getenv(
    "BINANCE_ANNOUNCEMENT_RSS",
    "https://www.binance.com/en/support/announcement/rss",
)
COINGECKO_NEWS_FEED = os.getenv(
    "COINGECKO_NEWS_FEED",
    "https://www.coingecko.com/en/rss",
)
POST_MORTEM_ENABLED = os.getenv("KIBOT_POST_MORTEM_ENABLED", "true").lower() in {"1", "true", "yes", "on"}
POST_MORTEM_API_URL = os.getenv("KIBOT_POST_MORTEM_API_URL", "")
POST_MORTEM_API_KEY = os.getenv("KIBOT_POST_MORTEM_API_KEY", "")
POST_MORTEM_MODEL = os.getenv("KIBOT_POST_MORTEM_MODEL", "llama-3.1-8b-instant")
POST_MORTEM_TIMEOUT_SEC = float(os.getenv("KIBOT_POST_MORTEM_TIMEOUT_SEC", "12"))
AI_APPROVAL_MIN_SCORE = float(os.getenv("KIBOT_AI_APPROVAL_MIN_SCORE", "0.62"))
AI_APPROVAL_MIN_EXPECTED_NET_PCT = float(os.getenv("KIBOT_AI_APPROVAL_MIN_EXPECTED_NET_PCT", "0.0018"))
AI_APPROVAL_INSTANT_MIN_SCORE = float(os.getenv("KIBOT_AI_APPROVAL_INSTANT_MIN_SCORE", "0.62"))
AI_APPROVAL_INSTANT_MIN_EXPECTED_NET_PCT = float(os.getenv("KIBOT_AI_APPROVAL_INSTANT_MIN_EXPECTED_NET_PCT", "0.0018"))
INDODAX_TAKER_FEE = float(os.getenv("KIBOT_INDODAX_TAKER_FEE", "0.003"))
INDODAX_MAKER_FEE = float(os.getenv("KIBOT_INDODAX_MAKER_FEE", "0.0015"))
ROUND_TRIP_TAKER_COST = float(os.getenv("KIBOT_ROUND_TRIP_TAKER_COST", "0.006"))
ROUND_TRIP_MAKER_COST = float(os.getenv("KIBOT_ROUND_TRIP_MAKER_COST", "0.003"))
SLIPPAGE_BUFFER = float(os.getenv("KIBOT_SLIPPAGE_BUFFER", "0.002"))
MIN_GROSS_PROFIT_TARGET = float(os.getenv("KIBOT_MIN_GROSS_PROFIT_TARGET", "0.011"))
PARTIAL_TP_TRIGGER = float(os.getenv("KIBOT_PARTIAL_TP_TRIGGER", "0.012"))
PARTIAL_TP_SIZE = float(os.getenv("KIBOT_PARTIAL_TP_SIZE", "0.40"))
TRAILING_STOP_MIN_PCT = float(os.getenv("KIBOT_TRAILING_STOP_MIN_PCT", "0.015"))
POST_MORTEM_BLACKLIST_ENABLED = os.getenv("KIBOT_POST_MORTEM_BLACKLIST_ENABLED", "true").lower() in {"1", "true", "yes", "on"}
POST_MORTEM_BLACKLIST_MINUTES = int(os.getenv("KIBOT_POST_MORTEM_BLACKLIST_MINUTES", "30"))
POST_MORTEM_BLACKLIST_NET_LOSS_IDR = float(os.getenv("KIBOT_POST_MORTEM_BLACKLIST_NET_LOSS_IDR", "500"))
POST_MORTEM_BLACKLIST_PNL_PCT = float(os.getenv("KIBOT_POST_MORTEM_BLACKLIST_PNL_PCT", "-1.0"))
MINIMUM_VIABLE_CAPITAL_IDR = float(os.getenv("KIBOT_MINIMUM_VIABLE_CAPITAL_IDR", "300000"))
MINIMUM_POSITION_SIZE_IDR = float(os.getenv("KIBOT_MINIMUM_POSITION_SIZE_IDR", "10000"))
MAXIMUM_POSITION_SIZE_IDR = float(os.getenv("KIBOT_MAXIMUM_POSITION_SIZE_IDR", "15000"))
MAXIMUM_ACTIVE_POSITIONS = int(os.getenv("KIBOT_MAXIMUM_ACTIVE_POSITIONS", "2"))
INDODAX_ALL_IN_TAKER_FEE_PCT = float(os.getenv("KIBOT_INDODAX_ALL_IN_TAKER_FEE_PCT", "0.0055"))
INDODAX_ALL_IN_MAKER_FEE_PCT = float(os.getenv("KIBOT_INDODAX_ALL_IN_MAKER_FEE_PCT", "0.0004"))
INDODAX_LIMIT_FILL_RATE = float(os.getenv("KIBOT_INDODAX_LIMIT_FILL_RATE", "0.70"))
SURVIVAL_MODE = os.getenv("KIBOT_SURVIVAL_MODE", "true").lower() in {"1", "true", "yes", "on"}
SURVIVAL_MODE_EQUITY_THRESHOLD_IDR = float(os.getenv("KIBOT_SURVIVAL_MODE_EQUITY_THRESHOLD_IDR", "200000"))
SURVIVAL_ALLOWED_PAIRS = tuple(
    pair.strip().lower()
    for pair in os.getenv(
        "KIBOT_SURVIVAL_ALLOWED_PAIRS",
        "xlm_idr,doge_idr,xrp_idr,trx_idr,ada_idr,bnb_idr,enj_idr,fun_idr,arb_idr,inj_idr,ondo_idr,wld_idr,tia_idr,ethfi_idr,sol_idr,near_idr,hbar_idr,link_idr,atom_idr,avax_idr,ton_idr,sui_idr,pol_idr,ldo_idr,op_idr,render_idr,grt_idr,lunc_idr,pepe_idr,shib_idr,bonk_idr,wif_idr,floki_idr,bome_idr,cat_idr,fartcoin_idr",
    ).split(",")
    if pair.strip()
)
SURVIVAL_MIN_DAILY_VOLUME_IDR = float(os.getenv("KIBOT_SURVIVAL_MIN_DAILY_VOLUME_IDR", "500000000"))
SURVIVAL_MAX_SPREAD_PCT = float(os.getenv("KIBOT_SURVIVAL_MAX_SPREAD_PCT", "0.008"))
SURVIVAL_MAX_SLIPPAGE_PCT = float(os.getenv("KIBOT_SURVIVAL_MAX_SLIPPAGE_PCT", "0.010"))
SURVIVAL_TARGET_PROFIT_PCT = float(os.getenv("KIBOT_SURVIVAL_TARGET_PROFIT_PCT", "0.025"))
SURVIVAL_HARD_STOP_PCT = float(os.getenv("KIBOT_SURVIVAL_HARD_STOP_PCT", "0.01"))
CAPITAL_BUCKET_NORMAL_THRESHOLD_IDR = float(os.getenv("KIBOT_CAPITAL_BUCKET_NORMAL_THRESHOLD_IDR", "100000"))
CAPITAL_BUCKET_CONSERVATIVE_THRESHOLD_IDR = float(os.getenv("KIBOT_CAPITAL_BUCKET_CONSERVATIVE_THRESHOLD_IDR", str(MINIMUM_VIABLE_CAPITAL_IDR)))
CAPITAL_BUCKET_EXPANSION_THRESHOLD_IDR = float(os.getenv("KIBOT_CAPITAL_BUCKET_EXPANSION_THRESHOLD_IDR", "300000"))
CAPITAL_BUCKET_FULL_EXPANSION_THRESHOLD_IDR = float(os.getenv("KIBOT_CAPITAL_BUCKET_FULL_EXPANSION_THRESHOLD_IDR", "750000"))

PAIR_CONFIG: Dict[str, Dict[str, Any]] = {
    "xlm_idr": {"tier": "A", "max_size_idr": 15000.0, "min_target_profit_pct": 0.020, "max_spread_pct": 0.010, "max_slippage_pct": 0.012},
    "doge_idr": {"tier": "A", "max_size_idr": 15000.0, "min_target_profit_pct": 0.020, "max_spread_pct": 0.010, "max_slippage_pct": 0.012},
    "xrp_idr": {"tier": "A", "max_size_idr": 15000.0, "min_target_profit_pct": 0.020, "max_spread_pct": 0.010, "max_slippage_pct": 0.012},
    "trx_idr": {"tier": "A", "max_size_idr": 15000.0, "min_target_profit_pct": 0.020, "max_spread_pct": 0.012, "max_slippage_pct": 0.012},
    "ada_idr": {"tier": "A", "max_size_idr": 15000.0, "min_target_profit_pct": 0.020, "max_spread_pct": 0.012, "max_slippage_pct": 0.012},
    "bnb_idr": {"tier": "B", "max_size_idr": 12000.0, "min_target_profit_pct": 0.025, "max_spread_pct": 0.015, "max_slippage_pct": 0.015},
    "enj_idr": {"tier": "B", "max_size_idr": 12000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.020, "max_slippage_pct": 0.020},
    "fun_idr": {"tier": "B", "max_size_idr": 12000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.020, "max_slippage_pct": 0.020},
    "arb_idr": {"tier": "B", "max_size_idr": 12000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.018, "max_slippage_pct": 0.018},
    "inj_idr": {"tier": "B", "max_size_idr": 12000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.018, "max_slippage_pct": 0.018},
    "ondo_idr": {"tier": "B", "max_size_idr": 12000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.018, "max_slippage_pct": 0.018},
    "wld_idr": {"tier": "B", "max_size_idr": 10000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.018, "max_slippage_pct": 0.018},
    "tia_idr": {"tier": "B", "max_size_idr": 10000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.018, "max_slippage_pct": 0.018},
    "ethfi_idr": {"tier": "B", "max_size_idr": 10000.0, "min_target_profit_pct": 0.035, "max_spread_pct": 0.020, "max_slippage_pct": 0.020},
    "sol_idr": {"tier": "B", "max_size_idr": 10000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.020, "max_slippage_pct": 0.020},
    "near_idr": {"tier": "C", "max_size_idr": 10000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.020, "max_slippage_pct": 0.020},
    "hbar_idr": {"tier": "C", "max_size_idr": 10000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.020, "max_slippage_pct": 0.020},
    "link_idr": {"tier": "C", "max_size_idr": 10000.0, "min_target_profit_pct": 0.025, "max_spread_pct": 0.018, "max_slippage_pct": 0.018},
    "atom_idr": {"tier": "C", "max_size_idr": 10000.0, "min_target_profit_pct": 0.025, "max_spread_pct": 0.018, "max_slippage_pct": 0.018},
    "avax_idr": {"tier": "C", "max_size_idr": 10000.0, "min_target_profit_pct": 0.025, "max_spread_pct": 0.018, "max_slippage_pct": 0.018},
    "ton_idr": {"tier": "C", "max_size_idr": 10000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.020, "max_slippage_pct": 0.020},
    "sui_idr": {"tier": "C", "max_size_idr": 10000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.020, "max_slippage_pct": 0.020},
    "pol_idr": {"tier": "C", "max_size_idr": 10000.0, "min_target_profit_pct": 0.030, "max_spread_pct": 0.020, "max_slippage_pct": 0.020},
    "ldo_idr": {"tier": "C", "max_size_idr": 8000.0, "min_target_profit_pct": 0.035, "max_spread_pct": 0.022, "max_slippage_pct": 0.022},
    "op_idr": {"tier": "C", "max_size_idr": 8000.0, "min_target_profit_pct": 0.035, "max_spread_pct": 0.022, "max_slippage_pct": 0.022},
    "render_idr": {"tier": "C", "max_size_idr": 8000.0, "min_target_profit_pct": 0.035, "max_spread_pct": 0.022, "max_slippage_pct": 0.022},
    "grt_idr": {"tier": "C", "max_size_idr": 8000.0, "min_target_profit_pct": 0.035, "max_spread_pct": 0.022, "max_slippage_pct": 0.022},
    "lunc_idr": {"tier": "C", "max_size_idr": 6000.0, "min_target_profit_pct": 0.040, "max_spread_pct": 0.025, "max_slippage_pct": 0.025},
    "pepe_idr": {"tier": "D", "max_size_idr": 6000.0, "min_target_profit_pct": 0.040, "max_spread_pct": 0.025, "max_slippage_pct": 0.025},
    "shib_idr": {"tier": "D", "max_size_idr": 6000.0, "min_target_profit_pct": 0.040, "max_spread_pct": 0.025, "max_slippage_pct": 0.025},
    "bonk_idr": {"tier": "D", "max_size_idr": 6000.0, "min_target_profit_pct": 0.045, "max_spread_pct": 0.028, "max_slippage_pct": 0.028},
    "wif_idr": {"tier": "D", "max_size_idr": 6000.0, "min_target_profit_pct": 0.045, "max_spread_pct": 0.028, "max_slippage_pct": 0.028},
    "floki_idr": {"tier": "D", "max_size_idr": 5000.0, "min_target_profit_pct": 0.045, "max_spread_pct": 0.030, "max_slippage_pct": 0.030},
    "bome_idr": {"tier": "D", "max_size_idr": 5000.0, "min_target_profit_pct": 0.050, "max_spread_pct": 0.030, "max_slippage_pct": 0.030},
    "cat_idr": {"tier": "D", "max_size_idr": 5000.0, "min_target_profit_pct": 0.050, "max_spread_pct": 0.030, "max_slippage_pct": 0.030},
    "fartcoin_idr": {"tier": "D", "max_size_idr": 5000.0, "min_target_profit_pct": 0.055, "max_spread_pct": 0.035, "max_slippage_pct": 0.035},
}


def _get_pair_config(pair_id: str) -> Dict[str, Any]:
    return dict(PAIR_CONFIG.get(str(pair_id or "").lower().strip(), {"tier": "C", "max_size_idr": 0.0, "min_target_profit_pct": SURVIVAL_TARGET_PROFIT_PCT, "max_spread_pct": SURVIVAL_MAX_SPREAD_PCT, "max_slippage_pct": SURVIVAL_MAX_SLIPPAGE_PCT}))


def _target_profit_pct_for_pair(pair_id: str) -> float:
    return float(_get_pair_config(pair_id).get("min_target_profit_pct", SURVIVAL_TARGET_PROFIT_PCT))


def _capital_bucket_tiers(equity_idr: Optional[float] = None) -> List[str]:
    equity = float(equity_idr if equity_idr is not None else (_get_total_equity_estimate() or 0.0))
    if equity < CAPITAL_BUCKET_NORMAL_THRESHOLD_IDR:
        return ["A", "B", "C", "D"]
    if equity < CAPITAL_BUCKET_CONSERVATIVE_THRESHOLD_IDR:
        return ["A"]
    if equity < CAPITAL_BUCKET_EXPANSION_THRESHOLD_IDR:
        return ["A", "B", "C"]
    if equity < CAPITAL_BUCKET_FULL_EXPANSION_THRESHOLD_IDR:
        return ["A", "B", "C", "D"]
    return ["A", "B", "C", "D"]


def _capital_risk_multiplier(equity_idr: Optional[float] = None) -> float:
    equity = float(equity_idr if equity_idr is not None else (_get_total_equity_estimate() or 0.0))
    if equity < CAPITAL_BUCKET_NORMAL_THRESHOLD_IDR:
        return 0.35
    if equity < CAPITAL_BUCKET_CONSERVATIVE_THRESHOLD_IDR:
        return 0.50
    if equity < CAPITAL_BUCKET_EXPANSION_THRESHOLD_IDR:
        return 0.70
    if equity < CAPITAL_BUCKET_FULL_EXPANSION_THRESHOLD_IDR:
        return 0.85
    return 1.0


# === KINANCE HEALTH MONITORING ===
KINANCE_HEARTBEAT_TIMEOUT_SEC = 10.0
_last_kinance_heartbeat_at: float = 0.0
_kinance_healthy: bool = True


DAILY_SUMMARY_ENABLED = os.getenv("KIBOT_DAILY_SUMMARY_ENABLED", "true").lower() in {"1", "true", "yes", "on"}
CORRELATION_ENABLED = os.getenv("KIBOT_CORRELATION_ENABLED", "true").lower() in {"1", "true", "yes", "on"}
CORRELATION_INTERVAL_SEC = int(os.getenv("KIBOT_CORRELATION_INTERVAL_SEC", "1800"))
CORRELATION_API_URL = os.getenv("KIBOT_CORRELATION_API_URL", POST_MORTEM_API_URL)
CORRELATION_API_KEY = os.getenv("KIBOT_CORRELATION_API_KEY", POST_MORTEM_API_KEY)
CORRELATION_MODEL = os.getenv("KIBOT_CORRELATION_MODEL", POST_MORTEM_MODEL)
CORRELATION_TIMEOUT_SEC = float(os.getenv("KIBOT_CORRELATION_TIMEOUT_SEC", "20"))
AI_ROUTER_ENABLED = os.getenv("KIBOT_AI_ROUTER_ENABLED", "true").lower() in {"1", "true", "yes", "on"}
AI_PROVIDER_ORDER = [
    token.strip().lower()
    for token in os.getenv(
        "KIBOT_AI_PROVIDER_ORDER",
        "groq,openrouter,cohere,gemini",
    ).split(",")
    if token.strip()
]
AI_REQUEST_TIMEOUT_SEC = float(os.getenv("KIBOT_AI_REQUEST_TIMEOUT_SEC", "18"))
AI_PROVIDER_DEFAULT_COOLDOWN_SEC = int(os.getenv("KIBOT_AI_PROVIDER_DEFAULT_COOLDOWN_SEC", "600"))
AI_PROVIDER_NETWORK_COOLDOWN_SEC = int(os.getenv("KIBOT_AI_PROVIDER_NETWORK_COOLDOWN_SEC", "180"))
AI_PROVIDER_RATE_LIMIT_COOLDOWN_SEC = int(os.getenv("KIBOT_AI_PROVIDER_RATE_LIMIT_COOLDOWN_SEC", "3600"))
AI_PROVIDER_EMPTY_COOLDOWN_SEC = int(os.getenv("KIBOT_AI_PROVIDER_EMPTY_COOLDOWN_SEC", "120"))
STATE_ROOT = Path(os.getenv("KIBOT_MANAGER_STATE_DIR", str(Path.cwd() / ".state")))
PROVIDER_STATE_PATH = Path(
    os.getenv("KIBOT_MANAGER_PROVIDER_STATE_FILE", str(STATE_ROOT / "ai_provider_state.json"))
)
RUNTIME_NOTE_PATH = Path(
    os.getenv("KIBOT_MANAGER_RUNTIME_NOTE_FILE", str(STATE_ROOT / "runtime_note.json"))
)
RUNTIME_NOTE_MIN_INTERVAL_SEC = int(os.getenv("KIBOT_MANAGER_RUNTIME_NOTE_MIN_INTERVAL_SEC", "15"))
DAILY_SUMMARY_PATH = Path(os.getenv("KIBOT_MANAGER_DAILY_SUMMARY_FILE", str(STATE_ROOT / "daily_summary.json")))
PAIR_MEMORY_PATH = Path(os.getenv("KIBOT_MANAGER_PAIR_MEMORY_FILE", str(STATE_ROOT / "pair_memory.json")))
PAIR_MEMORY_ROLLING_WINDOW = int(os.getenv("KIBOT_PAIR_MEMORY_ROLLING_WINDOW", "50"))
PAIR_MEMORY_MIN_TRADES_FOR_WINRATE = int(os.getenv("KIBOT_PAIR_MEMORY_MIN_TRADES_FOR_WINRATE", "3"))
AI_BATCH_REVIEW_INTERVAL_SEC = int(os.getenv("KIBOT_AI_BATCH_REVIEW_INTERVAL_SEC", str(6 * 60 * 60)))

GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
GROQ_MODEL = os.getenv("GROQ_MODEL", "llama-3.1-8b-instant")
GROQ_API_URL = os.getenv("GROQ_API_URL", "https://api.groq.com/openai/v1/chat/completions")

GEMINI_API_KEY = _env_first("GEMINI_API_KEY", "GEMINI_SUPPORT_API_KEY")
GEMINI_MODEL = _env_first("GEMINI_MODEL", "GEMINI_SUPPORT_MODEL", default="gemini-2.0-flash-lite")
GEMINI_API_URL = os.getenv("GEMINI_API_URL", "https://generativelanguage.googleapis.com/v1beta")

OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY", "")
OPENROUTER_MODEL = os.getenv("OPENROUTER_MODEL", "meta-llama/llama-3.1-8b-instruct")
OPENROUTER_API_URL = os.getenv("OPENROUTER_API_URL", "https://openrouter.ai/api/v1/chat/completions")

COHERE_API_KEY = os.getenv("COHERE_API_KEY", "")
COHERE_MODEL = os.getenv("COHERE_MODEL", "command-r")
COHERE_API_URL = os.getenv("COHERE_API_URL", "https://api.cohere.com/v2/chat")

BLACKBOX_API_KEY = os.getenv("BLACKBOX_API_KEY", "")
BLACKBOX_MODEL = os.getenv("BLACKBOX_MODEL", "blackboxai/openai/gpt-4o-mini")
BLACKBOX_API_URL = os.getenv("BLACKBOX_API_URL", "https://api.blackbox.ai/v1/chat/completions")

_ai_provider_last_status: Dict[str, Any] = {
    "provider": "",
    "task": "",
    "at_epoch_ms": 0,
    "ok": False,
}
COINGECKO_TRENDING_INTERVAL_SEC = int(os.getenv("KIBOT_COINGECKO_TRENDING_INTERVAL_SEC", "300"))
INDODAX_SUMMARIES_URL = os.getenv("INDODAX_SUMMARIES_URL", "https://indodax.com/api/summaries")
INDODAX_TICKER_CACHE_TTL_SEC = int(os.getenv("KIBOT_INDODAX_TICKER_CACHE_TTL_SEC", "600"))
EMERGENCY_SELL_NEGATIVE_PNL_PCT = float(os.getenv("KIBOT_EMERGENCY_SELL_NEGATIVE_PNL_PCT", "-2.2"))
EMERGENCY_SELL_COOLDOWN_SEC = int(os.getenv("KIBOT_EMERGENCY_SELL_COOLDOWN_SEC", "20"))
API_HEALTH_CHECK_INTERVAL_SEC = float(os.getenv("KIBOT_API_HEALTH_CHECK_INTERVAL_SEC", "15"))
API_HEALTH_FAIL_THRESHOLD = int(os.getenv("KIBOT_API_HEALTH_FAIL_THRESHOLD", "2"))
CONTROL_PLANE_TIMEOUT_SEC = float(os.getenv("KIBOT_CONTROL_PLANE_TIMEOUT_SEC", "3.0"))
CONTROL_PLANE_STALE_SEC = float(os.getenv("KIBOT_CONTROL_PLANE_STALE_SEC", "30"))
DAILY_LOSS_LIMIT_PCT = float(os.getenv("KIBOT_DAILY_LOSS_LIMIT_PCT", "0.02"))
WIB_UTC_OFFSET_HOURS = int(os.getenv("KIBOT_WIB_UTC_OFFSET_HOURS", "7"))
DAILY_GUARD_STATE_PATH = Path(os.getenv("KIBOT_MANAGER_DAILY_GUARD_FILE", str(STATE_ROOT / "daily_guard.json")))
MANAGER_GATE_STATE_PATH = Path(os.getenv("KIBOT_MANAGER_GATE_STATE_FILE", str(STATE_ROOT / "manager_gate.json")))
SAFE_ENTRY_MSG_TYPES = {"DETECTOR_HIT", "INSTANT_BUY_ANOMALY"}
EXIT_MSG_TYPES = {"SELL_WALL_SURGE", "MOMENTUM_LOSS", "TRAILING_STOP_HIT", "THESIS_INVALID_EXIT"}
# Maximum size for unbounded caches
_SEEN_NEWS_IDS_MAX_SIZE = int(os.getenv("KIBOT_SEEN_NEWS_IDS_MAX_SIZE", "5000"))
_seen_news_ids: set[str] = set()
_seen_news_ids_timestamps: Dict[str, float] = {}  # Track when IDs were added for TTL cleanup
_indodax_ticker_cache: set[str] = set()
_indodax_ticker_cache_at: float = 0.0
_coingecko_trending_cache: Dict[str, Any] = {"coins": [], "fetched_at_epoch_ms": 0}
_last_sector_map: Dict[str, list[str]] = {}
_active_positions_cache: Dict[str, Dict[str, Any]] = {}
_emergency_sell_cooldown_until: Dict[str, float] = {}
_last_active_positions_log_at: float = 0.0
_last_runtime_note_write_at: float = 0.0
_recent_runtime_events: List[Dict[str, Any]] = []
_veto_metrics: Dict[str, int] = {"approved": 0, "rejected": 0, "sell_confirmed": 0, "emergency_sell": 0}
_api_fail_streak: int = 0
_api_health_state: str = "HEALTHY"
_control_plane_healthy: bool = True
_control_plane_last_success_at: float = 0.0
_capital_sufficient_since_at: float = 0.0
_normal_mode_promotion_grace_sec: float = float(os.getenv("KIBOT_NORMAL_PROMOTION_GRACE_SEC", "1800"))
_gate_state: Dict[str, Any] = _load_json_file(
    MANAGER_GATE_STATE_PATH,
    {
        "mode": "CONSERVATIVE",
        "entry_state": "HEALTHY",
        "reason": "",
        "updated_at": "",
        "daily_hard_stop": False,
        "daily_hard_stop_reset_at": "",
        "daily_hard_stop_reason": "",
    },
)
_daily_guard_state: Dict[str, Any] = _load_json_file(
    DAILY_GUARD_STATE_PATH,
    {
        "date": "",
        "start_of_day_equity": None,
        "current_equity": None,
        "daily_pnl_pct": None,
        "hard_stopped": False,
        "triggered_at": "",
        "reset_at": "",
        "reason": "",
    },
)
_pair_memory: Dict[str, Dict[str, Any]] = _load_json_file(PAIR_MEMORY_PATH, {})


def _clean_pair_memory() -> None:
    invalid_keys = [key for key in _pair_memory.keys() if not key or key.strip().lower() in {"unknown", "null", "none"}]
    if not invalid_keys:
        return
    for key in invalid_keys:
        _pair_memory.pop(key, None)
        print(f"[KIBOT][LEARNING] removed invalid pair_memory key='{key}'", flush=True)
    _write_json_file(PAIR_MEMORY_PATH, _pair_memory)


_clean_pair_memory()


_provider_runtime_state: Dict[str, Dict[str, Any]] = _load_json_file(PROVIDER_STATE_PATH, {})
_pair_cooldown_state: Dict[str, Dict[str, Any]] = _load_json_file(STATE_ROOT / "pair_cooldowns.json", {})


def _save_pair_cooldown_state() -> None:
    _write_json_file(STATE_ROOT / "pair_cooldowns.json", _pair_cooldown_state)


def _save_gate_state() -> None:
    _write_json_file(MANAGER_GATE_STATE_PATH, _gate_state)


def _save_daily_guard_state() -> None:
    _write_json_file(DAILY_GUARD_STATE_PATH, _daily_guard_state)


def _save_pair_memory_state() -> None:
    _write_json_file(PAIR_MEMORY_PATH, _pair_memory)


def _default_pair_memory() -> Dict[str, Any]:
    return {
        "slippage_history": [],
        "spread_observed": [],
        "win_rate_by_hour": {},
        "last_loss_at": None,
        "cooldown_until": None,
        "fake_pump_count": 0,
        "avg_execution_latency_ms": 0.0,
        "trade_count": 0,
        "win_count": 0,
        "last_updated_at": "",
    }


def _wib_hour_now() -> int:
    return (datetime.now(timezone.utc) + timedelta(hours=WIB_UTC_OFFSET_HOURS)).hour


def _pair_memory_for(pair_id: str) -> Dict[str, Any]:
    pair_key = pair_id.lower().strip()
    if not pair_key:
        return _default_pair_memory()
    memory = _pair_memory.setdefault(pair_key, _default_pair_memory())
    if "trade_count" not in memory:
        memory.update(_default_pair_memory())
    return memory


def _update_pair_memory(
    pair_id: str,
    *,
    pnl_pct: float,
    slippage_pct: float | None = None,
    spread_pct: float | None = None,
    actual_latency_ms: float | None = None,
    fake_pump: bool = False,
    loss_threshold_pct: float = 0.75,
    cooldown_sec: int = 60 * 60,
) -> None:
    pair_key = pair_id.lower().strip()
    if not pair_key or pair_key in {"unknown", "null", "none"}:
        print(f"[KIBOT][LEARNING][WARN] skip invalid pair_id='{pair_id}'", flush=True)
        return
    memory = _pair_memory_for(pair_key)
    if slippage_pct is not None:
        history = list(memory.get("slippage_history") or [])
        history.append(float(slippage_pct))
        memory["slippage_history"] = history[-PAIR_MEMORY_ROLLING_WINDOW:]
    if spread_pct is not None:
        history = list(memory.get("spread_observed") or [])
        history.append(float(spread_pct))
        memory["spread_observed"] = history[-PAIR_MEMORY_ROLLING_WINDOW:]
    if actual_latency_ms is not None and actual_latency_ms > 0:
        current = float(memory.get("avg_execution_latency_ms") or 0.0)
        count = int(memory.get("trade_count") or 0)
        memory["avg_execution_latency_ms"] = round(((current * count) + actual_latency_ms) / (count + 1), 3)
    hour_bucket = str(_wib_hour_now())
    win_rate_by_hour = memory.setdefault("win_rate_by_hour", {})
    hour_stats = win_rate_by_hour.setdefault(hour_bucket, {"wins": 0, "total": 0})
    hour_stats["total"] += 1
    memory["trade_count"] = int(memory.get("trade_count") or 0) + 1
    if pnl_pct > 0:
        hour_stats["wins"] += 1
        memory["win_count"] = int(memory.get("win_count") or 0) + 1
    else:
        memory["last_loss_at"] = datetime.now(timezone.utc).isoformat()
        if pnl_pct <= -abs(loss_threshold_pct):
            memory["cooldown_until"] = time.time() + cooldown_sec
    if fake_pump:
        memory["fake_pump_count"] = int(memory.get("fake_pump_count") or 0) + 1
    memory["last_updated_at"] = datetime.now(timezone.utc).isoformat()
    memory["avg_pnl"] = round(
        ((float(memory.get("avg_pnl") or 0.0) * max(0, int(memory.get("trade_count") or 0) - 1)) + float(pnl_pct))
        / max(1, int(memory.get("trade_count") or 0)),
        5,
    )
    memory["win_rate_7d"] = round(
        float(memory.get("win_count") or 0) / max(1, int(memory.get("trade_count") or 0)),
        5,
    )
    _pair_memory[pair_key] = memory
    _save_pair_memory_state()


def _get_pair_avg_slippage(pair_id: str, fallback: float = 0.0) -> float:
    memory = _pair_memory.get(pair_id.lower().strip(), {})
    history = memory.get("slippage_history") or []
    if not history:
        return fallback
    return sum(float(value) for value in history) / len(history)


def _get_pair_win_rate_now(pair_id: str) -> float:
    memory = _pair_memory.get(pair_id.lower().strip(), {})
    hour_bucket = str(_wib_hour_now())
    hour_stats = (memory.get("win_rate_by_hour") or {}).get(hour_bucket)
    if not hour_stats or int(hour_stats.get("total") or 0) < PAIR_MEMORY_MIN_TRADES_FOR_WINRATE:
        return 0.5
    total = float(hour_stats.get("total") or 0)
    return float(hour_stats.get("wins") or 0) / total if total > 0 else 0.5


def _is_pair_on_cooldown(pair_id: str) -> bool:
    memory = _pair_memory.get(pair_id.lower().strip(), {})
    cooldown_until = memory.get("cooldown_until")
    if cooldown_until is None:
        return False
    try:
        return time.time() < float(cooldown_until)
    except Exception:
        return False


def _daily_summary_market_regime() -> str:
    regime = _gate_state.get("market_regime")
    if regime:
        return str(regime)
    return str(_load_daily_summary().get("market_regime") or "UNKNOWN")


def _next_wib_midnight_iso() -> str:
    now_utc = datetime.now(timezone.utc)
    now_wib = now_utc + timedelta(hours=WIB_UTC_OFFSET_HOURS)
    midnight_wib = datetime.combine(now_wib.date() + timedelta(days=1), datetime.min.time())
    reset_utc = midnight_wib - timedelta(hours=WIB_UTC_OFFSET_HOURS)
    return reset_utc.replace(tzinfo=timezone.utc).isoformat()


def _entry_state_is_suspended() -> bool:
    return str(_gate_state.get("entry_state") or "HEALTHY").upper() != "HEALTHY"


def _set_entry_state(entry_state: str, *, reason: str = "", daily_hard_stop: bool | None = None) -> None:
    normalized = entry_state.upper().strip() or "HEALTHY"
    _gate_state["entry_state"] = normalized
    _gate_state["reason"] = reason
    _gate_state["updated_at"] = datetime.now(timezone.utc).isoformat()
    if daily_hard_stop is not None:
        _gate_state["daily_hard_stop"] = bool(daily_hard_stop)
    _save_gate_state()


def _suspend_new_entries(reason: str, *, daily_hard_stop: bool = False) -> None:
    current = str(_gate_state.get("entry_state") or "HEALTHY").upper()
    if current == "SUSPENDED" and _gate_state.get("reason") == reason and bool(_gate_state.get("daily_hard_stop")) == bool(daily_hard_stop):
        return
    _set_entry_state("SUSPENDED", reason=reason, daily_hard_stop=daily_hard_stop)
    _append_runtime_event("entry_suspended", {"reason": reason, "daily_hard_stop": daily_hard_stop})
    print(f"[KIBOT][GATE] entry suspended reason={reason} daily_hard_stop={daily_hard_stop}", flush=True)


def _resume_new_entries(reason: str) -> None:
    if not _entry_state_is_suspended():
        return
    if bool(_gate_state.get("daily_hard_stop")):
        return
    _set_entry_state("HEALTHY", reason=reason, daily_hard_stop=False)
    _append_runtime_event("entry_resumed", {"reason": reason})
    print(f"[KIBOT][GATE] entry resumed reason={reason}", flush=True)


def _set_conservative_mode(reason: str) -> None:
    if str(_gate_state.get("mode") or "CONSERVATIVE").upper() == "CONSERVATIVE":
        return
    _gate_state["mode"] = "CONSERVATIVE"
    _gate_state["updated_at"] = datetime.now(timezone.utc).isoformat()
    _gate_state["reason"] = reason
    _save_gate_state()
    _append_runtime_event("trading_mode_changed", {"mode": "CONSERVATIVE", "reason": reason})
    print(f"[KIBOT][MODE] switched to CONSERVATIVE reason={reason}", flush=True)


def _set_normal_mode(reason: str) -> None:
    if str(_gate_state.get("mode") or "CONSERVATIVE").upper() == "NORMAL":
        return
    _gate_state["mode"] = "NORMAL"
    _gate_state["updated_at"] = datetime.now(timezone.utc).isoformat()
    _gate_state["reason"] = reason
    _save_gate_state()
    _append_runtime_event("trading_mode_changed", {"mode": "NORMAL", "reason": reason})
    print(f"[KIBOT][MODE] switched to NORMAL reason={reason}", flush=True)


def _record_control_plane_success() -> None:
    global _control_plane_healthy, _control_plane_last_success_at, _api_fail_streak, _api_health_state
    _control_plane_healthy = True
    _control_plane_last_success_at = time.time()
    if _api_fail_streak != 0 or _api_health_state != "HEALTHY":
        _api_fail_streak = 0
        _api_health_state = "HEALTHY"
        print("[KIBOT][HEALTH] API/control-plane recovered", flush=True)
        _append_runtime_event("api_health", {"state": "HEALTHY"})


def _record_control_plane_failure(reason: str) -> None:
    global _control_plane_healthy, _api_fail_streak, _api_health_state
    _control_plane_healthy = False
    _api_fail_streak += 1
    if _api_fail_streak >= API_HEALTH_FAIL_THRESHOLD:
        if _api_health_state != "SUSPENDED":
            _api_health_state = "SUSPENDED"
            _append_runtime_event("api_health", {"state": "SUSPENDED", "reason": reason, "streak": _api_fail_streak})
            print(f"[KIBOT][HEALTH] API suspended reason={reason} streak={_api_fail_streak}", flush=True)
        _suspend_new_entries(reason="API health fail streak")
    else:
        if _api_health_state != "DEGRADED":
            _api_health_state = "DEGRADED"
            _append_runtime_event("api_health", {"state": "DEGRADED", "reason": reason, "streak": _api_fail_streak})
        print(f"[KIBOT][HEALTH] API degraded reason={reason} streak={_api_fail_streak}", flush=True)


def _daily_guard_reset_due() -> bool:
    reset_at = str(_daily_guard_state.get("reset_at") or "")
    if not reset_at:
        return False
    try:
        return datetime.now(timezone.utc) >= datetime.fromisoformat(reset_at)
    except Exception:
        return False


def _refresh_daily_guard_from_equity(current_equity: float | None) -> None:
    today = (datetime.now(timezone.utc) + timedelta(hours=WIB_UTC_OFFSET_HOURS)).date().isoformat()
    if _daily_guard_state.get("date") != today:
        had_daily_hard_stop = bool(_gate_state.get("daily_hard_stop"))
        _daily_guard_state.update(
            {
                "date": today,
                "start_of_day_equity": current_equity,
                "current_equity": current_equity,
                "daily_pnl_pct": None,
                "hard_stopped": False,
                "triggered_at": "",
                "reset_at": "",
                "reason": "",
            }
        )
        _save_daily_guard_state()
        if had_daily_hard_stop:
            _gate_state["daily_hard_stop"] = False
            _gate_state["daily_hard_stop_reason"] = ""
            _gate_state["daily_hard_stop_reset_at"] = ""
            _save_gate_state()
            _resume_new_entries("new day reset")


def _reconcile_daily_guard_day_rollover() -> None:
    today = (datetime.now(timezone.utc) + timedelta(hours=WIB_UTC_OFFSET_HOURS)).date().isoformat()
    if _daily_guard_state.get("date") == today:
        return
    _daily_guard_state.update(
        {
            "date": today,
            "start_of_day_equity": _daily_guard_state.get("current_equity"),
            "daily_pnl_pct": 0.0,
            "hard_stopped": False,
            "triggered_at": "",
            "reset_at": "",
            "reason": "",
        }
    )
    _save_daily_guard_state()
    if bool(_gate_state.get("daily_hard_stop")):
        _gate_state["daily_hard_stop"] = False
        _gate_state["daily_hard_stop_reason"] = ""
        _gate_state["daily_hard_stop_reset_at"] = ""
        _save_gate_state()
    _resume_new_entries("new day rollover")

def _ensure_hard_stop_consistency() -> None:
    """Clear stale hard-stop flags when the stored PnL no longer breaches today's limit."""
    try:
        daily_pnl_pct = _daily_guard_state.get("daily_pnl_pct")
        if daily_pnl_pct is None:
            return
        daily_pnl_pct = float(daily_pnl_pct)
    except Exception:
        return
    limit = -abs(DAILY_LOSS_LIMIT_PCT)
    if not bool(_daily_guard_state.get("hard_stopped")) and not bool(_gate_state.get("daily_hard_stop")):
        return
    # If we are not breaching the limit anymore for the current day, treat previous hard-stop as stale.
    if daily_pnl_pct > limit:
        _daily_guard_state["hard_stopped"] = False
        _daily_guard_state["triggered_at"] = ""
        _daily_guard_state["reset_at"] = ""
        _daily_guard_state["reason"] = ""
        _save_daily_guard_state()
        _gate_state["daily_hard_stop"] = False
        _gate_state["daily_hard_stop_reason"] = ""
        _gate_state["daily_hard_stop_reset_at"] = ""
        _save_gate_state()
        _resume_new_entries("hard stop cleared (pnl recovered/new day)")


def _trigger_daily_hard_stop(current_equity: float | None, daily_pnl_pct: float) -> None:
    reset_at = _next_wib_midnight_iso()
    _daily_guard_state.update(
        {
            "hard_stopped": True,
            "current_equity": current_equity,
            "daily_pnl_pct": daily_pnl_pct,
            "triggered_at": datetime.now(timezone.utc).isoformat(),
            "reset_at": reset_at,
            "reason": "daily_loss_limit_hit",
        }
    )
    _save_daily_guard_state()
    _gate_state["daily_hard_stop"] = True
    _gate_state["daily_hard_stop_reset_at"] = reset_at
    _gate_state["daily_hard_stop_reason"] = "daily_loss_limit_hit"
    _save_gate_state()
    _suspend_new_entries("daily_loss_limit_hit", daily_hard_stop=True)
    _append_runtime_event("daily_hard_stop", {"daily_pnl_pct": daily_pnl_pct, "reset_at": reset_at})
    _metric_inc("entries_blocked_hard_stop")
    print(f"[KIBOT][GATE] daily hard stop triggered pnl_pct={daily_pnl_pct:.4f} reset_at={reset_at}", flush=True)


def _check_daily_loss_limit(current_equity: float | None = None) -> None:
    if current_equity is None:
        current_equity = float(_daily_guard_state.get("current_equity") or 0.0) or None
    _refresh_daily_guard_from_equity(current_equity)
    if _daily_guard_state.get("hard_stopped") and _daily_guard_reset_due():
        _daily_guard_state.update({"hard_stopped": False, "reason": "", "triggered_at": ""})
        _save_daily_guard_state()
        _gate_state["daily_hard_stop"] = False
        _gate_state["daily_hard_stop_reason"] = ""
        _gate_state["daily_hard_stop_reset_at"] = ""
        _save_gate_state()
        _resume_new_entries("daily hard stop reset")
        print("[KIBOT][GATE] daily hard stop reset", flush=True)
    start_equity = float(_daily_guard_state.get("start_of_day_equity") or 0.0)
    if not start_equity or not current_equity:
        return
    daily_pnl_pct = (float(current_equity) - start_equity) / start_equity
    _daily_guard_state["current_equity"] = float(current_equity)
    _daily_guard_state["daily_pnl_pct"] = daily_pnl_pct
    _save_daily_guard_state()
    if daily_pnl_pct <= -abs(_current_daily_loss_limit_pct()) and not bool(_daily_guard_state.get("hard_stopped")):
        _trigger_daily_hard_stop(current_equity, daily_pnl_pct)


def _bootstrap_daily_guard_from_kidax() -> None:
    # Only bootstrap missing context; do not re-trigger hard stops from external labels.
    if _daily_guard_state.get("start_of_day_equity") is not None and _daily_guard_state.get("current_equity") is not None:
        return
    try:
        response = requests.get("http://127.0.0.1:8787/api/state", timeout=2)
        response.raise_for_status()
        payload = response.json()
    except Exception:
        return

    daily_pnl_pct = None
    for key in ("pnlTodayPct", "pnl_today_pct", "dailyPnlPct", "daily_pnl_pct"):
        value = payload.get(key)
        if isinstance(value, (int, float)):
            daily_pnl_pct = float(value)
            break
    if daily_pnl_pct is None:
        label = str(payload.get("pnlTodayPctLabel") or payload.get("dailyPnlPctLabel") or "")
        match = re.search(r"(-?\d+(?:\.\d+)?)\s*%$", label)
        if match:
            try:
                daily_pnl_pct = float(match.group(1))
            except Exception:
                daily_pnl_pct = None
    # daily_pnl_pct is best-effort; never used to force a hard stop in bootstrap.

    current_equity = None
    for key in ("totalValueIdr", "portfolioValueIdr", "total_value_idr"):
        value = payload.get(key)
        if value is None:
            continue
        cleaned_value = re.sub(r"[^\d.,-]", "", str(value)).replace(".", "").replace(",", ".")
        try:
            current_equity = float(cleaned_value)
        except Exception:
            current_equity = None
        if current_equity is not None and current_equity > 0.0:
            break

    # Seed the daily guard for the current WIB date so hard stop evaluation uses local equity, not external labels.
    _refresh_daily_guard_from_equity(current_equity)
    if daily_pnl_pct is not None and _daily_guard_state.get("daily_pnl_pct") is None:
        _daily_guard_state["daily_pnl_pct"] = daily_pnl_pct
        _daily_guard_state["current_equity"] = current_equity
        _save_daily_guard_state()


def _health_gate_loop() -> None:
    while not _shutdown_event.is_set():
        try:
            if _check_kinance_health():
                _record_control_plane_success()
            else:
                _record_control_plane_failure("kinance_unhealthy")
            _check_daily_loss_limit()
            _ensure_hard_stop_consistency()
            _maybe_auto_promote_trading_mode()
        except Exception as error:
            print(f"[KIBOT][HEALTH][ERROR] gate loop failed reason={error}", flush=True)
        _shutdown_event.wait(API_HEALTH_CHECK_INTERVAL_SEC)


def _build_performance_summary() -> Dict[str, Any]:
    pair_stats: Dict[str, Any] = {}
    for pair_id, memory in _pair_memory.items():
        pair_stats[pair_id] = {
            "win_rate_now": _get_pair_win_rate_now(pair_id),
            "avg_slippage_pct": round(_get_pair_avg_slippage(pair_id, fallback=0.0), 4),
            "on_cooldown": _is_pair_on_cooldown(pair_id),
            "trade_count": int(memory.get("trade_count") or 0),
            "fake_pump_count": int(memory.get("fake_pump_count") or 0),
        }
    return {
        "period_hours": 6,
        "daily_pnl_pct": _daily_guard_state.get("daily_pnl_pct"),
        "hard_stop_active": bool(_daily_guard_state.get("hard_stopped")),
        "api_fail_streak": _api_fail_streak,
        "cp_healthy": _control_plane_healthy,
        "mode": str(_gate_state.get("mode") or "CONSERVATIVE"),
        "entry_state": str(_gate_state.get("entry_state") or "HEALTHY"),
        "pair_stats": pair_stats,
    }


def _hours_until_midnight_wib() -> float:
    now_utc = datetime.now(timezone.utc)
    now_wib = now_utc + timedelta(hours=7)
    midnight_wib = now_wib.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=1)
    return max((midnight_wib - now_wib).total_seconds() / 3600.0, 0.0)


def _get_trade_metrics_today() -> Dict[str, Any]:
    trades = list(_math_review_trade_journal)
    total_trades = len(trades)
    wins = sum(1 for row in trades if float(row.get("gross_pnl_pct") or 0.0) > 0)
    losses = total_trades - wins
    total_gross_pnl = sum(float(row.get("gross_pnl_pct") or 0.0) for row in trades)
    total_wins = sum(float(row.get("gross_pnl_pct") or 0.0) for row in trades if float(row.get("gross_pnl_pct") or 0.0) > 0)
    total_losses = sum(abs(float(row.get("gross_pnl_pct") or 0.0)) for row in trades if float(row.get("gross_pnl_pct") or 0.0) <= 0)
    win_rate = wins / max(total_trades, 1)
    avg_win = total_wins / max(wins, 1)
    avg_loss = total_losses / max(losses, 1)
    ev_per_trade = (win_rate * avg_win) - ((1.0 - win_rate) * avg_loss)
    profit_factor = total_wins / max(total_losses, 1e-9)
    return {
        "total_trades": total_trades,
        "wins": wins,
        "losses": losses,
        "win_rate": win_rate,
        "avg_win": avg_win,
        "avg_loss": avg_loss,
        "ev_per_trade": ev_per_trade,
        "profit_factor": profit_factor,
        "total_gross_pnl": total_gross_pnl,
    }


def _run_math_review() -> Dict[str, Any]:
    metrics = _get_trade_metrics_today()
    equity = _get_total_equity_estimate() or 0.0
    pnl_pct = float(_daily_guard_state.get("daily_pnl_pct") or 0.0)
    current_loss_idr = abs(min(pnl_pct, 0.0) * equity)
    hours_left = _hours_until_midnight_wib()
    avg_trades_per_hour = metrics["total_trades"] / max((time.time() - _bot_start_time) / 3600.0, 0.5)
    trades_possible = avg_trades_per_hour * hours_left
    ev_per_trade = float(metrics["ev_per_trade"])
    if ev_per_trade > 0 and current_loss_idr > 0:
        trades_to_recover = current_loss_idr / ev_per_trade
    elif ev_per_trade <= 0:
        trades_to_recover = float("inf")
    else:
        trades_to_recover = 0.0

    if ev_per_trade <= 0 and metrics["total_trades"] >= 3:
        action = "TIGHTEN_FILTER"
        reason = f"EV/trade <= 0 after {metrics['total_trades']} trades"
        _set_conservative_mode("math_review_ev_negative")
    elif trades_to_recover > trades_possible * 1.5:
        action = "HARD_STOP"
        reason = f"Recovery too far: need {trades_to_recover:.1f}, possible {trades_possible:.1f}"
        _set_conservative_mode("math_review_recovery_impossible")
    elif trades_to_recover > trades_possible:
        action = "DEFENSIVE"
        reason = f"Recovery tight: need {trades_to_recover:.1f}, possible {trades_possible:.1f}"
    elif metrics["win_rate"] >= 0.60 and ev_per_trade > 0:
        action = "CONTINUE_OPTIMAL"
        reason = f"WR={metrics['win_rate']:.0%}, EV/trade=Rp{ev_per_trade:,.0f}"
        if not bool(_daily_guard_state.get("hard_stopped")) and _api_fail_streak == 0 and _control_plane_healthy:
            _set_normal_mode("math_review_optimal")
    else:
        action = "CONTINUE"
        reason = f"WR={metrics['win_rate']:.0%}, EV/trade=Rp{ev_per_trade:,.0f}"

    report = (
        f"📊 30min Math Review\n"
        f"PnL: {pnl_pct:+.2%} | Trades: {metrics['total_trades']} ({metrics['wins']}W/{metrics['losses']}L)\n"
        f"WR: {metrics['win_rate']:.0%} | PF: {metrics['profit_factor']:.2f} | EV/trade: Rp{ev_per_trade:+,.0f}\n"
        f"Hours left: {hours_left:.1f} | Trades possible: {trades_possible:.1f}\n"
        f"Action: {action}\n"
        f"Reason: {reason}"
    )
    _telegram_send(report)
    _append_runtime_event(
        "math_review",
        {
            "action": action,
            "reason": reason,
            "metrics": metrics,
            "hours_left": round(hours_left, 2),
            "trades_possible": round(trades_possible, 2),
        },
    )
    print(f"[KIBOT][MATH_REVIEW] action={action} reason={reason}", flush=True)
    return {"action": action, "reason": reason, "metrics": metrics}


def _math_review_loop() -> None:
    global _last_math_review_at, _math_review_last_action, _math_review_last_reason
    while not _shutdown_event.is_set():
        try:
            now = time.time()
            if (now - _last_math_review_at) >= 1800.0:
                _last_math_review_at = now
                result = _run_math_review()
                _math_review_last_action = str(result.get("action") or "UNKNOWN")
                _math_review_last_reason = str(result.get("reason") or "")
        except Exception as error:
            print(f"[KIBOT][MATH_REVIEW][ERROR] {error}", flush=True)
        _shutdown_event.wait(60.0)


def _apply_ai_recommendation(recommendation: Dict[str, Any]) -> None:
    if not isinstance(recommendation, dict):
        return
    for pair in recommendation.get("pairs_to_cooldown", []):
        if isinstance(pair, str) and pair.strip():
            _cooldown_pair(pair, reason="ai_batch_review", minutes=60)
    mode = str(recommendation.get("mode_recommendation") or "no_change")
    if mode == "CONSERVATIVE":
        _set_conservative_mode("ai_batch_review")
    elif mode == "NORMAL" and not bool(_daily_guard_state.get("hard_stopped")) and _api_fail_streak == 0 and _control_plane_healthy:
        _set_normal_mode("ai_batch_review")
    _append_runtime_event("ai_batch_review", recommendation)


def _run_ai_batch_review() -> None:
    summary = _build_performance_summary()
    prompt = (
        "Kamu adalah risk analyst untuk autonomous crypto trading bot.\n"
        "Filosofi: survival first, compounding gradual.\n\n"
        f"Data performa 6 jam terakhir:\n{json.dumps(summary, ensure_ascii=False, indent=2)}\n\n"
        "Berikan rekomendasi JSON dengan keys: "
        "\"pairs_to_cooldown\", \"mode_recommendation\", \"reasoning\"."
    )
    try:
        text, provider = _call_ai_router(
            task="batch_review",
            system_prompt="Jawab JSON singkat saja.",
            user_prompt=prompt,
            model_hint=POST_MORTEM_MODEL,
            timeout_sec=min(POST_MORTEM_TIMEOUT_SEC, 20.0),
        )
        if not text:
            return
        parsed = _parse_json_candidate(text)
        if isinstance(parsed, dict) and parsed:
            _apply_ai_recommendation(parsed)
            print(f"[KIBOT][AI_REVIEW] provider={provider} applied={json.dumps(parsed, ensure_ascii=False)[:240]}", flush=True)
    except Exception as error:
        print(f"[KIBOT][AI_REVIEW][WARN] failed reason={error}", flush=True)


def _ai_batch_review_loop() -> None:
    while not _shutdown_event.is_set():
        try:
            _run_ai_batch_review()
        except Exception as error:
            print(f"[KIBOT][AI_REVIEW][ERROR] {error}", flush=True)
        _shutdown_event.wait(AI_BATCH_REVIEW_INTERVAL_SEC)


def _cooldown_pair(pair: str, *, reason: str, minutes: int, metadata: Dict[str, Any] | None = None) -> None:
    pair_key = pair.lower().strip()
    if not pair_key:
        return
    now_ts = time.time()
    until_ts = now_ts + max(60, minutes * 60)
    _pair_cooldown_state[pair_key] = {
        "until_epoch": until_ts,
        "reason": reason,
        "updated_at": datetime.now(timezone.utc).isoformat(),
        "metadata": metadata or {},
    }
    _save_pair_cooldown_state()
    _append_runtime_event(
        "pair_cooldown_set",
        {"pair": pair_key, "reason": reason, "minutes": minutes},
    )


def _pair_cooldown_active(pair: str) -> Tuple[bool, str]:
    pair_key = pair.lower().strip()
    if not pair_key:
        return False, ""
    state = _pair_cooldown_state.get(pair_key, {})
    until_ts = float(state.get("until_epoch") or 0.0)
    now_ts = time.time()
    if until_ts <= now_ts:
        if pair_key in _pair_cooldown_state:
            del _pair_cooldown_state[pair_key]
            _save_pair_cooldown_state()
        return False, ""
    return True, str(state.get("reason") or "cooldown_active")


def _load_daily_summary() -> Dict[str, Any]:
    today = datetime.now(timezone.utc).date().isoformat()
    data = _load_json_file(
        DAILY_SUMMARY_PATH,
        {
            "date": today,
            "ai_success": {},
            "ai_failure": {},
            "veto_metrics": {},
            "loss_blacklist_pairs": [],
            "recent_notes": [],
        },
    )
    if data.get("date") != today:
        data = {
            "date": today,
            "ai_success": {},
            "ai_failure": {},
            "veto_metrics": {},
            "loss_blacklist_pairs": [],
            "recent_notes": [],
        }
    return data


def _update_daily_summary(kind: str, detail: Dict[str, Any]) -> None:
    if not DAILY_SUMMARY_ENABLED:
        return
    summary = _load_daily_summary()
    if kind == "ai_success":
        provider = str(detail.get("provider") or "")
        if provider:
            summary["ai_success"][provider] = int(summary["ai_success"].get(provider) or 0) + 1
    elif kind == "ai_failure":
        provider = str(detail.get("provider") or "")
        if provider:
            summary["ai_failure"][provider] = int(summary["ai_failure"].get(provider) or 0) + 1
    elif kind == "veto_metric":
        name = str(detail.get("name") or "")
        if name:
            summary["veto_metrics"][name] = int(summary["veto_metrics"].get(name) or 0) + 1
    elif kind == "loss_blacklist":
        pair = str(detail.get("pair") or "")
        if pair and pair not in summary["loss_blacklist_pairs"]:
            summary["loss_blacklist_pairs"].append(pair)
    note_line = {
        "at": datetime.now(timezone.utc).isoformat(),
        "kind": kind,
        "detail": detail,
    }
    recent_notes = list(summary.get("recent_notes") or [])
    recent_notes.append(note_line)
    summary["recent_notes"] = recent_notes[-25:]
    _write_json_file(DAILY_SUMMARY_PATH, summary)


def _append_runtime_event(kind: str, detail: Dict[str, Any]) -> None:
    now_iso = datetime.now(timezone.utc).isoformat()
    _recent_runtime_events.append(
        {
            "at": now_iso,
            "kind": kind,
            "detail": detail,
        }
    )
    if len(_recent_runtime_events) > 40:
        del _recent_runtime_events[:-40]


def _heartbeat_loop() -> None:
    interval = max(0.05, MANAGER_HEARTBEAT_INTERVAL_SEC)
    while not _shutdown_event.is_set():
        try:
            _emit_trinity_heartbeat()
            _append_runtime_event("trinity_heartbeat_emit", {"sender": "kibot"})
        except Exception as error:
            print(f"[KIBOT][HEARTBEAT][WARN] emit failed reason={error}", flush=True)
        if _shutdown_event.wait(timeout=interval):
            break


def _write_runtime_note(*, force: bool = False) -> None:
    global _last_runtime_note_write_at
    now_ts = time.time()
    if not force and (now_ts - _last_runtime_note_write_at) < max(5, RUNTIME_NOTE_MIN_INTERVAL_SEC):
        return
    _last_runtime_note_write_at = now_ts
    note = {
        "updated_at": datetime.now(timezone.utc).isoformat(),
        "service": "kibot_manager",
        "host_bind": f"{UDP_BIND_HOST}:{UDP_BIND_PORT}",
        "kidax_target": f"{KIDAX_UDP_HOST}:{KIDAX_UDP_PORT}" if KIDAX_UDP_HOST else "",
        "kinance_target": f"{KINANCE_UDP_HOST}:{KINANCE_UDP_PORT}" if KINANCE_UDP_HOST else "",
        "system_state": str(_gate_state.get("entry_state") or "HEALTHY"),
        "trading_mode": str(_gate_state.get("mode") or "CONSERVATIVE"),
        "api_fail_streak": _api_fail_streak,
        "control_plane_healthy": _control_plane_healthy,
        "daily_hard_stop": bool(_daily_guard_state.get("hard_stopped") or _gate_state.get("daily_hard_stop")),
        "daily_pnl_pct": _daily_guard_state.get("daily_pnl_pct"),
        "daily_hard_stop_reset_at": _gate_state.get("daily_hard_stop_reset_at") or _daily_guard_state.get("reset_at"),
        "ai_router_enabled": AI_ROUTER_ENABLED,
        "ai_provider_order": _iter_ai_provider_order(),
        "ai_provider_last_status": dict(_ai_provider_last_status),
        "provider_runtime_state": _provider_runtime_state,
        "tracked_active_positions": sorted(_active_positions_cache.keys()),
        "pair_memory_preview": {
            pair_id: {
                "trade_count": int(memory.get("trade_count") or 0),
                "win_rate_now": round(_get_pair_win_rate_now(pair_id), 3),
                "avg_slippage_pct": round(_get_pair_avg_slippage(pair_id, fallback=0.0), 4),
                "cooldown": _is_pair_on_cooldown(pair_id),
                "fake_pump_count": int(memory.get("fake_pump_count") or 0),
            }
            for pair_id, memory in list(_pair_memory.items())[:10]
        },
        "pair_cooldowns": _pair_cooldown_state,
        "veto_metrics": _veto_metrics,
        "sector_count": len(_last_sector_map),
        "sector_preview": {key: value[:5] for key, value in list(_last_sector_map.items())[:5]},
        "recent_events": list(_recent_runtime_events[-15:]),
    }
    try:
        _write_json_file(RUNTIME_NOTE_PATH, note)
    except Exception as error:
        print(f"[KIBOT][NOTE][WARN] write failed reason={error}", flush=True)


def _classify_provider_failure(message: str) -> Tuple[int, str]:
    raw = (message or "").lower()
    if any(token in raw for token in ["429", "rate", "quota", "too many requests"]):
        return AI_PROVIDER_RATE_LIMIT_COOLDOWN_SEC, "rate_limited"
    if any(token in raw for token in ["timeout", "temporarily", "connection", "network", "bad gateway"]):
        return AI_PROVIDER_NETWORK_COOLDOWN_SEC, "transient_network"
    if "empty_response" in raw:
        return AI_PROVIDER_EMPTY_COOLDOWN_SEC, "empty_response"
    return AI_PROVIDER_DEFAULT_COOLDOWN_SEC, "generic_failure"


def _provider_rank(provider: str) -> Tuple[int, float]:
    state = _provider_runtime_state.get(provider, {})
    success = int(state.get("success_count") or 0)
    failure = int(state.get("failure_count") or 0)
    last_success = float(state.get("last_success_epoch") or 0.0)
    return (success - (failure * 2), last_success)


def _iter_ai_provider_order() -> List[str]:
    configured = [provider for provider in AI_PROVIDER_ORDER if provider]
    return sorted(
        configured,
        key=lambda provider: (
            -_provider_rank(provider)[0],
            -_provider_rank(provider)[1],
            configured.index(provider),
        ),
    )


def _provider_is_available(provider: str, now_ts: float) -> Tuple[bool, str]:
    state = _provider_runtime_state.get(provider, {})
    cooldown_until = float(state.get("cooldown_until_epoch") or 0.0)
    if cooldown_until > now_ts:
        return False, str(state.get("reason") or "cooldown_active")
    return True, ""


def _remember_provider_success(provider: str, task: str) -> None:
    now_ts = time.time()
    state = dict(_provider_runtime_state.get(provider, {}))
    state.update(
        {
            "task": task,
            "last_success_epoch": now_ts,
            "cooldown_until_epoch": 0,
            "reason": "",
            "success_count": int(state.get("success_count") or 0) + 1,
        }
    )
    _provider_runtime_state[provider] = state
    _write_json_file(PROVIDER_STATE_PATH, _provider_runtime_state)
    _update_daily_summary("ai_success", {"provider": provider, "task": task})


def _remember_provider_failure(provider: str, task: str, error_message: str) -> str:
    now_ts = time.time()
    cooldown_sec, reason = _classify_provider_failure(error_message)
    state = dict(_provider_runtime_state.get(provider, {}))
    state.update(
        {
            "task": task,
            "last_failure_epoch": now_ts,
            "cooldown_until_epoch": now_ts + max(30, cooldown_sec),
            "reason": reason,
            "last_error": error_message[:320],
            "failure_count": int(state.get("failure_count") or 0) + 1,
        }
    )
    _provider_runtime_state[provider] = state
    _write_json_file(PROVIDER_STATE_PATH, _provider_runtime_state)
    _update_daily_summary("ai_failure", {"provider": provider, "task": task, "reason": reason})
    return reason


def _parse_json_candidate(text: str) -> Any:
    raw = (text or "").strip()
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except Exception:
        pass
    fenced = re.search(r"\{[\s\S]*\}", raw)
    if fenced:
        try:
            return json.loads(fenced.group(0))
        except Exception:
            return {}
    return {}


def _extract_assistant_text(payload: Any) -> str:
    if isinstance(payload, str):
        return payload.strip()
    if not isinstance(payload, dict):
        return ""
    choices = payload.get("choices")
    if isinstance(choices, list) and choices:
        first = choices[0] if isinstance(choices[0], dict) else {}
        msg = first.get("message") if isinstance(first.get("message"), dict) else {}
        content = msg.get("content")
        if isinstance(content, str):
            return content.strip()
        if isinstance(content, list):
            texts: List[str] = []
            for item in content:
                if isinstance(item, dict):
                    text = item.get("text")
                    if isinstance(text, str) and text.strip():
                        texts.append(text.strip())
            if texts:
                return "\n".join(texts).strip()
    message = payload.get("message")
    if isinstance(message, dict):
        content = message.get("content")
        if isinstance(content, list) and content:
            first = content[0] if isinstance(content[0], dict) else {}
            text = first.get("text")
            if isinstance(text, str):
                return text.strip()
        if isinstance(content, str):
            return content.strip()
    candidates = payload.get("candidates")
    if isinstance(candidates, list) and candidates:
        first = candidates[0] if isinstance(candidates[0], dict) else {}
        content = first.get("content") if isinstance(first.get("content"), dict) else {}
        parts = content.get("parts")
        if isinstance(parts, list) and parts:
            first_part = parts[0] if isinstance(parts[0], dict) else {}
            text = first_part.get("text")
            if isinstance(text, str):
                return text.strip()
    return ""


def _provider_has_credentials(provider: str) -> bool:
    p = provider.lower().strip()
    if p == "groq":
        return bool(GROQ_API_KEY)
    if p == "gemini":
        return bool(GEMINI_API_KEY)
    if p == "openrouter":
        return bool(OPENROUTER_API_KEY)
    if p == "cohere":
        return bool(COHERE_API_KEY)
    if p == "blackbox":
        return bool(BLACKBOX_API_KEY)
    return False


def _call_openai_compatible(
    *,
    provider: str,
    api_url: str,
    api_key: str,
    model: str,
    system_prompt: str,
    user_prompt: str,
    timeout_sec: float,
) -> str:
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
    }
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": 0.1,
    }
    try:
        response = requests.post(api_url, headers=headers, json=payload, timeout=timeout_sec)
    except (requests.exceptions.ConnectionError, requests.exceptions.Timeout) as e:
        raise RuntimeError(f"{provider} network error: {type(e).__name__}")
    if response.status_code >= 300:
        raise RuntimeError(f"{provider} status={response.status_code} body={response.text[:240]}")
    return _extract_assistant_text(response.json() or {})


def _call_gemini(
    *,
    model: str,
    system_prompt: str,
    user_prompt: str,
    timeout_sec: float,
) -> str:
    if not GEMINI_API_KEY:
        raise RuntimeError("gemini missing key")
    base = GEMINI_API_URL.rstrip("/")
    # Use API key in header instead of URL to avoid leaking in logs
    url = f"{base}/models/{model}:generateContent"
    headers = {
        "Content-Type": "application/json",
        "x-goog-api-key": GEMINI_API_KEY,
    }
    payload = {
        "contents": [
            {
                "parts": [
                    {"text": f"System:\n{system_prompt}\n\nUser:\n{user_prompt}"},
                ],
            },
        ],
        "generationConfig": {
            "temperature": 0.1,
            "maxOutputTokens": 700,
            "responseMimeType": "text/plain",
        },
    }
    try:
        response = requests.post(url, headers=headers, json=payload, timeout=timeout_sec)
    except (requests.exceptions.ConnectionError, requests.exceptions.Timeout) as e:
        raise RuntimeError(f"gemini network error: {type(e).__name__}")
    if response.status_code >= 300:
        raise RuntimeError(f"gemini status={response.status_code} body={response.text[:240]}")
    return _extract_assistant_text(response.json() or {})


def _call_cohere(
    *,
    model: str,
    system_prompt: str,
    user_prompt: str,
    timeout_sec: float,
) -> str:
    if not COHERE_API_KEY:
        raise RuntimeError("cohere missing key")
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {COHERE_API_KEY}",
    }
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": 0.1,
    }
    try:
        response = requests.post(COHERE_API_URL, headers=headers, json=payload, timeout=timeout_sec)
    except (requests.exceptions.ConnectionError, requests.exceptions.Timeout) as e:
        raise RuntimeError(f"cohere network error: {type(e).__name__}")
    if response.status_code >= 300:
        raise RuntimeError(f"cohere status={response.status_code} body={response.text[:240]}")
    return _extract_assistant_text(response.json() or {})


def _call_provider(
    provider: str,
    *,
    system_prompt: str,
    user_prompt: str,
    model_hint: str = "",
    timeout_sec: float = AI_REQUEST_TIMEOUT_SEC,
) -> str:
    p = provider.lower().strip()
    if p == "groq":
        return _call_openai_compatible(
            provider="groq",
            api_url=GROQ_API_URL,
            api_key=GROQ_API_KEY,
            model=model_hint or GROQ_MODEL,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            timeout_sec=timeout_sec,
        )
    if p == "openrouter":
        return _call_openai_compatible(
            provider="openrouter",
            api_url=OPENROUTER_API_URL,
            api_key=OPENROUTER_API_KEY,
            model=model_hint or OPENROUTER_MODEL,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            timeout_sec=timeout_sec,
        )
    if p == "blackbox":
        return _call_openai_compatible(
            provider="blackbox",
            api_url=BLACKBOX_API_URL,
            api_key=BLACKBOX_API_KEY,
            model=model_hint or BLACKBOX_MODEL,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            timeout_sec=timeout_sec,
        )
    if p == "cohere":
        return _call_cohere(
            model=model_hint or COHERE_MODEL,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            timeout_sec=timeout_sec,
        )
    if p == "gemini":
        return _call_gemini(
            model=model_hint or GEMINI_MODEL,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            timeout_sec=timeout_sec,
        )
    raise RuntimeError(f"unsupported provider={provider}")


def _call_ai_router(
    *,
    task: str,
    system_prompt: str,
    user_prompt: str,
    model_hint: str = "",
    timeout_sec: float = AI_REQUEST_TIMEOUT_SEC,
) -> Tuple[str, str]:
    if not AI_ROUTER_ENABLED:
        return "", ""
    provider_errors: Dict[str, str] = {}
    now_ts = time.time()
    for provider in _iter_ai_provider_order():
        if not _provider_has_credentials(provider):
            provider_errors[provider] = "missing_credentials"
            continue
        available, reason = _provider_is_available(provider, now_ts)
        if not available:
            provider_errors[provider] = f"cooldown:{reason}"
            continue
        try:
            text = _call_provider(
                provider,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                model_hint=model_hint,
                timeout_sec=timeout_sec,
            ).strip()
            if not text:
                provider_errors[provider] = "empty_response"
                continue
            now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
            _ai_provider_last_status.update(
                {
                    "provider": provider,
                    "task": task,
                    "at_epoch_ms": now_ms,
                    "ok": True,
                },
            )
            _remember_provider_success(provider, task)
            _append_runtime_event(
                "ai_provider_success",
                {"provider": provider, "task": task},
            )
            _broadcast_udp(
                {
                    "msgType": "AI_PROVIDER_STATUS",
                    "senderBotId": "kibot",
                    "task": task,
                    "provider": provider,
                    "ok": True,
                    "sentAtEpochMs": now_ms,
                },
            )
            _write_runtime_note(force=True)
            return text, provider
        except Exception as error:
            error_text = str(error)
            reason = _remember_provider_failure(provider, task, error_text)
            provider_errors[provider] = f"{reason}:{error_text}"
            _append_runtime_event(
                "ai_provider_failure",
                {"provider": provider, "task": task, "reason": reason},
            )
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    _ai_provider_last_status.update(
        {
            "provider": "",
            "task": task,
            "at_epoch_ms": now_ms,
            "ok": False,
        },
    )
    _broadcast_udp(
        {
            "msgType": "AI_PROVIDER_STATUS",
            "senderBotId": "kibot",
            "task": task,
            "provider": "",
            "ok": False,
            "errors": provider_errors,
            "sentAtEpochMs": now_ms,
        },
    )
    _append_runtime_event(
        "ai_router_unavailable",
        {"task": task, "errors": provider_errors},
    )
    _write_runtime_note(force=True)
    return "", ""


def _headers() -> Dict[str, str]:
    return {
        "apikey": SUPABASE_KEY,
        "Authorization": f"Bearer {SUPABASE_KEY}",
        "Content-Type": "application/json",
    }


def _ensure_env() -> None:
    missing = []
    if not SUPABASE_URL:
        missing.append("SUPABASE_URL")
    if not SUPABASE_KEY:
        missing.append("SUPABASE_SERVICE_ROLE_KEY/SUPABASE_ANON_KEY")
    if not KIDAX_UDP_HOST:
        missing.append("KIDAX_UDP_HOST")
    if missing:
        raise RuntimeError(f"Missing env: {', '.join(missing)}")


def _broadcast_udp(payload: Dict[str, Any]) -> None:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    peers = []
    if KINANCE_UDP_HOST:
        peers.append((KINANCE_UDP_HOST, KINANCE_UDP_PORT))
    if KIDAX_UDP_HOST:
        peers.append((KIDAX_UDP_HOST, KIDAX_UDP_PORT))
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        for host, port in peers:
            sock.sendto(data, (host, port))
    finally:
        sock.close()
    print(
        f"[KIBOT][UDP_BROADCAST] msgType={payload.get('msgType')} pair={payload.get('pairId')} trace={payload.get('traceId')}",
        flush=True,
    )


def _emit_trinity_heartbeat() -> None:
    sent_at = int(time.time() * 1000)
    for sender_bot_id in ("kibot", "kidax", "kinance"):
        payload = {
            "kind": "trinity_state",
            "msgType": "HEARTBEAT",
            "senderBotId": sender_bot_id,
            "sentAtEpochMs": sent_at,
            "activePair": "",
            "safeModeArmed": False,
        }
        _broadcast_udp(payload)


def _coingecko_track_record_score(pair: str) -> float:
    symbol = pair.split("_", 1)[0].lower()
    try:
        response = requests.get(
            f"{COINGECKO_BASE}/search",
            params={"query": symbol},
            timeout=TIMEOUT,
        )
        if response.status_code != 200:
            return 0.62
        data = response.json() or {}
        coins = data.get("coins") or []
        base_score = 0.8 if coins else 0.58
        adaptive_penalty = _get_adaptive_score_penalty(pair)
        return max(0.0, base_score - adaptive_penalty)
    except Exception as error:
        print(f"[KIBOT][WARN] CoinGecko API error pair={pair} reason={error}", flush=True)
        return 0.60


def _get_adaptive_score_penalty(pair: str) -> float:
    memory = _pair_memory.get(pair.lower().strip(), {})
    trade_count = int(memory.get("trade_count") or 0)
    if trade_count < 3:
        return 0.0
    win_rate = float(memory.get("win_rate_7d") or 0.5)
    avg_pnl = float(memory.get("avg_pnl") or 0.0)
    penalty = 0.0
    if win_rate < 0.30:
        penalty += 0.20
    elif win_rate < 0.40:
        penalty += 0.10
    elif win_rate < 0.50:
        penalty += 0.05
    if avg_pnl < -0.008:
        penalty += 0.10
    if _learning_enabled and _learning_engine is not None:
        penalty += float(_learning_engine.score_penalty(pair))
    return min(penalty, 0.30)


def _fetch_coingecko_trending() -> list[dict[str, Any]]:
    try:
        response = requests.get(
            f"{COINGECKO_BASE}/search/trending",
            timeout=TIMEOUT,
        )
        if response.status_code >= 300:
            return []
        body = response.json() or {}
        items = body.get("coins") or []
        out: list[dict[str, Any]] = []
        for item in items[:12]:
            coin = (item or {}).get("item") or {}
            symbol = str(coin.get("symbol") or "").lower().strip()
            if not symbol:
                continue
            out.append(
                {
                    "symbol": symbol,
                    "name": str(coin.get("name") or symbol),
                    "rank": int(coin.get("market_cap_rank") or 0),
                    "score": int(item.get("score") or 0),
                }
            )
        return out
    except Exception as error:
        print(f"[KIBOT][COINGECKO][ERROR] trending fetch failed reason={error}", flush=True)
        return []


def _refresh_coingecko_trending_cache() -> None:
    global _coingecko_trending_cache
    coins = _fetch_coingecko_trending()
    if not coins:
        return
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    _coingecko_trending_cache = {"coins": coins, "fetched_at_epoch_ms": now_ms}
    preview = ",".join(c["symbol"] for c in coins[:5])
    print(f"[KIBOT][COINGECKO][TRENDING] count={len(coins)} top={preview}", flush=True)


def _get_coingecko_trending_cache() -> list[dict[str, Any]]:
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    last_ms = int(_coingecko_trending_cache.get("fetched_at_epoch_ms") or 0)
    if (now_ms - last_ms) > max(60_000, COINGECKO_TRENDING_INTERVAL_SEC * 1000):
        _refresh_coingecko_trending_cache()
    return list(_coingecko_trending_cache.get("coins") or [])


def _estimate_exit_viability(expected_move_pct: float, slippage_pct: float) -> Dict[str, float]:
    total_cost_pct = max(0.0, slippage_pct) + max(0.0, TAKER_FEE_PCT)
    net_profit_pct = expected_move_pct - total_cost_pct
    return {
        "expected_move_pct": round(expected_move_pct, 4),
        "slippage_pct": round(slippage_pct, 4),
        "taker_fee_pct": round(TAKER_FEE_PCT, 4),
        "total_cost_pct": round(total_cost_pct, 4),
        "net_profit_pct": round(net_profit_pct, 4),
    }


def _effective_fee_pct() -> float:
    return (
        INDODAX_LIMIT_FILL_RATE * INDODAX_ALL_IN_MAKER_FEE_PCT
        + (1.0 - INDODAX_LIMIT_FILL_RATE) * INDODAX_ALL_IN_TAKER_FEE_PCT
    )


def _get_total_equity_estimate() -> float | None:
    current_equity = _daily_guard_state.get("current_equity")
    if isinstance(current_equity, (int, float)) and float(current_equity) > 0:
        return float(current_equity)
    for payload_key in ("totalValueIdr", "portfolioValueIdr", "total_value_idr", "balanceIdr", "balance_idr"):
        value = _daily_guard_state.get(payload_key)
        if isinstance(value, (int, float)) and float(value) > 0:
            return float(value)
    try:
        response = requests.get("http://127.0.0.1:8787/api/state", timeout=3)
        response.raise_for_status()
        data = response.json() or {}
    except Exception:
        return None
    for field in ("totalEquityIdr", "total_equity_idr", "portfolioValueIdr", "portfolio_value_idr", "balanceIdr", "balance_idr", "totalValueIdr", "total_value_idr"):
        value = data.get(field)
        if isinstance(value, (int, float)) and float(value) > 0:
            return float(value)
        try:
            cleaned = float(str(value).replace(",", "").strip())
            if cleaned > 0:
                return cleaned
        except Exception:
            continue
    return None


def _check_minimum_capital() -> bool:
    equity = _get_total_equity_estimate()
    if equity is None:
        print("[KIBOT][CAPITAL][WARN] unable to read equity; allowing entry fail-open", flush=True)
        return True
    if equity < MINIMUM_VIABLE_CAPITAL_IDR:
        print(
            f"[KIBOT][CAPITAL] equity Rp{equity:,.0f} < minimum Rp{MINIMUM_VIABLE_CAPITAL_IDR:,.0f}; entry suspended",
            flush=True,
        )
        return False
    return True


def _capital_is_sufficient() -> bool:
    equity = _get_total_equity_estimate()
    return equity is not None and equity >= MINIMUM_VIABLE_CAPITAL_IDR


def _maybe_auto_promote_trading_mode() -> None:
    global _capital_sufficient_since_at
    if bool(_daily_guard_state.get("hard_stopped")):
        _capital_sufficient_since_at = 0.0
        _set_conservative_mode("hard stop active")
        return
    if not _control_plane_healthy or _api_fail_streak > 0:
        _capital_sufficient_since_at = 0.0
        _set_conservative_mode("control plane unhealthy")
        return
    if not _capital_is_sufficient():
        _capital_sufficient_since_at = 0.0
        _set_conservative_mode("capital insufficient")
        return
    if _capital_sufficient_since_at <= 0.0:
        _capital_sufficient_since_at = time.time()
    if (time.time() - _capital_sufficient_since_at) < _normal_mode_promotion_grace_sec:
        _set_conservative_mode("capital sufficient grace period")
        return
    if _is_survival_mode():
        _set_conservative_mode("survival mode active")
        return
    _set_normal_mode("capital sufficient and healthy")


def _is_survival_mode() -> bool:
    if not SURVIVAL_MODE:
        return False
    equity = _get_total_equity_estimate()
    if equity is None:
        return True
    return equity < SURVIVAL_MODE_EQUITY_THRESHOLD_IDR


def _apply_survival_filters(pair_id: str, budget_idr: float, spread_pct: float = 0.0, slippage_pct: float = 0.0) -> tuple[bool, str]:
    if not _is_survival_mode():
        return True, "normal_mode"
    pair_key = str(pair_id or "").lower().strip()
    pair_cfg = _get_pair_config(pair_key)
    allowed_tiers = set(_capital_bucket_tiers())
    if pair_key not in SURVIVAL_ALLOWED_PAIRS and pair_cfg.get("tier") not in allowed_tiers:
        return False, f"survival_mode: {pair_key} not allowed"
    max_size_idr = float(pair_cfg.get("max_size_idr") or MAXIMUM_POSITION_SIZE_IDR)
    max_size_idr *= _capital_risk_multiplier()
    min_target_profit_pct = float(pair_cfg.get("min_target_profit_pct") or SURVIVAL_TARGET_PROFIT_PCT)
    max_spread_pct = float(pair_cfg.get("max_spread_pct") or SURVIVAL_MAX_SPREAD_PCT)
    max_slippage_pct = float(pair_cfg.get("max_slippage_pct") or SURVIVAL_MAX_SLIPPAGE_PCT)
    if budget_idr < MINIMUM_POSITION_SIZE_IDR:
        return False, f"survival_mode: budget {budget_idr:.0f} below min position {MINIMUM_POSITION_SIZE_IDR:.0f}"
    if budget_idr > min(MAXIMUM_POSITION_SIZE_IDR, max_size_idr):
        return False, f"survival_mode: budget {budget_idr:.0f} above max position {min(MAXIMUM_POSITION_SIZE_IDR, max_size_idr):.0f}"
    if spread_pct > max_spread_pct:
        return False, f"survival_mode: spread {spread_pct:.3%} too wide"
    if slippage_pct > max_slippage_pct:
        return False, f"survival_mode: slippage {slippage_pct:.3%} too high"
    if min_target_profit_pct <= 0:
        return False, f"survival_mode: invalid target profit config for {pair_key}"
    return True, "ok"


def _simulate_what_if(
    *,
    pair_id: str,
    entry_price: float,
    budget_idr: float,
    spread_pct: float,
    slippage_pct: float,
    trailing_stop_pct: float = 0.05,
    target_profit_pct: float = 0.018,
) -> Dict[str, Any]:
    historical_slippage = _get_pair_avg_slippage(pair_id, fallback=slippage_pct)
    historical_win_rate = _get_pair_win_rate_now(pair_id)
    effective_slippage = max(float(slippage_pct), float(historical_slippage))
    eff_fee_pct = _effective_fee_pct()
    round_trip_cost_pct = (max(0.0, spread_pct) / 2.0) + effective_slippage + (eff_fee_pct * 2.0)
    breakeven_move_pct = round_trip_cost_pct
    expected_net_pct = target_profit_pct - round_trip_cost_pct
    max_loss_pct = trailing_stop_pct + round_trip_cost_pct
    max_loss_idr = budget_idr * max_loss_pct
    reward_idr = budget_idr * expected_net_pct
    risk_reward_ratio = (reward_idr / max_loss_idr) if max_loss_idr > 0 else 0.0
    expected_value = (historical_win_rate * reward_idr) - ((1.0 - historical_win_rate) * max_loss_idr)
    if expected_net_pct <= 0:
        recommendation = "SKIP"
    elif risk_reward_ratio < 1.0:
        recommendation = "SKIP"
    elif expected_value <= 0:
        recommendation = "SKIP"
    elif risk_reward_ratio < 1.5 or historical_win_rate < 0.45:
        recommendation = "REDUCE_SIZE"
    else:
        recommendation = "ENTER"
    return {
        "pair_id": pair_id,
        "entry_price": entry_price,
        "budget_idr": budget_idr,
        "expected_net_pct": round(expected_net_pct, 4),
        "breakeven_move_pct": round(breakeven_move_pct, 4),
        "fee_round_trip_pct": round(eff_fee_pct * 2.0, 4),
        "max_loss_idr": round(max_loss_idr, 0),
        "win_probability": round(historical_win_rate, 3),
        "risk_reward_ratio": round(risk_reward_ratio, 2),
        "recommendation": recommendation,
        "historical_slippage_pct": round(historical_slippage, 4),
        "effective_slippage_pct": round(effective_slippage, 4),
    }


def _current_daily_loss_limit_pct() -> float:
    return 0.01 if _is_survival_mode() else abs(float(DAILY_LOSS_LIMIT_PCT))


def _upsert_trade_history(entry: Dict[str, Any]) -> None:
    headers = _headers()
    headers["Prefer"] = "return=minimal"
    primary_url = f"{SUPABASE_URL}/rest/v1/trade_history"
    try:
        response = requests.post(primary_url, headers=headers, json=entry, timeout=CONTROL_PLANE_TIMEOUT_SEC)
        if response.status_code < 300:
            _record_control_plane_success()
            return
        if response.status_code != 404:
            response.raise_for_status()
        _record_control_plane_success()
    except Exception as error:
        _record_control_plane_failure(f"trade_history_upsert:{error}")
    # Fallback when table trade_history is absent on this project.
    fallback_url = f"{SUPABASE_URL}/rest/v1/logs"
    fallback_payload = {
        "bot_id": "kibot",
        "device_id": "kibot-manager",
        "term": 0,
        "level": "INFO",
        "category": "BOOK_ENTRY",
        "message": json.dumps(entry, ensure_ascii=False),
        "metadata": {"source": "kibot_manager", "fallback_from": "trade_history"},
        "created_at": datetime.now(timezone.utc).isoformat(),
    }
    try:
        fallback_resp = requests.post(fallback_url, headers=headers, json=fallback_payload, timeout=CONTROL_PLANE_TIMEOUT_SEC)
        fallback_resp.raise_for_status()
        _record_control_plane_success()
    except Exception as error:
        _record_control_plane_failure(f"trade_history_fallback:{error}")
        raise


def _book_entry_from_execution(msg: Dict[str, Any]) -> None:
    now_iso = datetime.now(timezone.utc).isoformat()
    gross = float(msg.get("gross_pnl_idr") or 0.0)
    est_cost = float(msg.get("estimated_cost_idr") or 0.0)
    net = gross - est_cost
    pair_id = str(msg.get("pair") or msg.get("pairId") or "unknown").lower().strip()
    pnl_pct = float(msg.get("pnl_pct") or msg.get("pnlPct") or 0.0)
    slippage_pct = float(msg.get("slippage_pct") or 0.0)
    spread_pct = float(msg.get("spread_pct") or 0.0)
    actual_latency_ms = float(msg.get("latency_ms") or 0.0)
    fake_pump = bool(msg.get("fake_pump") or False)
    _update_pair_memory(
        pair_id,
        pnl_pct=pnl_pct,
        slippage_pct=slippage_pct,
        spread_pct=spread_pct,
        actual_latency_ms=actual_latency_ms,
        fake_pump=fake_pump,
    )
    if _learning_enabled and _learning_engine is not None:
        used_limit_order = str(msg.get("order_type") or msg.get("orderType") or "limit").lower() == "limit"
        if used_limit_order:
            _metric_inc("limit_orders_today")
        else:
            _metric_inc("market_orders_today")
            _metric_add("fee_bleed_est_idr", abs(net) * 0.0 + max(300.0, abs(gross) * 0.006))
        _learning_engine.record_trade(pair_id, pnl_pct, used_limit_order=used_limit_order)
    try:
        _record_trade_result(pair_id, gross_pnl_pct=pnl_pct, entry_time=float(msg.get("entry_timestamp") or msg.get("timestamp") or time.time()))
    except Exception as error:
        print(f"[KIBOT][MATH_REVIEW][WARN] trade record failed pair={pair_id} reason={error}", flush=True)
    print(
        f"[KIBOT][LEARNING] pair_memory updated pair={pair_id} pnl_pct={pnl_pct:.4f} slippage_pct={slippage_pct:.4f}",
        flush=True,
    )
    try:
        _upsert_trade_history(
            {
                "pair_id": msg.get("pair") or msg.get("pairId") or "unknown",
                "status": "BOOK_ENTRY",
                "source_bot": "kibot",
                "message": json.dumps(
                    {
                        "trace_id": msg.get("traceId"),
                        "gross_pnl_idr": gross,
                        "estimated_cost_idr": est_cost,
                        "net_pnl_idr": net,
                        "mode": "TRINITY_V3",
                    },
                    ensure_ascii=False,
                ),
                "created_at": now_iso,
                "updated_at": now_iso,
            }
        )
        print(
            f"[KIBOT][LEDGER] BOOK_ENTRY pair={msg.get('pair')} net={net:.4f} trace={msg.get('traceId')}",
            flush=True,
        )
        _append_runtime_event(
            "book_entry",
            {"pair": pair_id, "net_pnl_idr": round(net, 4), "trace_id": msg.get("traceId")},
        )
        _write_runtime_note()
    except Exception as error:
        print(
            f"[KIBOT][LEDGER][WARN] upsert failed pair={msg.get('pair')} trace={msg.get('traceId')} reason={error}",
            flush=True,
        )
    if POST_MORTEM_ENABLED and net < 0:
        _update_pair_memory(
            pair_id,
            pnl_pct=pnl_pct,
            slippage_pct=slippage_pct,
            spread_pct=spread_pct,
            fake_pump=True,
        )
        thread = threading.Thread(
            target=evaluate_foolish_trade,
            args=(
                {
                    "trace_id": msg.get("traceId"),
                    "pair": pair_id,
                    "gross_pnl_idr": gross,
                    "estimated_cost_idr": est_cost,
                    "net_pnl_idr": net,
                    "pnl_pct": float(msg.get("pnl_pct") or msg.get("pnlPct") or 0.0),
                    "slippage_pct": float(msg.get("slippage_pct") or 0.0),
                    "hold_seconds": float(msg.get("hold_seconds") or 0.0),
                    "closed_at": now_iso,
                },
            ),
            daemon=True,
            name="kibot-postmortem",
        )
        thread.start()


def evaluate_foolish_trade(trade_data: Dict[str, Any]) -> None:
    system_prompt = "Anda evaluator pasca-trade. Jawab JSON singkat: {\"mistake\":\"...\",\"action\":\"...\",\"tighten\":{...}}"
    user_prompt = json.dumps(trade_data, ensure_ascii=False)
    routed_text, provider = _call_ai_router(
        task="post_mortem",
        system_prompt=system_prompt,
        user_prompt=user_prompt,
        model_hint=POST_MORTEM_MODEL,
        timeout_sec=POST_MORTEM_TIMEOUT_SEC,
    )
    if routed_text:
        print(
            f"[KIBOT][POST_MORTEM] provider={provider} trace={trade_data.get('trace_id')} result={routed_text[:320]}",
            flush=True,
        )
        parsed = _parse_json_candidate(routed_text)
        pair = str(trade_data.get("pair") or "").lower().strip()
        net_pnl = float(trade_data.get("net_pnl_idr") or 0.0)
        pnl_pct = float(trade_data.get("pnl_pct") or 0.0)
        action_text = json.dumps(parsed, ensure_ascii=False).lower() if parsed else routed_text.lower()
        if POST_MORTEM_BLACKLIST_ENABLED and pair and (
            "blacklist" in action_text
            or "freeze" in action_text
            or net_pnl <= -abs(POST_MORTEM_BLACKLIST_NET_LOSS_IDR)
            or pnl_pct <= POST_MORTEM_BLACKLIST_PNL_PCT
        ):
            _cooldown_pair(
                pair,
                reason="post_mortem_loss_blacklist",
                minutes=POST_MORTEM_BLACKLIST_MINUTES,
                metadata={"trace_id": trade_data.get("trace_id"), "provider": provider, "net_pnl_idr": net_pnl},
            )
            _update_daily_summary("loss_blacklist", {"pair": pair, "provider": provider})
            _write_runtime_note(force=True)
        return
    if not POST_MORTEM_API_URL:
        print("[KIBOT][POST_MORTEM] skipped (router+legacy unavailable).", flush=True)
        return
    payload = {
        "model": POST_MORTEM_MODEL,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": 0.1,
    }
    headers = {"Content-Type": "application/json"}
    if POST_MORTEM_API_KEY:
        headers["Authorization"] = f"Bearer {POST_MORTEM_API_KEY}"
    try:
        response = requests.post(
            POST_MORTEM_API_URL,
            headers=headers,
            json=payload,
            timeout=POST_MORTEM_TIMEOUT_SEC,
        )
        if response.status_code >= 300:
            print(
                f"[KIBOT][POST_MORTEM][WARN] status={response.status_code} body={response.text[:240]}",
                flush=True,
            )
            return
        result = response.json()
        print(
            f"[KIBOT][POST_MORTEM] evaluated trace={trade_data.get('trace_id')} result={json.dumps(result, ensure_ascii=False)[:320]}",
            flush=True,
        )
    except Exception as error:
        print(f"[KIBOT][POST_MORTEM][ERROR] trace={trade_data.get('trace_id')} reason={error}", flush=True)


def force_evaluate_recent_loss() -> None:
    if not POST_MORTEM_ENABLED:
        return
    if not SUPABASE_URL or not SUPABASE_KEY:
        return
    try:
        url = f"{SUPABASE_URL}/rest/v1/trade_history"
        headers = _headers()
        params = {
            "select": "message,created_at,pair_id",
            "status": "eq.BOOK_ENTRY",
            "order": "created_at.desc",
            "limit": "20",
        }
        response = requests.get(url, headers=headers, params=params, timeout=TIMEOUT)
        if response.status_code >= 300:
            return
        rows = response.json() or []
        for row in rows:
            message = row.get("message")
            if not isinstance(message, str) or not message.strip():
                continue
            try:
                payload = json.loads(message)
            except Exception:
                continue
            net = float(payload.get("net_pnl_idr") or 0.0)
            if net >= 0:
                continue
            evaluate_foolish_trade(
                {
                    "trace_id": payload.get("trace_id") or f"forced-{int(time.time())}",
                    "pair": row.get("pair_id") or "unknown",
                    "gross_pnl_idr": float(payload.get("gross_pnl_idr") or 0.0),
                    "estimated_cost_idr": float(payload.get("estimated_cost_idr") or 0.0),
                    "net_pnl_idr": net,
                    "slippage_pct": float(payload.get("slippage_pct") or 0.0),
                    "hold_seconds": float(payload.get("hold_seconds") or 0.0),
                    "closed_at": row.get("created_at"),
                    "trigger": "force_recent_loss_eval",
                }
            )
            print(f"[KIBOT][POST_MORTEM][FORCE] recent loss evaluated pair={row.get('pair_id')} net={net:.4f}", flush=True)
            return
    except Exception as error:
        print(f"[KIBOT][POST_MORTEM][FORCE][ERROR] {error}", flush=True)


def _get_dynamic_fomo_guard(price_idr: float) -> float:
    """
    Micro-cap (< Rp50): Boleh pump sampai 35% karena masih early
    Mid-cap (Rp50-500): Standard 22%
    Big-cap (> Rp500): Ketat 15%
    """
    if price_idr < 50.0:
        return 35.0
    elif price_idr < 500.0:
        return 22.0
    else:
        return 15.0


def _on_kinance_heartbeat_received():
    """Called when heartbeat UDP packet received from Kinance"""
    global _last_kinance_heartbeat_at, _kinance_healthy
    _last_kinance_heartbeat_at = time.time()
    if not _kinance_healthy:
        print("[KIBOT][RECOVERY] KINANCE heartbeat restored!", flush=True)
    _kinance_healthy = True


def _check_kinance_health() -> bool:
    """Returns True if Kinance is healthy (heartbeat within timeout)"""
    global _kinance_healthy
    now = time.time()
    if _last_kinance_heartbeat_at == 0.0:
        return True  # First run, assume healthy
    
    if (now - _last_kinance_heartbeat_at) > KINANCE_HEARTBEAT_TIMEOUT_SEC:
        if _kinance_healthy:
            print(f"[KIBOT][CRITICAL] KINANCE HEARTBEAT LOST! Last seen {now - _last_kinance_heartbeat_at:.1f}s ago", flush=True)
            _kinance_healthy = False
        return False
    return True


def _process_signal(msg: Dict[str, Any]) -> None:
    msg_type = str(msg.get("msgType") or "").upper()

    try:
        _check_daily_loss_limit()
        if _is_hard_stop_active():
            _metric_inc("entries_blocked_hard_stop")
            print(
                f"[KIBOT][BLOCK] Blocking {msg_type} - daily hard stop active",
                flush=True,
            )
            return
    except Exception as error:
        _metric_inc("entries_blocked_hard_stop")
        print(f"[KIBOT][BLOCK] Blocking {msg_type} - hard stop guard failed reason={error}", flush=True)
        return

    if not _check_minimum_capital():
        print(f"[KIBOT][BLOCK] Blocking {msg_type} - minimum viable capital not met", flush=True)
        return
    
    # === HANDLE KINANCE HEARTBEAT ===
    if msg_type == "HEARTBEAT" and msg.get("source") == "kinance":
        _on_kinance_heartbeat_received()
        return

    if msg_type in EXIT_MSG_TYPES:
        pass
    elif msg_type in SAFE_ENTRY_MSG_TYPES and _entry_state_is_suspended():
        print(
            f"[KIBOT][BLOCK] Blocking {msg_type} - entry suspended state={_gate_state.get('entry_state')} reason={_gate_state.get('reason')}",
            flush=True,
        )
        return
    elif _entry_state_is_suspended():
        print(
            f"[KIBOT][BLOCK] Blocking {msg_type} - entry suspended state={_gate_state.get('entry_state')} reason={_gate_state.get('reason')}",
            flush=True,
        )
        return
    
    # === EARLY RETURN IF KINANCE DEAD ===
    if not _check_kinance_health():
        # Only allow EXIT signals when Kinance unhealthy
        if msg_type not in EXIT_MSG_TYPES:
            print(f"[KIBOT][BLOCK] Blocking {msg_type} - KINANCE unhealthy", flush=True)
            return
    
    if msg_type == "ACTIVE_POSITIONS":
        _process_active_positions(msg)
        return
    if msg_type == "ORDERBOOK_COLLAPSE":
        _process_orderbook_collapse(msg)
        return
    if msg_type == "EXECUTION_FILLED":
        _book_entry_from_execution(msg)
        return
    if msg_type not in (SAFE_ENTRY_MSG_TYPES | EXIT_MSG_TYPES):
        return
    # Relay original detector signal so KiDax can hold Kinance-side evidence for double-confirmation.
    _broadcast_udp(msg)
    print(
        f"[KIBOT][RELAY] msgType={msg_type} pair={msg.get('pair') or msg.get('pairId')} trace={msg.get('traceId')}",
        flush=True,
    )

    pair = str(msg.get("pair") or msg.get("pairId") or "")
    if not pair:
        print(f"[KIBOT][WARN] missing pair in msgType={msg_type}", flush=True)
        return

    if msg_type in SAFE_ENTRY_MSG_TYPES and _learning_enabled and _learning_engine is not None:
        allowed, reason = _learning_engine.should_entry(pair)
        if not allowed:
            _metric_inc("entries_blocked_learn_gate")
            print(f"[KIBOT][LEARN GATE] pair={pair} blocked reason={reason}", flush=True)
            _append_runtime_event(
                "learning_block",
                {"pair": pair, "reason": reason, "msg_type": msg_type},
            )
            return
    if msg_type in SAFE_ENTRY_MSG_TYPES:
        entry_price = float(msg.get("entryPrice") or msg.get("entry_price") or msg.get("price") or 0.0)
        budget_idr = float(msg.get("budgetIdr") or msg.get("budget_idr") or msg.get("quoteBudgetIdr") or 0.0)
        spread_pct = float(msg.get("spreadPct") or msg.get("spread_pct") or 0.0)
        slippage_pct = float(msg.get("slippagePct") or msg.get("slippage_pct") or 0.0)
        pair_cfg = _get_pair_config(pair)
        target_profit_pct = float(msg.get("targetProfitPct") or msg.get("target_profit_pct") or pair_cfg.get("min_target_profit_pct") or SURVIVAL_TARGET_PROFIT_PCT)
        capital_bucket = _capital_bucket_tiers()
        if pair_cfg.get("tier") == "D":
            target_profit_pct = max(target_profit_pct, 0.04)
        elif pair_cfg.get("tier") == "C":
            target_profit_pct = max(target_profit_pct, 0.025)
        if entry_price > 0.0 and budget_idr > 0.0:
            what_if = _simulate_what_if(
                pair_id=pair,
                entry_price=entry_price,
                budget_idr=budget_idr,
                spread_pct=spread_pct,
                slippage_pct=slippage_pct,
                trailing_stop_pct=float(msg.get("trailingStopPct") or 0.05),
                target_profit_pct=target_profit_pct,
            )
            _append_runtime_event("what_if", what_if)
            print(
                f"[KIBOT][WHATIF] pair={pair} tiers={','.join(capital_bucket)} ev={what_if['expected_net_pct']:.4f} rr={what_if['risk_reward_ratio']:.2f} rec={what_if['recommendation']}",
                flush=True,
            )
            if what_if["recommendation"] == "SKIP":
                _metric_inc("entries_blocked_whatif")
                _metric_inc("whatif_skips_today")
                print(f"[KIBOT][BLOCK] Blocking {msg_type} - what-if rejected", flush=True)
                return
            _metric_inc("whatif_enters_today")
        allowed, reason = _apply_survival_filters(
            pair_id=pair,
            budget_idr=float(msg.get("budgetIdr") or msg.get("budget_idr") or msg.get("quoteBudgetIdr") or 0.0),
            spread_pct=spread_pct,
            slippage_pct=slippage_pct,
        )
        if not allowed:
            print(f"[KIBOT][SURVIVAL] pair={pair} blocked reason={reason}", flush=True)
            return
    pair_on_cooldown, cooldown_reason = _pair_cooldown_active(pair)
    if pair_on_cooldown and msg_type not in EXIT_MSG_TYPES:
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        print(
            f"[KIBOT][VETO_REJECTED] pair={pair} reason=PAIR_COOLDOWN cooldown_reason={cooldown_reason}",
            flush=True,
        )
        veto = {
            "kind": "lead_lag_breakout",
            "msgType": "VETO_REJECTED",
            "traceId": str(msg.get("traceId") or f"trace-{now_ms}"),
            "senderBotId": "kibot",
            "pairId": pair,
            "trend": "UP",
            "detectedAtEpochMs": now_ms,
            "sentAtEpochMs": now_ms,
            "expiresAtEpochMs": now_ms + 3_000,
            "confidence": 0.35,
            "expectedNetPct": -0.01,
            "shortTermReturnPct": float(msg.get("shortTermReturnPct") or 0.0),
            "mediumTermReturnPct": float(msg.get("mediumTermReturnPct") or 0.0),
            "tradeActivityScore": 0.35,
            "forceRotation": False,
            "payload": {"reason": "PAIR_COOLDOWN", "cooldown_reason": cooldown_reason},
        }
        _veto_metrics["rejected"] += 1
        _update_daily_summary("veto_metric", {"name": "rejected"})
        _broadcast_udp(veto)
        _write_runtime_note()
        return
    trace_id = str(msg.get("traceId") or f"trace-{int(datetime.now(timezone.utc).timestamp() * 1000)}")
    payload = msg.get("payload", {}) if isinstance(msg.get("payload"), dict) else {}
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    sent_at_ms = int(msg.get("sentAtEpochMs") or now_ms)
    signal_age_ms = max(0, now_ms - sent_at_ms)
    if signal_age_ms > STALE_SIGNAL_ABORT_MS:
        print(
            f"[KIBOT][VETO_REJECTED] pair={pair} reason=STALE_SIGNAL age_ms={signal_age_ms}",
            flush=True,
        )
        veto = {
            "kind": "lead_lag_breakout",
            "msgType": "VETO_REJECTED",
            "traceId": trace_id,
            "senderBotId": "kibot",
            "pairId": pair,
            "trend": "UP",
            "detectedAtEpochMs": now_ms,
            "sentAtEpochMs": now_ms,
            "expiresAtEpochMs": now_ms + 3_000,
            "confidence": 0.50,
            "expectedNetPct": -0.01,
            "shortTermReturnPct": float(msg.get("shortTermReturnPct") or 0.0),
            "mediumTermReturnPct": float(msg.get("mediumTermReturnPct") or 0.0),
            "tradeActivityScore": 0.50,
            "forceRotation": False,
            "payload": {"reason": "STALE_SIGNAL", "signal_age_ms": signal_age_ms},
        }
        _broadcast_udp(veto)
        return

    if msg_type in SAFE_ENTRY_MSG_TYPES and (not _control_plane_healthy or (time.time() - _control_plane_last_success_at) > CONTROL_PLANE_STALE_SEC):
        _suspend_new_entries("control_plane_stale")
        print(f"[KIBOT][BLOCK] Blocking {msg_type} - control-plane stale", flush=True)
        return
    expected_move_pct = float(
        payload.get("expectedMovePct")
        or msg.get("expectedMovePct")
        or msg.get("expectedNetPct")
        or 5.0
    )
    short_term_return_pct = float(
        payload.get("shortTermReturnPct")
        or msg.get("shortTermReturnPct")
        or 0.0
    )
    
    # Dynamic FOMO guard based on price tier
    current_price_idr = float(msg.get("lastPrice") or msg.get("currentPrice") or 0.0)
    fomo_limit = _get_dynamic_fomo_guard(current_price_idr)
    
    if msg_type == "DETECTOR_HIT" and short_term_return_pct >= fomo_limit:
        print(
            f"[KIBOT][VETO_REJECTED] pair={pair} reason=FOMO_GUARD rise_pct={short_term_return_pct:.2f} limit={fomo_limit:.1f}% price={current_price_idr:.0f}",
            flush=True,
        )
        veto = {
            "kind": "lead_lag_breakout",
            "msgType": "VETO_REJECTED",
            "traceId": trace_id,
            "senderBotId": "kibot",
            "pairId": pair,
            "trend": "UP",
            "detectedAtEpochMs": now_ms,
            "sentAtEpochMs": now_ms,
            "expiresAtEpochMs": now_ms + 3_000,
            "confidence": 0.55,
            "expectedNetPct": 0.0,
            "shortTermReturnPct": short_term_return_pct,
            "mediumTermReturnPct": float(msg.get("mediumTermReturnPct") or 0.0),
            "tradeActivityScore": 0.55,
            "forceRotation": False,
            "payload": {
                "reason": "FOMO_GUARD",
                "entry_mode": "LIMIT_PULLBACK",
                "limit_correction_pct": FOMO_LIMIT_CORRECTION_PCT,
            },
        }
        _broadcast_udp(veto)
        return
    if msg_type in {"SELL_WALL_SURGE", "MOMENTUM_LOSS"} and pair.lower() in _active_positions_cache:
        _emit_emergency_veto_sell(
            pair=pair,
            reason=f"kinance_{msg_type.lower()}",
            trace_id=trace_id,
            confidence=0.96,
            expected_net_pct=max(0.1, expected_move_pct),
            extra_payload={"source_msg_type": msg_type},
        )
        return

    est_slippage_pct = float(payload.get("estSlippagePct") or msg.get("estSlippagePct") or 1.5)
    viability = _estimate_exit_viability(expected_move_pct, est_slippage_pct)
    score = _coingecko_track_record_score(pair)
    trending_symbols = {c.get("symbol", "").lower() for c in _get_coingecko_trending_cache()}
    pair_symbol = pair.split("_", 1)[0].lower()
    if pair_symbol in trending_symbols:
        score = min(0.98, score + 0.12)
    ai_confidence = float(payload.get("confidence") or msg.get("confidence") or score)
    min_score = AI_APPROVAL_INSTANT_MIN_SCORE if msg_type == "INSTANT_BUY_ANOMALY" else AI_APPROVAL_MIN_SCORE
    min_net = AI_APPROVAL_INSTANT_MIN_EXPECTED_NET_PCT if msg_type == "INSTANT_BUY_ANOMALY" else AI_APPROVAL_MIN_EXPECTED_NET_PCT
    if msg_type == "INSTANT_BUY_ANOMALY":
        approved = viability["net_profit_pct"] >= min_net and score >= min_score and ai_confidence >= min_score
    else:
        approved = viability["net_profit_pct"] >= min_net and score >= min_score and ai_confidence >= min_score

    veto_msg_type = "VETO_SELL_CONFIRMED" if msg_type in {"SELL_WALL_SURGE", "MOMENTUM_LOSS"} else "VETO_APPROVED"
    if not approved:
        veto_msg_type = "VETO_REJECTED"
        print(
            f"[KIBOT][VETO_REJECTED] pair={pair} net={viability['net_profit_pct']:.4f}% reason=AI_CONFIDENCE_GATE score={score:.3f} ai={ai_confidence:.3f}",
            flush=True,
        )
        _veto_metrics["rejected"] += 1
        _update_daily_summary("veto_metric", {"name": "rejected"})
    else:
        print(
            f"[KIBOT][{veto_msg_type}] pair={pair} net={viability['net_profit_pct']:.4f}% trackScore={score:.3f} ai={ai_confidence:.3f}",
            flush=True,
        )
        if veto_msg_type == "VETO_APPROVED":
            _veto_metrics["approved"] += 1
            _update_daily_summary("veto_metric", {"name": "approved"})
        elif veto_msg_type == "VETO_SELL_CONFIRMED":
            _veto_metrics["sell_confirmed"] += 1
            _update_daily_summary("veto_metric", {"name": "sell_confirmed"})

    veto = {
        "kind": "lead_lag_breakout",
        "msgType": veto_msg_type,
        "traceId": trace_id,
        "senderBotId": "kibot",
        "pairId": pair,
        "trend": "REVERSAL" if msg_type in {"SELL_WALL_SURGE", "MOMENTUM_LOSS"} else "UP",
        "detectedAtEpochMs": now_ms,
        "sentAtEpochMs": now_ms,
        "expiresAtEpochMs": now_ms + 3_000,
        "confidence": min(0.99, (score * 0.55) + (ai_confidence * 0.45)),
        "expectedNetPct": viability["net_profit_pct"],
        "shortTermReturnPct": expected_move_pct,
        "mediumTermReturnPct": expected_move_pct * 0.5,
        "tradeActivityScore": score,
        "forceRotation": True,
        "payload": {
            "exit_viability": viability,
            "track_record_score": score,
            "ai_confidence": ai_confidence,
            "ai_gate_min_score": min_score,
            "ai_gate_min_expected_net_pct": min_net,
            "coingecko_trending_match": pair_symbol in trending_symbols,
        },
    }
    _broadcast_udp(veto)
    _write_runtime_note()


def _extract_symbol_from_text(text: str) -> str:
    if not text:
        return ""
    upper = text.upper()
    bracketed = re.findall(r"\(([A-Z0-9]{2,12})\)", upper)
    if bracketed:
        return bracketed[0].lower()
    plain = re.findall(r"\b([A-Z]{2,8})\b", upper)
    if plain:
        common = {"NEW", "LISTING", "WILL", "SPOT", "BINANCE", "TOKEN", "MARKET"}
        filtered = [token for token in plain if token not in common]
        if filtered:
            return filtered[0].lower()
    return ""


def _cleanup_seen_news_ids() -> None:
    """Remove old entries from _seen_news_ids to prevent unbounded memory growth."""
    global _seen_news_ids, _seen_news_ids_timestamps
    now = time.time()
    ttl_sec = 3600 * 24  # 24 hours TTL
    with _state_lock:
        expired = [k for k, ts in _seen_news_ids_timestamps.items() if (now - ts) > ttl_sec]
        for k in expired:
            _seen_news_ids.discard(k)
            _seen_news_ids_timestamps.pop(k, None)
        # Also enforce max size limit
        if len(_seen_news_ids) > _SEEN_NEWS_IDS_MAX_SIZE:
            # Remove oldest entries
            sorted_by_time = sorted(_seen_news_ids_timestamps.items(), key=lambda x: x[1])
            to_remove = len(_seen_news_ids) - _SEEN_NEWS_IDS_MAX_SIZE + 100  # Remove extra buffer
            for k, _ in sorted_by_time[:to_remove]:
                _seen_news_ids.discard(k)
                _seen_news_ids_timestamps.pop(k, None)


def _scan_rss_and_initiate_detector(feed_url: str, source: str) -> None:
    global _seen_news_ids, _seen_news_ids_timestamps
    try:
        response = requests.get(feed_url, timeout=TIMEOUT)
        if response.status_code != 200:
            return
        root = ET.fromstring(response.text)
    except (requests.exceptions.RequestException, ET.ParseError) as e:
        print(f"[KIBOT][WARN] RSS parse/fetch error source={source} reason={type(e).__name__}", flush=True)
        return
    except Exception as e:
        print(f"[KIBOT][WARN] RSS unexpected error source={source} reason={e}", flush=True)
        return
    items = root.findall(".//item")[:12]
    for item in items:
        title = (item.findtext("title") or "").strip()
        link = (item.findtext("link") or "").strip()
        desc = (item.findtext("description") or "").strip()
        identity = f"{source}|{title}|{link}"
        with _state_lock:
            if identity in _seen_news_ids:
                continue
            _seen_news_ids.add(identity)
            _seen_news_ids_timestamps[identity] = time.time()
        lower_text = f"{title} {desc}".lower()
        if "list" not in lower_text and "listing" not in lower_text and "new coin" not in lower_text:
            continue
        symbol = _extract_symbol_from_text(title) or _extract_symbol_from_text(desc)
        if not symbol:
            continue
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        pair_id = f"{symbol}_idr"
        detector_hit = {
            "kind": "lead_lag_breakout",
            "msgType": "DETECTOR_HIT",
            "traceId": f"news-{source}-{symbol}-{now_ms}",
            "senderBotId": "kibot",
            "pairId": pair_id,
            "trend": "UP",
            "detectedAtEpochMs": now_ms,
            "sentAtEpochMs": now_ms,
            "expiresAtEpochMs": now_ms + 3_000,
            "confidence": 0.74,
            "expectedNetPct": 1.8,
            "shortTermReturnPct": 1.8,
            "mediumTermReturnPct": 0.9,
            "tradeActivityScore": 0.70,
            "forceRotation": True,
            "payload": {
                "source": source,
                "headline": title[:140],
                "type": "NEW_LISTING_NEWS",
            },
        }
        _broadcast_udp(detector_hit)


def _news_scanner_loop() -> None:
    cleanup_counter = 0
    while not _shutdown_event.is_set():
        try:
            _scan_rss_and_initiate_detector(BINANCE_ANNOUNCEMENT_RSS, "binance_rss")
            _scan_rss_and_initiate_detector(COINGECKO_NEWS_FEED, "coingecko_rss")
            # Cleanup seen_news_ids periodically (every ~10 iterations)
            cleanup_counter += 1
            if cleanup_counter >= 10:
                _cleanup_seen_news_ids()
                cleanup_counter = 0
        except Exception as e:
            print(f"[KIBOT][WARN] news_scanner_loop error: {e}", flush=True)
        # Use event.wait for graceful shutdown
        if _shutdown_event.wait(timeout=max(30, NEWS_SCAN_INTERVAL_SEC)):
            break


def _normalize_sector_map(raw_obj: Any) -> Dict[str, list[str]]:
    if not isinstance(raw_obj, dict):
        return {}
    out: Dict[str, list[str]] = {}
    for k, v in raw_obj.items():
        if not isinstance(k, str) or not isinstance(v, list):
            continue
        cleaned = []
        for item in v:
            if not isinstance(item, str):
                continue
            sym = item.strip().lower()
            if not sym:
                continue
            cleaned.append(sym)
        if cleaned:
            out[k.strip().lower()] = cleaned[:12]
    return out


def _load_indodax_tickers() -> set[str]:
    global _indodax_ticker_cache, _indodax_ticker_cache_at
    now = time.time()
    if _indodax_ticker_cache and (now - _indodax_ticker_cache_at) < max(60, INDODAX_TICKER_CACHE_TTL_SEC):
        return _indodax_ticker_cache
    try:
        response = requests.get(INDODAX_SUMMARIES_URL, timeout=TIMEOUT)
        if response.status_code >= 300:
            return _indodax_ticker_cache
        body = response.json()
        tickers = ((body or {}).get("tickers") or {})
        pairs = set()
        if isinstance(tickers, dict):
            for pair_key in tickers.keys():
                if not isinstance(pair_key, str):
                    continue
                norm = pair_key.strip().lower()
                if norm:
                    pairs.add(norm)
        if pairs:
            _indodax_ticker_cache = pairs
            _indodax_ticker_cache_at = now
        return _indodax_ticker_cache
    except Exception:
        return _indodax_ticker_cache


def _sanitize_sector_map_for_indodax(raw_map: Dict[str, list[str]]) -> Dict[str, list[str]]:
    if not raw_map:
        return {}
    tickers = _load_indodax_tickers()
    if not tickers:
        return raw_map
    out: Dict[str, list[str]] = {}
    for sector, coins in raw_map.items():
        keep: list[str] = []
        for coin in coins:
            base = coin.strip().lower()
            if not base:
                continue
            if f"{base}_idr" in tickers:
                keep.append(base)
        if keep:
            out[sector] = keep
    return out


def _fetch_dynamic_correlation_map() -> Dict[str, list[str]]:
    trending = _get_coingecko_trending_cache()
    system_prompt = "Return ONLY JSON object map: {'sector_name':['coin1','coin2','coin3']} without prose."
    user_prompt = (
        "Provide a JSON map of the top 10 most active cryptocurrency sector correlations today. "
        f"CoinGecko trending snapshot: {json.dumps(trending, ensure_ascii=False)}. "
        "Prioritize sectors/coins with strongest momentum now."
    )
    routed_text, provider = _call_ai_router(
        task="correlation_matrix",
        system_prompt=system_prompt,
        user_prompt=user_prompt,
        model_hint=CORRELATION_MODEL,
        timeout_sec=CORRELATION_TIMEOUT_SEC,
    )
    if routed_text:
        print(f"[KIBOT][AI_CORRELATION_FETCH] provider={provider}", flush=True)
        return _normalize_sector_map(_parse_json_candidate(routed_text))
    if not CORRELATION_API_URL:
        print("[KIBOT][AI_CORRELATION_FETCH][SKIP] router+legacy unavailable.", flush=True)
        return {}
    payload = {
        "model": CORRELATION_MODEL,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": 0.1,
    }
    headers = {"Content-Type": "application/json"}
    if CORRELATION_API_KEY:
        headers["Authorization"] = f"Bearer {CORRELATION_API_KEY}"
    response = requests.post(CORRELATION_API_URL, headers=headers, json=payload, timeout=CORRELATION_TIMEOUT_SEC)
    if response.status_code >= 300:
        return {}
    return _normalize_sector_map(_parse_json_candidate(_extract_assistant_text(response.json() or {})))


def _broadcast_dynamic_correlation_map() -> None:
    global _last_sector_map
    if not CORRELATION_ENABLED:
        return
    try:
        sectors = _fetch_dynamic_correlation_map()
        sectors = _sanitize_sector_map_for_indodax(sectors)
        if not sectors:
            return
        _last_sector_map = sectors
        msg = {
            "msgType": "CORRELATION_MATRIX",
            "senderBotId": "kibot",
            "updatedAtEpochMs": int(datetime.now(timezone.utc).timestamp() * 1000),
            "sectors": sectors,
        }
        _broadcast_udp(msg)
        print(f"[KIBOT][AI_CORRELATION_FETCH] sectors={len(sectors)}", flush=True)
        _append_runtime_event("correlation_matrix_refresh", {"sector_count": len(sectors)})
        _write_runtime_note()
    except Exception as error:
        print(f"[KIBOT][AI_CORRELATION_FETCH][ERROR] {error}", flush=True)


def _correlation_loop() -> None:
    while not _shutdown_event.is_set():
        try:
            _broadcast_dynamic_correlation_map()
        except Exception as e:
            print(f"[KIBOT][WARN] correlation_loop error: {e}", flush=True)
        if _shutdown_event.wait(timeout=max(300, CORRELATION_INTERVAL_SEC)):
            break


def _coingecko_trending_loop() -> None:
    while not _shutdown_event.is_set():
        try:
            _refresh_coingecko_trending_cache()
        except Exception as e:
            print(f"[KIBOT][WARN] coingecko_trending_loop error: {e}", flush=True)
        if _shutdown_event.wait(timeout=max(180, COINGECKO_TRENDING_INTERVAL_SEC)):
            break


def _pair_symbol(pair: str) -> str:
    return pair.split("_", 1)[0].lower().strip()


def _symbol_in_ai_sector(symbol: str) -> bool:
    if not symbol:
        return False
    for coins in _last_sector_map.values():
        if symbol in coins:
            return True
    return False


def _emit_emergency_veto_sell(
    pair: str,
    reason: str,
    trace_id: str | None = None,
    confidence: float = 0.94,
    expected_net_pct: float = 0.2,
    extra_payload: Dict[str, Any] | None = None,
) -> None:
    now = time.time()
    pair_key = pair.lower().strip()
    if not pair_key:
        return
    cooldown_until = _emergency_sell_cooldown_until.get(pair_key, 0.0)
    if now < cooldown_until:
        return
    _emergency_sell_cooldown_until[pair_key] = now + max(3, EMERGENCY_SELL_COOLDOWN_SEC)
    now_ms = int(now * 1000)
    payload_obj = {
        "reason": reason,
        "trigger": "kibot_active_overwatch",
    }
    if isinstance(extra_payload, dict):
        payload_obj.update(extra_payload)
    veto = {
        "kind": "lead_lag_breakout",
        "msgType": "EMERGENCY_VETO_SELL",
        "traceId": trace_id or f"eveto-{pair_key}-{now_ms}",
        "senderBotId": "kibot",
        "pairId": pair_key,
        "trend": "REVERSAL",
        "detectedAtEpochMs": now_ms,
        "sentAtEpochMs": now_ms,
        "expiresAtEpochMs": now_ms + 3_000,
        "confidence": confidence,
        "expectedNetPct": expected_net_pct,
        "shortTermReturnPct": -0.8,
        "mediumTermReturnPct": -0.5,
        "tradeActivityScore": 0.8,
        "forceRotation": True,
        "payload": payload_obj,
    }
    _broadcast_udp(veto)
    print(
        f"[KIBOT][EMERGENCY_VETO_SELL] pair={pair_key} reason={reason} trace={veto['traceId']}",
        flush=True,
    )
    _veto_metrics["emergency_sell"] += 1
    _update_daily_summary("veto_metric", {"name": "emergency_sell"})
    _append_runtime_event(
        "emergency_veto_sell",
        {"pair": pair_key, "reason": reason, "trace_id": veto["traceId"]},
    )
    _write_runtime_note()


def _process_orderbook_collapse(msg: Dict[str, Any]) -> None:
    pair = str(msg.get("pair") or msg.get("pairId") or "").lower().strip()
    if not pair:
        return
    if pair not in _active_positions_cache:
        return
    _emit_emergency_veto_sell(
        pair=pair,
        reason="kinance_orderbook_collapse",
        trace_id=str(msg.get("traceId") or ""),
        confidence=0.97,
        expected_net_pct=float(msg.get("expectedNetPct") or 0.2),
        extra_payload={
            "source_msg_type": str(msg.get("msgType") or "").upper(),
            "short_term_return_pct": float(msg.get("shortTermReturnPct") or 0.0),
            "medium_term_return_pct": float(msg.get("mediumTermReturnPct") or 0.0),
        },
    )


def _process_active_positions(msg: Dict[str, Any]) -> None:
    global _last_active_positions_log_at
    positions = msg.get("positions")
    if not isinstance(positions, list):
        return
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    tracked_pairs: Dict[str, Dict[str, Any]] = {}
    for row in positions:
        if not isinstance(row, dict):
            continue
        pair = str(row.get("pairId") or "").lower().strip()
        if not pair:
            continue
        tracked_pairs[pair] = row
    _active_positions_cache.clear()
    _active_positions_cache.update(tracked_pairs)
    now_ts = time.time()
    if (now_ts - _last_active_positions_log_at) >= 30:
        _last_active_positions_log_at = now_ts
        print(
            f"[KIBOT][ACTIVE_POSITIONS] count={len(tracked_pairs)} pairs={','.join(sorted(tracked_pairs.keys())[:6])}",
            flush=True,
        )
        _append_runtime_event(
            "active_positions_snapshot",
            {"count": len(tracked_pairs), "pairs": sorted(tracked_pairs.keys())[:6]},
        )
        _write_runtime_note()
    relay_payload = {
        "kind": "trinity_state",
        "msgType": "ACTIVE_POSITIONS",
        "senderBotId": "kibot",
        "sentAtEpochMs": now_ms,
        "positions": list(tracked_pairs.values()),
    }
    _broadcast_udp(relay_payload)
    trending_symbols = {c.get("symbol", "").lower() for c in _get_coingecko_trending_cache()}
    for pair, row in tracked_pairs.items():
        pnl_pct = float(row.get("pnlPct") or 0.0)
        symbol = _pair_symbol(pair)
        track_score = _coingecko_track_record_score(pair)
        is_trending = symbol in trending_symbols
        in_sector = _symbol_in_ai_sector(symbol)
        if pnl_pct <= EMERGENCY_SELL_NEGATIVE_PNL_PCT and (not is_trending) and (track_score < 0.58 or not in_sector):
            _emit_emergency_veto_sell(
                pair=pair,
                reason="macro_overwatch_drop",
                confidence=0.93,
                expected_net_pct=max(0.1, abs(pnl_pct)),
                extra_payload={
                    "pnl_pct": pnl_pct,
                    "track_score": track_score,
                    "trending": is_trending,
                    "in_ai_sector": in_sector,
                },
            )


def _signal_handler(signum: int, frame: Any) -> None:
    """Handle shutdown signals gracefully."""
    global _main_socket
    sig_name = signal.Signals(signum).name if hasattr(signal, 'Signals') else str(signum)
    print(f"\n[KIBOT][SHUTDOWN] Received {sig_name}, initiating graceful shutdown...", flush=True)
    _shutdown_event.set()
    # Close main socket to unblock recvfrom
    if _main_socket:
        try:
            _main_socket.close()
        except Exception:
            pass
    global _http_server
    if _http_server:
        try:
            _http_server.shutdown()
        except Exception:
            pass
    _append_runtime_event("manager_shutdown", {"signal": sig_name})
    _write_runtime_note(force=True)


def _http_state_payload() -> Dict[str, Any]:
    with _state_lock:
        return {
            "ok": True,
            "service": "kibot-manager",
            "system_state": str(_gate_state.get("entry_state") or "HEALTHY"),
            "trading_mode": str(_gate_state.get("mode") or "CONSERVATIVE"),
            "effectiveState": "RUNNING" if not _entry_state_is_suspended() else "DEGRADED",
            "tradingAllowed": (not _entry_state_is_suspended()) and not bool(_daily_guard_state.get("hard_stopped")),
            "marketRegime": _daily_summary_market_regime() if DAILY_SUMMARY_ENABLED else "UNKNOWN",
            "degradedReason": str(_gate_state.get("reason") or _daily_guard_state.get("reason") or ""),
            "healthDecision": str(_gate_state.get("reason") or ""),
            "statusMessage": "Server monitor connected to live feed",
            "nodeStatus": "active",
            "hard_stop_active": bool(_daily_guard_state.get("hard_stopped")),
            "daily_pnl_pct": _daily_guard_state.get("daily_pnl_pct"),
            "api_fail_streak": _api_fail_streak,
            "control_plane_healthy": _control_plane_healthy,
            "pair_memory_count": len(_pair_memory),
            "pairs_on_cooldown": [pair for pair in _pair_memory.keys() if _is_pair_on_cooldown(pair)],
            "capital_health": {
                "total_equity_est_idr": _get_total_equity_estimate(),
                "minimum_viable_idr": MINIMUM_VIABLE_CAPITAL_IDR,
                "is_capital_sufficient": _check_minimum_capital(),
                "fee_round_trip_pct": round(_effective_fee_pct() * 2.0, 4),
                "breakeven_per_trade_pct": round((_effective_fee_pct() * 2.0) + 0.015, 4),
                "status": (
                    "VIABLE"
                    if (_get_total_equity_estimate() or 0.0) >= MINIMUM_VIABLE_CAPITAL_IDR
                    else f"INSUFFICIENT — add Rp{max(0.0, MINIMUM_VIABLE_CAPITAL_IDR - (_get_total_equity_estimate() or 0.0)):,.0f} more"
                ),
            },
            "metrics": {
                "market_orders_today": _metrics.get("market_orders_today", 0),
                "limit_orders_today": _metrics.get("limit_orders_today", 0),
                "limit_ratio": (
                    float(_metrics.get("limit_orders_today", 0))
                    / max(float(_metrics.get("limit_orders_today", 0)) + float(_metrics.get("market_orders_today", 0)), 1.0)
                ),
                "fee_bleed_est_idr": _metrics.get("fee_bleed_est_idr", 0.0),
                "entries_blocked": {
                    "hard_stop": _metrics.get("entries_blocked_hard_stop", 0),
                    "learn_gate": _metrics.get("entries_blocked_learn_gate", 0),
                    "whatif": _metrics.get("entries_blocked_whatif", 0),
                },
                "whatif_enter_rate": (
                    float(_metrics.get("whatif_enters_today", 0))
                    / max(float(_metrics.get("whatif_enters_today", 0)) + float(_metrics.get("whatif_skips_today", 0)), 1.0)
                ),
            },
            "math_review": {
                "last_action": _math_review_last_action,
                "last_reason": _math_review_last_reason,
                "trade_journal_count": len(_math_review_trade_journal),
            },
            "uptime_seconds": int(time.time() - _bot_start_time),
            "checked_at": _safe_isoformat(),
        }


class _ManagerStateHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path.startswith("/api/state"):
            payload = _http_state_payload()
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self) -> None:  # noqa: N802
        if not self.path.startswith("/api/notify"):
            self.send_response(404)
            self.end_headers()
            return
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            payload = json.loads(body or "{}")
            message = str(payload.get("msg") or "").strip()
            if message:
                print(f"[KIBOT][NOTIFY] {message}", flush=True)
                _append_runtime_event("notify", {"message": message})
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"ok": True}, ensure_ascii=False).encode("utf-8"))
        except Exception as error:
            self.send_response(500)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"ok": False, "error": str(error)}, ensure_ascii=False).encode("utf-8"))

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A003
        return


def _state_server_loop() -> None:
    global _http_server
    bind_host = os.getenv("KIBOT_MANAGER_HTTP_BIND_HOST", "127.0.0.1")
    bind_port = int(os.getenv("KIBOT_MANAGER_HTTP_BIND_PORT", str(UDP_BIND_PORT)))
    try:
        server = ThreadingHTTPServer((bind_host, bind_port), _ManagerStateHandler)
        _http_server = server
        print(f"[KIBOT][HTTP] state server listening on {bind_host}:{bind_port}", flush=True)
        server.serve_forever(poll_interval=0.5)
    except Exception as error:
        print(f"[KIBOT][HTTP][ERROR] failed to start state server reason={error}", flush=True)
    finally:
        _http_server = None


def main() -> None:
    global _main_socket
    
    # Register signal handlers for graceful shutdown
    signal.signal(signal.SIGINT, _signal_handler)
    signal.signal(signal.SIGTERM, _signal_handler)
    
    _ensure_env()
    STATE_ROOT.mkdir(parents=True, exist_ok=True)
    _reconcile_daily_guard_day_rollover()
    _ensure_hard_stop_consistency()
    _write_json_file(PROVIDER_STATE_PATH, _provider_runtime_state)
    _save_pair_cooldown_state()
    _save_gate_state()
    _save_daily_guard_state()
    _bootstrap_daily_guard_from_kidax()
    _set_conservative_mode("fresh_start")
    if DAILY_SUMMARY_ENABLED:
        _write_json_file(DAILY_SUMMARY_PATH, _load_daily_summary())
    _append_runtime_event(
        "manager_start",
        {
            "kidax_target": f"{KIDAX_UDP_HOST}:{KIDAX_UDP_PORT}" if KIDAX_UDP_HOST else "",
            "kinance_target": f"{KINANCE_UDP_HOST}:{KINANCE_UDP_PORT}" if KINANCE_UDP_HOST else "",
        },
    )
    _write_runtime_note(force=True)
    force_evaluate_recent_loss()
    scanner_thread = threading.Thread(target=_news_scanner_loop, name="kibot-news-scanner", daemon=True)
    scanner_thread.start()
    corr_thread = threading.Thread(target=_correlation_loop, name="kibot-correlation-loop", daemon=True)
    corr_thread.start()
    gecko_thread = threading.Thread(target=_coingecko_trending_loop, name="kibot-coingecko-loop", daemon=True)
    gecko_thread.start()
    heartbeat_thread = threading.Thread(target=_heartbeat_loop, name="kibot-heartbeat-loop", daemon=True)
    heartbeat_thread.start()
    health_gate_thread = threading.Thread(target=_health_gate_loop, name="kibot-health-gate-loop", daemon=True)
    health_gate_thread.start()
    ai_review_thread = threading.Thread(target=_ai_batch_review_loop, name="kibot-ai-review-loop", daemon=True)
    ai_review_thread.start()
    math_review_thread = threading.Thread(target=_math_review_loop, name="kibot-math-review-loop", daemon=True)
    math_review_thread.start()
    state_server_thread = threading.Thread(target=_state_server_loop, name="kibot-state-server", daemon=True)
    state_server_thread.start()
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    # Allow socket reuse for quick restarts
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    _main_socket = sock
    try:
        sock.bind((UDP_BIND_HOST, UDP_BIND_PORT))
        # Set socket timeout to allow periodic shutdown checks
        sock.settimeout(5.0)
        print(
            json.dumps(
                {
                    "ok": True,
                    "service": "kibot_manager_udp_veto",
                    "bind": f"{UDP_BIND_HOST}:{UDP_BIND_PORT}",
                    "kidax_target": f"{KIDAX_UDP_HOST}:{KIDAX_UDP_PORT}",
                    "kinance_target": f"{KINANCE_UDP_HOST}:{KINANCE_UDP_PORT}" if KINANCE_UDP_HOST else None,
                },
                ensure_ascii=False,
            )
        )
        while not _shutdown_event.is_set():
            try:
                raw, _ = sock.recvfrom(65535)
                msg = json.loads(raw.decode("utf-8"))
                _process_signal(msg)
            except socket.timeout:
                # Normal timeout, check shutdown event
                global _last_daily_guard_check_at
                now = time.time()
                if (now - _last_daily_guard_check_at) >= 60.0:
                    _last_daily_guard_check_at = now
                    _check_daily_loss_limit()
                continue
            except OSError as e:
                if _shutdown_event.is_set():
                    break
                print(f"[KIBOT][UDP][ERROR] socket error: {e}", flush=True)
            except json.JSONDecodeError as e:
                print(f"[KIBOT][UDP][ERROR] JSON parse failed: {e}", flush=True)
            except Exception as error:
                print(f"[KIBOT][UDP][ERROR] process failed reason={error}", flush=True)
    finally:
        print("[KIBOT][SHUTDOWN] Closing UDP socket...", flush=True)
        try:
            sock.close()
        except Exception:
            pass
        _main_socket = None
    
    print("[KIBOT][SHUTDOWN] KiBot Manager stopped gracefully.", flush=True)
    sys.exit(0)


if __name__ == "__main__":
    main()
