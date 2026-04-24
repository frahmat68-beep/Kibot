#!/usr/bin/env python3
"""
KiBot AI Coordinator
====================
Rate-limited AI router for non-trading subsystems.
"""

from __future__ import annotations

import hashlib
import json
import os
import time
import urllib.error
import urllib.request
from datetime import date
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional


def _load_dotenv_early() -> None:
    candidates = [
        Path(".env.server"),
        Path(".env.kibot"),
        Path(".env.kibot_manager"),
        Path(".env"),
        Path("scripts/.env"),
        Path("../.env"),
    ]
    explicit = os.getenv("KIBOT_MANAGER_ENV_FILE")
    if explicit:
        candidates.insert(0, Path(explicit))
    for path in candidates:
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            raw = line.strip()
            if not raw or raw.startswith("#") or "=" not in raw:
                continue
            key, value = raw.split("=", 1)
            key = key.strip()
            value = value.strip().strip("'").strip('"')
            if key and key not in os.environ:
                os.environ[key] = value


def _env_first(*keys: str) -> str:
    for key in keys:
        value = os.getenv(key, "").strip()
        if value:
            return value
    return ""


_load_dotenv_early()

ROOT = Path(os.getenv("KIBOT_RUNTIME_ROOT", Path(__file__).resolve().parent.parent))
STATE_DIR = ROOT / "state"
RATE_STATE_FILE = STATE_DIR / "ai_coordinator_rate.json"
RESPONSE_CACHE = STATE_DIR / "ai_coordinator_cache.json"
REQUEST_TIMEOUT_SEC = float(os.getenv("KIBOT_AI_COORDINATOR_TIMEOUT_SEC", "12"))

PROVIDERS = {
    "ollama": {
        "daily_limit": 100000,
        "model": os.getenv("KIBOT_OLLAMA_MODEL", "qwen3:4b"),
        "api_key_envs": ["OLLAMA_API_KEY", "KIBOT_OLLAMA_GATEWAY_TOKEN"],
        "base_url": os.getenv("KIBOT_OLLAMA_BASE_URL", "http://127.0.0.1:11434/api/chat"),
        "priority": 1,
    },
    "groq": {
        "daily_limit": 14400,
        "model": "llama-3.1-8b-instant",
        "api_key_envs": ["GROQ_API_KEY"],
        "base_url": "https://api.groq.com/openai/v1/chat/completions",
        "priority": 2,
    },
    "gemini": {
        "daily_limit": 1500,
        "model": os.getenv("GEMINI_SUPPORT_MODEL", os.getenv("GEMINI_MODEL", "gemini-2.0-flash-lite")),
        "api_key_envs": ["GEMINI_API_KEY", "GOOGLE_API_KEY", "GEMINI_SUPPORT_API_KEY"],
        "base_url": "https://generativelanguage.googleapis.com/v1beta/models",
        "priority": 3,
    },
    "nvidia": {
        "daily_limit": 1000,
        "model": "meta/llama-3.1-70b-instruct",
        "api_key_envs": ["NVIDIA_API_KEY"],
        "base_url": "https://integrate.api.nvidia.com/v1/chat/completions",
        "priority": 4,
    },
    "openrouter": {
        "daily_limit": 200,
        "model": "openrouter/free",
        "api_key_envs": ["OPENROUTER_API_KEY"],
        "base_url": "https://openrouter.ai/api/v1/chat/completions",
        "priority": 5,
    },
    "cohere": {
        "daily_limit": 100,
        "model": "command-a-03-2025",
        "api_key_envs": ["COHERE_API_KEY"],
        "base_url": "https://api.cohere.ai/v1/chat",
        "priority": 6,
    },
    "jina": {
        "daily_limit": 50,
        "model": "jina-3-8b-instruct",
        "api_key_envs": ["JINA_API_KEY"],
        "base_url": "https://api.jina.ai/v1/chat/completions",
        "priority": 7,
    },
}

