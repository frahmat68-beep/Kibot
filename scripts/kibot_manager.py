#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import socket
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Tuple
from xml.etree import ElementTree as ET

import requests


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

SUPABASE_URL = os.getenv("SUPABASE_URL", "").rstrip("/")
SUPABASE_KEY = os.getenv("SUPABASE_SERVICE_ROLE_KEY") or os.getenv("SUPABASE_ANON_KEY") or ""
TIMEOUT = float(os.getenv("KIBOT_MANAGER_HTTP_TIMEOUT_SEC", "12"))
UDP_BIND_HOST = os.getenv("KIBOT_MANAGER_UDP_BIND_HOST", "0.0.0.0")
UDP_BIND_PORT = int(os.getenv("KIBOT_MANAGER_UDP_BIND_PORT", "9998"))
KINANCE_UDP_HOST = os.getenv("KINANCE_UDP_HOST", "")
KINANCE_UDP_PORT = int(os.getenv("KINANCE_UDP_PORT", "9999"))
KIDAX_UDP_HOST = os.getenv("KIDAX_UDP_HOST", "")
KIDAX_UDP_PORT = int(os.getenv("KIDAX_UDP_PORT", "9999"))
TAKER_FEE_PCT = float(os.getenv("KIDAX_TAKER_FEE_PCT", "0.51"))
STALE_SIGNAL_ABORT_MS = int(os.getenv("KIBOT_STALE_SIGNAL_ABORT_MS", "1500"))
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
        "blackbox,groq,openrouter,cohere,gemini",
    ).split(",")
    if token.strip()
]
AI_REQUEST_TIMEOUT_SEC = float(os.getenv("KIBOT_AI_REQUEST_TIMEOUT_SEC", "18"))

GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
GROQ_MODEL = os.getenv("GROQ_MODEL", "llama-3.1-8b-instant")
GROQ_API_URL = os.getenv("GROQ_API_URL", "https://api.groq.com/openai/v1/chat/completions")

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.0-flash-lite")
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
_seen_news_ids: set[str] = set()
_indodax_ticker_cache: set[str] = set()
_indodax_ticker_cache_at: float = 0.0
_coingecko_trending_cache: Dict[str, Any] = {"coins": [], "fetched_at_epoch_ms": 0}
_last_sector_map: Dict[str, list[str]] = {}
_active_positions_cache: Dict[str, Dict[str, Any]] = {}
_emergency_sell_cooldown_until: Dict[str, float] = {}
_last_active_positions_log_at: float = 0.0


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
    response = requests.post(api_url, headers=headers, json=payload, timeout=timeout_sec)
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
    url = f"{base}/models/{model}:generateContent?key={GEMINI_API_KEY}"
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
    response = requests.post(url, json=payload, timeout=timeout_sec)
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
    response = requests.post(COHERE_API_URL, headers=headers, json=payload, timeout=timeout_sec)
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
    for provider in AI_PROVIDER_ORDER:
        if not _provider_has_credentials(provider):
            provider_errors[provider] = "missing_credentials"
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
            return text, provider
        except Exception as error:
            provider_errors[provider] = str(error)
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
        return 0.8 if coins else 0.58
    except Exception as error:
        print(f"[KIBOT][WARN] CoinGecko API error pair={pair} reason={error}", flush=True)
        return 0.60


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


def _upsert_trade_history(entry: Dict[str, Any]) -> None:
    headers = _headers()
    headers["Prefer"] = "return=minimal"
    primary_url = f"{SUPABASE_URL}/rest/v1/trade_history"
    response = requests.post(primary_url, headers=headers, json=entry, timeout=TIMEOUT)
    if response.status_code < 300:
        return
    if response.status_code != 404:
        response.raise_for_status()
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
    fallback_resp = requests.post(fallback_url, headers=headers, json=fallback_payload, timeout=TIMEOUT)
    fallback_resp.raise_for_status()


