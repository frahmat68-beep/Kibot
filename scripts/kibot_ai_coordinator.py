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
from typing import Any, Dict, Optional

ROOT = Path(os.getenv("KIBOT_RUNTIME_ROOT", Path(__file__).resolve().parent.parent))
STATE_DIR = ROOT / "state"
RATE_STATE_FILE = STATE_DIR / "ai_coordinator_rate.json"
RESPONSE_CACHE = STATE_DIR / "ai_coordinator_cache.json"

PROVIDERS = {
    "groq": {
        "daily_limit": 14400,
        "model": "llama-3.1-8b-instant",
        "api_key_env": "GROQ_API_KEY",
        "base_url": "https://api.groq.com/openai/v1/chat/completions",
        "priority": 1,
    },
    "gemini": {
        "daily_limit": 1500,
        "model": "gemini-2.0-flash-lite",
        "api_key_env": "GEMINI_API_KEY",
        "base_url": "https://generativelanguage.googleapis.com/v1beta/models",
        "priority": 2,
    },
    "openrouter": {
        "daily_limit": 200,
        "model": "meta-llama/llama-3.1-8b-instruct:free",
        "api_key_env": "OPENROUTER_API_KEY",
        "base_url": "https://openrouter.ai/api/v1/chat/completions",
        "priority": 3,
    },
}

PROMPT_TEMPLATES = {
    "VETO_ANALYSIS": (
        "Kamu adalah AI veto gate KiBot.\nSignal: {signal_data}\nMarket: {market_state}\nSystem: {system_health}\n"
        'Balas JSON {"approved": true/false, "reason": "...", "confidence": 0.0}'
    ),
    "INFRA_ANALYSIS": (
        "Analisis laporan infra Oracle Micro berikut, fokus hanya infrastruktur.\n{audit_report}\n"
        'Balas JSON {"critical_issues": [], "fixes": [], "optimization_tips": []}'
    ),
    "TRADE_POSTMORTEM": (
        "Analisis trade rugi berikut.\nTrade: {trade_data}\nMarket: {market_context}\nSystem: {system_state}\n"
        'Balas JSON {"root_cause": "...", "contributing_factors": [], "prevention": "...", "pattern": "..."}'
    ),
    "WEEKLY_SUMMARY": (
        "Buat insight mingguan untuk KiBot berdasarkan data berikut.\n{weekly_data}\n"
        'Balas JSON {"overall_assessment": "...", "top_issues": [], "opportunities_missed": [], "strengths": [], "recommendations": [], "next_week_focus": "..."}'
    ),
    "WHATIF_SIMULATION": (
        "Simulasikan skenario berikut.\nScenario: {scenario}\nContext: {context}\nParams: {params}\n"
        'Balas JSON {"likely_outcome": "...", "probability": 0.0, "risk_factors": [], "expected_pnl_pct": 0.0, "recommendation": "..."}'
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


def _get_best_provider() -> Optional[str]:
    state = _load_rate_state()
    counts = state.get("counts", {})
    for name, config in sorted(PROVIDERS.items(), key=lambda item: item[1]["priority"]):
        if counts.get(name, 0) >= config["daily_limit"]:
            continue
        if not os.getenv(config["api_key_env"], "").strip():
            continue
        return name
    return None


def _increment_usage(provider: str) -> None:
    state = _load_rate_state()
    counts = state.setdefault("counts", {})
    counts[provider] = counts.get(provider, 0) + 1
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


def _call_provider(provider: str, prompt: str) -> Optional[str]:
    config = PROVIDERS[provider]
    api_key = os.getenv(config["api_key_env"], "").strip()
    if not api_key:
        return None
    try:
        if provider == "gemini":
            url = f"{config['base_url']}/{config['model']}:generateContent?key={api_key}"
            payload = {"contents": [{"parts": [{"text": prompt}]}]}
            headers = {"Content-Type": "application/json"}
        else:
            url = config["base_url"]
            payload = {
                "model": config["model"],
                "messages": [{"role": "user", "content": prompt}],
                "max_tokens": 800,
                "temperature": 0.3,
            }
            headers = {"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"}
        request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers)
        with urllib.request.urlopen(request, timeout=15) as response:
            data = json.loads(response.read())
            if provider == "gemini":
                return data["candidates"][0]["content"]["parts"][0]["text"]
            return data["choices"][0]["message"]["content"]
    except urllib.error.HTTPError as error:
        if error.code == 429:
            state = _load_rate_state()
            state.setdefault("counts", {})[provider] = PROVIDERS[provider]["daily_limit"]
            _save_rate_state(state)
        return None
    except Exception:
        return None


def query_ai(prompt_type: str, context: Dict[str, Any], cache_ttl_minutes: int = 60, force_refresh: bool = False) -> Optional[Dict[str, Any]]:
    template = PROMPT_TEMPLATES.get(prompt_type, "Analyze this context:\n{context}")
    try:
        prompt = template.format(**context)
    except KeyError:
        prompt = template.format(context=json.dumps(context, indent=2, ensure_ascii=False))
    data_hash = hashlib.md5(json.dumps(context, sort_keys=True).encode()).hexdigest()[:8]
    cache_key = f"{prompt_type}_{data_hash}"
    if not force_refresh:
        cached = _get_from_cache(cache_key, cache_ttl_minutes)
        if cached:
            return cached
    provider = _get_best_provider()
    if not provider:
        return None
    response = _call_provider(provider, prompt)
    if not response:
        return None
    _increment_usage(provider)
    try:
        parsed = json.loads(response)
    except Exception:
        start = response.find("{")
        end = response.rfind("}")
        parsed = json.loads(response[start : end + 1]) if start != -1 and end != -1 else {"raw": response}
    _save_to_cache(cache_key, parsed)
    return parsed


def get_rate_summary() -> Dict[str, Dict[str, Any]]:
    state = _load_rate_state()
    counts = state.get("counts", {})
    summary: Dict[str, Dict[str, Any]] = {}
    for name, config in PROVIDERS.items():
        used = counts.get(name, 0)
        limit = config["daily_limit"]
        summary[name] = {"used": used, "remaining": max(0, limit - used), "pct_used": round((used / limit) * 100, 1)}
    return summary


if __name__ == "__main__":
    print(json.dumps(get_rate_summary(), indent=2))