PROMPT_PROVIDER_ORDER = {
    "BRAIN_CRITIC": ["groq", "openrouter", "ollama", "gemini", "nvidia", "cohere", "jina"],
    "WHATIF_SIMULATION": ["openrouter", "ollama", "groq", "gemini", "cohere", "nvidia", "jina"],
    "TRADE_POSTMORTEM": ["ollama", "openrouter", "groq", "gemini", "nvidia", "cohere", "jina"],
    "VETO_ANALYSIS": ["groq", "openrouter", "ollama", "gemini", "nvidia", "cohere", "jina"],
    "WEEKLY_SUMMARY": ["ollama", "openrouter", "groq", "gemini", "cohere", "nvidia", "jina"],
    "NEWS_ANALYSIS": ["openrouter", "ollama", "groq", "gemini", "cohere", "nvidia", "jina"],
}

PROMPT_TEMPLATES = {
    "BRAIN_CRITIC": (
        "You are KiBot's strategy critic.\n"
        "Watch symbols: {watch_symbols}\n"
        "Market pulse: {market_pulse}\n"
        "Daily target: {daily_target}\n"
        "Capital profile: {capital_profile}\n"
        "Rules: keep risk controls strict, prefer survival on tiny accounts, and only turn opportunistic when both market pulse and local learning support it.\n"
        "Return compact JSON only with keys "
        "{{\"capital_posture\":\"DEFENSIVE|NEUTRAL|OPPORTUNISTIC\",\"risk_bias\":\"RISK_OFF|MIXED|RISK_ON\",\"confidence\":0.0,\"strategy_next\":\"...\",\"focus_symbols\":[...],\"do_not_do\":[...]}}"
    ),
    "VETO_ANALYSIS": (
        "Kamu adalah AI veto gate KiBot.\nSignal: {signal_data}\nMarket: {market_state}\nSystem: {system_health}\n"
        "Balas JSON {{\"approved\": true/false, \"reason\": \"...\", \"confidence\": 0.0}}"
    ),
    "INFRA_ANALYSIS": (
        "Analisis laporan infra Oracle Micro berikut, fokus hanya infrastruktur.\n{audit_report}\n"
        "Balas JSON {{\"critical_issues\": [], \"fixes\": [], \"optimization_tips\": []}}"
    ),
    "TRADE_POSTMORTEM": (
        "Analisis trade rugi berikut.\nTrade: {trade_data}\nMarket: {market_context}\nSystem: {system_state}\n"
        "Balas JSON {{\"root_cause\": \"...\", \"contributing_factors\": [], \"prevention\": \"...\", \"pattern\": \"...\"}}"
    ),
    "WEEKLY_SUMMARY": (
        "Buat insight mingguan untuk KiBot berdasarkan data berikut.\n{weekly_data}\n"
        "Balas JSON {{\"overall_assessment\": \"...\", \"top_issues\": [], \"opportunities_missed\": [], \"strengths\": [], \"recommendations\": [], \"next_week_focus\": \"...\"}}"
    ),
    "WHATIF_SIMULATION": (
        "Simulasikan skenario berikut.\nScenario: {scenario}\nContext: {context}\nParams: {params}\n"
        "Balas JSON {{\"likely_outcome\": \"...\", \"probability\": 0.0, \"risk_factors\": [], \"expected_pnl_pct\": 0.0, \"recommendation\": \"...\"}}"
    ),
    "NEWS_ANALYSIS": (
        "Analyze latest market news for {symbol}.\nNews: {news_dump}\n"
        "Categorize sentiment and impact on price movement.\n"
        "Return JSON {{\"sentiment\": \"BULLISH/BEARISH/NEUTRAL\", \"confidence\": 0.0, \"reason\": \"...\", \"action\": \"HOLD/SELL/STABLE\"}}"
    ),
}