def _book_entry_from_execution(msg: Dict[str, Any]) -> None:
    now_iso = datetime.now(timezone.utc).isoformat()
    gross = float(msg.get("gross_pnl_idr") or 0.0)
    est_cost = float(msg.get("estimated_cost_idr") or 0.0)
    net = gross - est_cost
    try:
        _upsert_trade_history(
            {
                "pair_id": msg.get("pair", "unknown"),
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
    except Exception as error:
        print(
            f"[KIBOT][LEDGER][WARN] upsert failed pair={msg.get('pair')} trace={msg.get('traceId')} reason={error}",
            flush=True,
        )
    if POST_MORTEM_ENABLED and net < 0:
        thread = threading.Thread(
            target=evaluate_foolish_trade,
            args=(
                {
                    "trace_id": msg.get("traceId"),
                    "pair": msg.get("pair"),
                    "gross_pnl_idr": gross,
                    "estimated_cost_idr": est_cost,
                    "net_pnl_idr": net,
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


def _process_signal(msg: Dict[str, Any]) -> None:
    msg_type = str(msg.get("msgType") or "").upper()
    if msg_type == "ACTIVE_POSITIONS":
        _process_active_positions(msg)
        return
    if msg_type == "ORDERBOOK_COLLAPSE":
        _process_orderbook_collapse(msg)
        return
    if msg_type == "EXECUTION_FILLED":
        _book_entry_from_execution(msg)
        return
    if msg_type not in {"DETECTOR_HIT", "INSTANT_BUY_ANOMALY", "SELL_WALL_SURGE", "MOMENTUM_LOSS"}:
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
    if msg_type == "DETECTOR_HIT" and short_term_return_pct >= FOMO_GUARD_PCT:
        print(
            f"[KIBOT][VETO_REJECTED] pair={pair} reason=FOMO_GUARD rise_pct={short_term_return_pct:.2f}",
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
    if msg_type == "INSTANT_BUY_ANOMALY":
        # Jalur cepat: tetap fee-aware, tapi jangan terlalu takut saat momentum kilat.
        approved = viability["net_profit_pct"] > -0.05 and score >= 0.45
    else:
        approved = viability["net_profit_pct"] > 0 and score >= 0.55

    veto_msg_type = "VETO_SELL_CONFIRMED" if msg_type in {"SELL_WALL_SURGE", "MOMENTUM_LOSS"} else "VETO_APPROVED"
    if not approved:
        veto_msg_type = "VETO_REJECTED"
        print(
            f"[KIBOT][VETO_REJECTED] pair={pair} net={viability['net_profit_pct']:.4f}% reason=NEGATIVE_NET_OR_LOW_SCORE",
            flush=True,
        )
    else:
        print(
            f"[KIBOT][{veto_msg_type}] pair={pair} net={viability['net_profit_pct']:.4f}% trackScore={score:.3f}",
            flush=True,
        )

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
        "confidence": score,
        "expectedNetPct": viability["net_profit_pct"],
        "shortTermReturnPct": expected_move_pct,
        "mediumTermReturnPct": expected_move_pct * 0.5,
        "tradeActivityScore": score,
        "forceRotation": True,
        "payload": {
            "exit_viability": viability,
            "track_record_score": score,
            "coingecko_trending_match": pair_symbol in trending_symbols,
        },
    }
    _broadcast_udp(veto)


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


def _scan_rss_and_initiate_detector(feed_url: str, source: str) -> None:
    try:
        response = requests.get(feed_url, timeout=TIMEOUT)
        if response.status_code != 200:
            return
        root = ET.fromstring(response.text)
    except Exception:
        return
    items = root.findall(".//item")[:12]
    for item in items:
        title = (item.findtext("title") or "").strip()
        link = (item.findtext("link") or "").strip()
        desc = (item.findtext("description") or "").strip()
        identity = f"{source}|{title}|{link}"
        if identity in _seen_news_ids:
            continue
        _seen_news_ids.add(identity)
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
    while True:
        _scan_rss_and_initiate_detector(BINANCE_ANNOUNCEMENT_RSS, "binance_rss")
        _scan_rss_and_initiate_detector(COINGECKO_NEWS_FEED, "coingecko_rss")
        time.sleep(max(30, NEWS_SCAN_INTERVAL_SEC))


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
    except Exception as error:
        print(f"[KIBOT][AI_CORRELATION_FETCH][ERROR] {error}", flush=True)


def _correlation_loop() -> None:
    while True:
        _broadcast_dynamic_correlation_map()
        time.sleep(max(300, CORRELATION_INTERVAL_SEC))


def _coingecko_trending_loop() -> None:
    while True:
        _refresh_coingecko_trending_cache()
        time.sleep(max(180, COINGECKO_TRENDING_INTERVAL_SEC))


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


def main() -> None:
    _ensure_env()
    force_evaluate_recent_loss()
    scanner_thread = threading.Thread(target=_news_scanner_loop, name="kibot-news-scanner", daemon=True)
    scanner_thread.start()
    corr_thread = threading.Thread(target=_correlation_loop, name="kibot-correlation-loop", daemon=True)
    corr_thread.start()
    gecko_thread = threading.Thread(target=_coingecko_trending_loop, name="kibot-coingecko-loop", daemon=True)
    gecko_thread.start()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((UDP_BIND_HOST, UDP_BIND_PORT))
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
    while True:
        raw, _ = sock.recvfrom(65535)
        try:
            msg = json.loads(raw.decode("utf-8"))
            _process_signal(msg)
        except Exception as error:
            print(f"[KIBOT][UDP][ERROR] parse/process failed reason={error}", flush=True)


if __name__ == "__main__":
    main()