def _atomic_write(path: Path, data: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.tmp.{os.getpid()}")
    try:
        with open(tmp, "w", encoding="utf-8") as handle:
            json.dump(data, handle)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()


def _load_rate_state() -> Dict[str, Any]:
    if not RATE_STATE_FILE.exists():
        return {"date": str(date.today()), "counts": {}}
    try:
        state = json.loads(RATE_STATE_FILE.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {"date": str(date.today()), "counts": {}}
    if state.get("date") != str(date.today()):
        return {"date": str(date.today()), "counts": {}}
    return state


def _save_rate_state(state: Dict[str, Any]) -> None:
    _atomic_write(RATE_STATE_FILE, state)


def _provider_api_key(provider: str) -> str:
    config = PROVIDERS.get(provider) or {}
    envs = config.get("api_key_envs") or []
    return _env_first(*[str(item) for item in envs])


def _candidate_providers(prompt_type: str) -> List[str]:
    state = _load_rate_state()
    counts = state.get("counts", {})
    configured_order = [
        item.strip().lower()
        for item in os.getenv("KIBOT_AI_PROVIDER_ORDER", "").split(",")
        if item.strip()
    ]
    prompt_order = list(PROMPT_PROVIDER_ORDER.get(prompt_type, []))
    default_order = [name for name, _ in sorted(PROVIDERS.items(), key=lambda item: item[1]["priority"])]
    ordered: List[str] = []
    for name in configured_order + prompt_order + default_order:
        if name not in PROVIDERS or name in ordered:
            continue
        config = PROVIDERS[name]
        if counts.get(name, 0) >= int(config["daily_limit"]):
            continue
        if not _provider_api_key(name):
            continue
        ordered.append(name)
    return ordered


def _increment_usage(provider: str) -> None:
    state = _load_rate_state()
    counts = state.setdefault("counts", {})
    counts[provider] = counts.get(provider, 0) + 1
    _save_rate_state(state)


def _mark_provider_unavailable(provider: str) -> None:
    state = _load_rate_state()
    counts = state.setdefault("counts", {})
    counts[provider] = int(PROVIDERS[provider]["daily_limit"])
    _save_rate_state(state)


def _get_from_cache(cache_key: str, ttl_minutes: int) -> Optional[Dict[str, Any]]:
    if not RESPONSE_CACHE.exists():
        return None
    try:
        cache = json.loads(RESPONSE_CACHE.read_text(encoding="utf-8"))
        entry = cache.get(cache_key)
        if not entry:
            return None
        if (time.time() - float(entry["ts"])) / 60 > ttl_minutes:
            return None
        return entry["data"]
    except Exception:
        return None


def _save_to_cache(cache_key: str, data: Dict[str, Any]) -> None:
    cache: Dict[str, Any] = {}
    if RESPONSE_CACHE.exists():
        try:
            cache = json.loads(RESPONSE_CACHE.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            cache = {}
    cache[cache_key] = {"ts": time.time(), "data": data}
    if len(cache) > 100:
        oldest_key = min(cache, key=lambda key: cache[key]["ts"])
        del cache[oldest_key]
    _atomic_write(RESPONSE_CACHE, cache)


def _render_prompt(template: str, context: Dict[str, Any]) -> str:
    prepared: Dict[str, Any] = {}
    for key, value in context.items():
        if isinstance(value, (dict, list, tuple)):
            prepared[key] = json.dumps(value, ensure_ascii=False)
        else:
            prepared[key] = value
    try:
        return template.format(**prepared)
    except Exception:
        return f"{template}\n\nContext:\n{json.dumps(context, ensure_ascii=False, indent=2)}"


def _ollama_think_value() -> Any:
    raw = os.getenv("KIBOT_OLLAMA_THINK_LEVEL", "").strip().lower()
    if not raw:
        return False
    if raw in {"0", "false", "no", "off", "nothink"}:
        return False
    if raw in {"1", "true", "yes", "on", "think"}:
        return True
    return raw


def _call_provider(provider: str, prompt: str) -> Optional[str]:
    config = PROVIDERS[provider]
    api_key = _provider_api_key(provider)
    if not api_key:
        return None
    try:
        if provider == "ollama":
            url = config["base_url"]
            payload = {
                "model": config["model"],
                "messages": [{"role": "user", "content": prompt}],
                "stream": False,
                "format": "json",
                "keep_alive": os.getenv("KIBOT_OLLAMA_KEEP_ALIVE", "10m"),
                "options": {
                    "temperature": 0.2,
                },
            }
            payload["think"] = _ollama_think_value()
            headers = {
                "Content-Type": "application/json",
                "Authorization": f"Bearer {api_key}",
            }
        elif provider == "gemini":
            url = f"{config['base_url']}/{config['model']}:generateContent?key={api_key}"
            payload = {"contents": [{"parts": [{"text": prompt}]}]}
            headers = {"Content-Type": "application/json"}
        else:
            url = config["base_url"]
            if provider == "cohere":
                payload = {
                    "model": config["model"],
                    "message": prompt,
                    "temperature": 0.3,
                }
                headers = {"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"}
            else:
                payload = {
                    "model": config["model"],
                    "messages": [{"role": "user", "content": prompt}],
                    "max_tokens": 800,
                    "temperature": 0.3,
                }
                headers = {
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {api_key}",
                }
                if provider == "openrouter":
                    headers["HTTP-Referer"] = "https://github.com/frahmat68-beep/KiBot"
                    headers["X-Title"] = "KiBot"
        request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers)
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SEC) as response:
            data = json.loads(response.read())
            if provider == "ollama":
                return data.get("message", {}).get("content")
            if provider == "gemini":
                return data["candidates"][0]["content"]["parts"][0]["text"]
            if provider == "cohere":
                return data.get("text") or data.get("message", {}).get("content", [{}])[0].get("text")
            return data["choices"][0]["message"]["content"]
    except urllib.error.HTTPError as error:
        if error.code in {401, 403, 404, 429}:
            _mark_provider_unavailable(provider)
        return None
    except Exception:
        return None


def query_ai(prompt_type: str, context: Dict[str, Any], cache_ttl_minutes: int = 60, force_refresh: bool = False) -> Optional[Dict[str, Any]]:
    template = PROMPT_TEMPLATES.get(prompt_type, "Analyze this context:\n{context}")
    prompt = _render_prompt(template, context)
    data_hash = hashlib.md5(json.dumps(context, sort_keys=True).encode()).hexdigest()[:8]
    cache_key = f"{prompt_type}_{data_hash}"
    if not force_refresh:
        cached = _get_from_cache(cache_key, cache_ttl_minutes)
        if cached:
            return cached
    for provider in _candidate_providers(prompt_type):
        response = _call_provider(provider, prompt)
        if not response:
            continue
        _increment_usage(provider)
        try:
            parsed = json.loads(response)
        except Exception:
            start = response.find("{")
            end = response.rfind("}")
            parsed = json.loads(response[start : end + 1]) if start != -1 and end != -1 else {"raw": response}
        if isinstance(parsed, dict):
            parsed.setdefault("provider", provider)
            parsed.setdefault("model", str(PROVIDERS[provider]["model"]))
        _save_to_cache(cache_key, parsed)
        return parsed
    return None


def get_provider_status() -> Dict[str, Dict[str, Any]]:
    state = _load_rate_state()
    counts = state.get("counts", {})
    summary: Dict[str, Dict[str, Any]] = {}
    for name, config in PROVIDERS.items():
        used = counts.get(name, 0)
        limit = config["daily_limit"]
        summary[name] = {
            "configured": bool(_provider_api_key(name)),
            "model": str(config["model"]),
            "priority": int(config["priority"]),
            "used": used,
            "remaining": max(0, limit - used),
            "pct_used": round((used / limit) * 100, 1),
        }
    return summary


def get_rate_summary() -> Dict[str, Dict[str, Any]]:
    summary = get_provider_status()
    return {
        name: {
            "used": data.get("used", 0),
            "remaining": data.get("remaining", 0),
            "pct_used": data.get("pct_used", 0.0),
        }
        for name, data in summary.items()
    }


if __name__ == "__main__":
    print(json.dumps(get_provider_status(), indent=2))
