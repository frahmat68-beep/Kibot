from __future__ import annotations

import importlib.util
import calendar
import json
import logging
import os
import re
import threading
import time
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple
from urllib.parse import urlencode
from urllib.request import Request, urlopen


logger = logging.getLogger("KiBrain")
ROOT_DIR = Path(__file__).resolve().parent.parent

POSITIVE_HEADLINE_KEYWORDS = {
    "approval",
    "breakout",
    "bull",
    "gain",
    "greenlight",
    "growth",
    "inflow",
    "launch",
    "listing",
    "partnership",
    "rally",
    "record",
    "recovery",
    "surge",
    "upgrade",
}

NEGATIVE_HEADLINE_KEYWORDS = {
    "attack",
    "ban",
    "breach",
    "crackdown",
    "delist",
    "downtime",
    "dump",
    "exploit",
    "fraud",
    "hack",
    "halt",
    "investigation",
    "lawsuit",
    "liquidation",
    "loss",
    "outage",
    "risk",
    "scam",
    "selloff",
}


def _load_dotenv_early() -> None:
    candidates = [
        ROOT_DIR / ".env.kibot_manager",
        ROOT_DIR / ".env.kibot",
        ROOT_DIR / ".env.server",
        ROOT_DIR / ".env",
        ROOT_DIR / "scripts" / ".env",
        Path(".env.kibot_manager"),
        Path(".env.kibot"),
        Path(".env.server"),
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


_load_dotenv_early()

try:
    from kibot_ai_coordinator import (
        get_provider_status as _coordinator_provider_status_fn,
        query_ai as _coordinator_query_ai_fn,
    )
except Exception:
    _coordinator_provider_status_fn = None
    _coordinator_query_ai_fn = None


class BrainManager:
    """
    Advisory-only research helper.

    Design principles:
    - Never block the live entry hot path on external network calls.
    - Keep search/news usage bounded with short timeouts and long TTLs.
    - Prefer lightweight REST calls over heavy SDK dependencies on small servers.
    - Provide a compact, operator-readable snapshot of market context and progress
      toward the daily green target.
    """

    def __init__(self) -> None:
        state_root = Path(os.getenv("KIBOT_MANAGER_STATE_DIR", "state"))
        state_root.mkdir(parents=True, exist_ok=True)
        self.state_file = state_root / "brain_status.json"
        self.request_timeout = (
            float(os.getenv("KIBOT_BRAIN_CONNECT_TIMEOUT_SEC", "2.0")),
            float(os.getenv("KIBOT_BRAIN_READ_TIMEOUT_SEC", "4.0")),
        )
        self.review_ttl_sec = int(os.getenv("KIBOT_BRAIN_REVIEW_TTL_SEC", "900"))
        self.market_pulse_ttl_sec = int(os.getenv("KIBOT_BRAIN_MARKET_PULSE_TTL_SEC", "900"))
        self.tavily_ttl_sec = int(os.getenv("KIBOT_BRAIN_TAVILY_TTL_SEC", "7200"))
        self.serper_ttl_sec = int(os.getenv("KIBOT_BRAIN_SERPER_TTL_SEC", "5400"))
        self.ddg_ttl_sec = int(os.getenv("KIBOT_BRAIN_DDG_TTL_SEC", "3600"))
        self.finnhub_ttl_sec = int(os.getenv("KIBOT_BRAIN_FINNHUB_TTL_SEC", "900"))
        self.gemini_ttl_sec = int(os.getenv("KIBOT_BRAIN_GEMINI_TTL_SEC", "7200"))
        self.polymarket_ttl_sec = int(os.getenv("KIBOT_BRAIN_POLYMARKET_TTL_SEC", "90"))
        self.max_watch_symbols = max(1, int(os.getenv("KIBOT_BRAIN_MAX_WATCH_SYMBOLS", "5")))
        self.max_external_symbols = max(1, int(os.getenv("KIBOT_BRAIN_NEWS_MAX_SYMBOLS", "2")))
        self.green_target_daily_pct = float(os.getenv("KIBOT_GREEN_TARGET_DAILY_PCT", "0.003"))
        self.external_research_enabled = os.getenv("KIBOT_BRAIN_ENABLE_EXTERNAL_RESEARCH", "true").lower() == "true"
        self.ai_coordinator_enabled = os.getenv("KIBOT_BRAIN_ENABLE_AI_COORDINATOR", "true").lower() == "true"
        self.search_country = os.getenv("KIBOT_BRAIN_SEARCH_COUNTRY", "indonesia")
        self.search_lang = os.getenv("KIBOT_BRAIN_SEARCH_LANG", "id")
        self.gemini_model = os.getenv("KIBOT_BRAIN_GEMINI_MODEL", os.getenv("GEMINI_MODEL", "gemini-2.0-flash-lite"))
        self.polymarket_state_url = os.getenv("KIBOT_POLYMARKET_STATE_URL", "").strip()
        self._pair_cache: Dict[str, Dict[str, Any]] = {}
        self._provider_cache: Dict[str, Dict[str, Any]] = {}
        self._last_snapshot: Dict[str, Any] = self._load_snapshot()
        self._refresh_lock = threading.Lock()
        self._refresh_in_flight = False

    def _gemini_api_key(self) -> str:
        return (
            os.getenv("GEMINI_API_KEY")
            or os.getenv("GOOGLE_API_KEY")
            or os.getenv("GEMINI_SUPPORT_API_KEY")
            or ""
        )

    def get_market_intel(self, symbol: str) -> Dict[str, Any]:
        symbol = (symbol or "").strip().upper()
        if not symbol:
            return {"symbol": "", "ok": False, "reason": "empty_symbol"}

        now = time.time()
        cached = self._pair_cache.get(symbol)
        if cached and (now - float(cached.get("ts") or 0.0)) < self.review_ttl_sec:
            return dict(cached)

        pair = f"{symbol}USDT"
        binance = self._get_json(
            "https://api.binance.com/api/v3/ticker/24hr",
            params={"symbol": pair},
        )
        indodax_pairs = self._get_json("https://indodax.com/api/pairs")
        coingecko = self._get_json(
            "https://api.coingecko.com/api/v3/search",
            params={"query": symbol},
        )
        listed_on_indodax = self._listed_on_indodax(symbol, indodax_pairs)
        quote_volume = self._safe_float(binance.get("quoteVolume"))
        external_research = self._symbol_external_intel(symbol)
        intel = {
            "symbol": symbol,
            "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now)),
            "binance": binance,
            "indodax_pairs": indodax_pairs,
            "coingecko_search": coingecko,
            "listed_on_indodax": listed_on_indodax,
            "quote_volume_usdt": quote_volume,
            "external_research": external_research,
            "ok": True,
            "ts": now,
        }
        self._pair_cache[symbol] = intel
        return dict(intel)

    def vet_signal(self, symbol: str, tech_score: float) -> Tuple[bool, str]:
        """
        Advisory signal review for tests/manual audits.
        This must not be used as a blocking network call in the live hot path.
        """
        if tech_score < 0.60:
            return False, "technical_score_too_weak"

        intel = self.get_market_intel(symbol)
        if not intel.get("listed_on_indodax"):
            return False, "symbol_not_listed_on_indodax"

        quote_volume = self._safe_float(intel.get("quote_volume_usdt"))
        if quote_volume <= 0:
            return False, "missing_or_zero_quote_volume"

        research = intel.get("external_research") if isinstance(intel.get("external_research"), dict) else {}
        risk_bias = str(research.get("risk_bias") or "UNKNOWN")
        if risk_bias == "RISK_OFF" and tech_score < 0.75:
            return False, "external_research_risk_off"

        return True, "brain_advisory_ok"

    def think(
        self,
        watch_symbols: Optional[Iterable[str]] = None,
        context: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """
        Background connectivity / research pulse.
        Safe to run in a background thread with short network timeouts.
        """
        context = context or {}
        symbols = self._normalize_symbols(watch_symbols or self._default_watch_symbols())[: self.max_watch_symbols]
        market_pulse = self._get_market_pulse(symbols)
        snapshot = {
            "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "mode": "advisory_only",
            "provider_status": self._provider_status(),
            "ai_legion": self._ai_legion_status(),
            "optional_modules": self._optional_modules(),
            "internet_checks": {
                "binance": self._status_code("https://api.binance.com/api/v3/ping"),
                "bybit": self._status_code("https://api.bybit.com/v5/market/tickers?category=spot"),
                "kucoin": self._status_code("https://api.kucoin.com/api/v1/market/allTickers"),
                "mexc": self._status_code("https://api.mexc.com/api/v3/ticker/24hr"),
                "indodax": self._status_code("https://indodax.com/api/pairs"),
            },
            "daily_target": self._daily_target_snapshot(context),
            "market_pulse": market_pulse,
            "polymarket": self._get_polymarket_snapshot(),
            "watch_symbols": symbols,
            "watch_reviews": [],
        }
        snapshot["ai_critic"] = self._get_ai_critic(symbols, market_pulse, context)
        for symbol in symbols[: self.max_external_symbols]:
            intel = self.get_market_intel(symbol)
            approved, reason = self.vet_signal(symbol, 0.70)
            research = intel.get("external_research") if isinstance(intel.get("external_research"), dict) else {}
            watch_review = {
                "symbol": symbol,
                "approved": approved,
                "reason": reason,
                "listed_on_indodax": bool(intel.get("listed_on_indodax")),
                "quote_volume_usdt": round(self._safe_float(intel.get("quote_volume_usdt")), 2),
                "risk_bias": str(research.get("risk_bias") or "UNKNOWN"),
                "research_provider": str(research.get("provider") or "none"),
                "research_summary": str(research.get("summary") or "")[:280],
                "headline_count": len(list(research.get("headlines") or [])),
                "top_headlines": list(research.get("headlines") or [])[:3],
            }
            snapshot["watch_reviews"].append(watch_review)

        self._last_snapshot = snapshot
        self._write_snapshot(snapshot)
        return dict(snapshot)

    def snapshot(self) -> Dict[str, Any]:
        if self._last_snapshot:
            return dict(self._last_snapshot)
        self._last_snapshot = self._load_snapshot()
        return dict(self._last_snapshot)

    def snapshot_age_sec(self) -> Optional[float]:
        snapshot = self.snapshot()
        checked_at = str(snapshot.get("checked_at") or "").strip()
        if not checked_at:
            return None
        try:
            ts = time.strptime(checked_at, "%Y-%m-%dT%H:%M:%SZ")
            return max(0.0, time.time() - calendar.timegm(ts))
        except Exception:
            return None

    def ensure_warm(
        self,
        watch_symbols: Optional[Iterable[str]] = None,
        context: Optional[Dict[str, Any]] = None,
    ) -> bool:
        age = self.snapshot_age_sec()
        stale_after = max(60, int(self.market_pulse_ttl_sec))
        if age is not None and age < stale_after:
            return False
        with self._refresh_lock:
            if self._refresh_in_flight:
                return False
            self._refresh_in_flight = True

        def worker() -> None:
            try:
                self.think(watch_symbols=watch_symbols, context=context)
            except Exception as error:
                logger.warning("Brain async warm failed: %s", error)
            finally:
                with self._refresh_lock:
                    self._refresh_in_flight = False

        threading.Thread(target=worker, name="kibot-brain-warm", daemon=True).start()
        return True

    def _get_market_pulse(self, symbols: Sequence[str]) -> Dict[str, Any]:
        def loader() -> Dict[str, Any]:
            finnhub_news = self._get_finnhub_crypto_news()
            tavily_brief = self._get_tavily_market_brief()
            serper_brief = self._get_serper_market_brief() if not tavily_brief else {}
            ddg_brief = self._get_ddg_market_brief()

            top_headlines: List[str] = []
            for row in finnhub_news[:6]:
                if isinstance(row, dict):
                    headline = str(row.get("headline") or "").strip()
                    if headline:
                        top_headlines.append(headline)
            if tavily_brief.get("answer"):
                top_headlines.append(str(tavily_brief.get("answer")).strip())
            for item in list(tavily_brief.get("results") or [])[:2]:
                if isinstance(item, dict):
                    headline = str(item.get("title") or "").strip()
                    if headline:
                        top_headlines.append(headline)
            for item in list(serper_brief.get("organic") or [])[:2]:
                if isinstance(item, dict):
                    headline = str(item.get("title") or "").strip()
                    if headline:
                        top_headlines.append(headline)
            for item in list(ddg_brief.get("results") or [])[:2]:
                if isinstance(item, dict):
                    headline = str(item.get("title") or item.get("content") or "").strip()
                    if headline:
                        top_headlines.append(headline)

            deduped_headlines = self._dedupe_texts(top_headlines)[:5]
            positive_hits, negative_hits = self._sentiment_counts(deduped_headlines)
            if negative_hits > positive_hits + 1:
                risk_bias = "RISK_OFF"
            elif positive_hits > negative_hits + 1:
                risk_bias = "RISK_ON"
            else:
                risk_bias = "MIXED"

            summary = ""
            if tavily_brief.get("answer"):
                summary = str(tavily_brief.get("answer")).strip()
            elif serper_brief.get("organic"):
                first = serper_brief.get("organic")[0]
                if isinstance(first, dict):
                    summary = str(first.get("snippet") or first.get("title") or "").strip()
            elif deduped_headlines:
                summary = deduped_headlines[0]

            return {
                "risk_bias": risk_bias,
                "headline_count": len(finnhub_news),
                "top_headlines": deduped_headlines,
                "summary": summary[:320],
                "providers_used": [name for name, used in {
                    "finnhub": bool(finnhub_news),
                    "tavily": bool(tavily_brief),
                    "serper": bool(serper_brief),
                    "ddg": bool(ddg_brief),
                }.items() if used],
                "watch_symbols": list(symbols)[: self.max_external_symbols],
            }

        return self._cached_payload("market_pulse", self.market_pulse_ttl_sec, loader)

    def _extract_text_from_gemini_response(self, payload: Any) -> str:
        if not isinstance(payload, dict):
            return ""
        candidates = payload.get("candidates")
        if not isinstance(candidates, list) or not candidates:
            return ""
        first = candidates[0] if isinstance(candidates[0], dict) else {}
        content = first.get("content") if isinstance(first.get("content"), dict) else {}
        parts = content.get("parts")
        if not isinstance(parts, list) or not parts:
            return ""
        first_part = parts[0] if isinstance(parts[0], dict) else {}
        text = first_part.get("text")
        return str(text or "").strip()

    def _safe_json_from_text(self, text: str) -> Dict[str, Any]:
        raw = str(text or "").strip()
        if not raw:
            return {}
        if raw.startswith("```"):
            raw = re.sub(r"^```(?:json)?", "", raw, flags=re.IGNORECASE).strip()
            raw = raw[:-3].strip() if raw.endswith("```") else raw
        start = raw.find("{")
        end = raw.rfind("}")
        if start >= 0 and end > start:
            raw = raw[start:end + 1]
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, dict) else {}
        except Exception:
            return {}

    def _get_ai_critic(self, symbols: Sequence[str], market_pulse: Dict[str, Any], context: Dict[str, Any]) -> Dict[str, Any]:
        if not self.external_research_enabled:
            return {}
        risk_bias = str(market_pulse.get("risk_bias") or "UNKNOWN").upper()
        daily_pnl = f"{self._safe_float(context.get('daily_pnl_pct')):.4f}"
        cache_key = f"ai_critic:{risk_bias}:{daily_pnl}:{'-'.join(list(symbols)[:3])}"
        daily_target = self._daily_target_snapshot(context)
        polymarket = self._get_polymarket_snapshot()
        critic_context = {
            "watch_symbols": list(symbols)[:3],
            "market_pulse": {
                "risk_bias": market_pulse.get("risk_bias"),
                "headline_count": int(market_pulse.get("headline_count") or 0),
                "top_headlines": list(market_pulse.get("top_headlines") or [])[:3],
                "summary": str(market_pulse.get("summary") or "")[:240],
                "watch_symbols": list(market_pulse.get("watch_symbols") or [])[:3],
            },
            "daily_target": {
                "status": daily_target.get("status"),
                "gap_pct": daily_target.get("gap_pct"),
                "strategy_next": daily_target.get("strategy_next"),
                "capital_profile": (
                    {
                        "mode": (daily_target.get("capital_profile") or {}).get("mode"),
                        "reason": (daily_target.get("capital_profile") or {}).get("reason"),
                        "trading_allowed": (daily_target.get("capital_profile") or {}).get("trading_allowed"),
                        "max_position_idr": (daily_target.get("capital_profile") or {}).get("max_position_idr"),
                        "daily_loss_limit_pct": (daily_target.get("capital_profile") or {}).get("daily_loss_limit_pct"),
                    }
                    if isinstance(daily_target.get("capital_profile"), dict)
                    else {}
                ),
            },
            "capital_profile": {
                "mode": (context.get("capital_profile") or {}).get("mode"),
                "reason": (context.get("capital_profile") or {}).get("reason"),
                "trading_allowed": (context.get("capital_profile") or {}).get("trading_allowed"),
                "max_position_idr": (context.get("capital_profile") or {}).get("max_position_idr"),
                "daily_loss_limit_pct": (context.get("capital_profile") or {}).get("daily_loss_limit_pct"),
            },
            "polymarket": {
                "ready": polymarket.get("ready"),
                "execution_enabled": polymarket.get("execution_enabled"),
                "blocked": (polymarket.get("geoblock") or {}).get("blocked") if isinstance(polymarket.get("geoblock"), dict) else None,
                "country": (polymarket.get("geoblock") or {}).get("country") if isinstance(polymarket.get("geoblock"), dict) else None,
                "top_opportunities": [
                    {
                        "slug": item.get("slug"),
                        "spread": item.get("spread"),
                        "liquidity": item.get("liquidity"),
                    }
                    for item in list(polymarket.get("top_opportunities") or [])[:3]
                    if isinstance(item, dict)
                ],
            },
        }

        def loader() -> Dict[str, Any]:
            if self.ai_coordinator_enabled and _coordinator_query_ai_fn is not None:
                critic = _coordinator_query_ai_fn(
                    "BRAIN_CRITIC",
                    critic_context,
                    cache_ttl_minutes=max(1, int(self.gemini_ttl_sec / 60)),
                )
                if isinstance(critic, dict) and critic:
                    return critic

            if not self._gemini_api_key():
                return {}
            payload = {
                "contents": [
                    {
                        "parts": [
                            {
                                "text": (
                                    "You are a trading strategy critic. Return compact JSON only with keys "
                                    "{\"capital_posture\":\"DEFENSIVE|NEUTRAL|OPPORTUNISTIC\","
                                    "\"risk_bias\":\"RISK_OFF|MIXED|RISK_ON\","
                                    "\"confidence\":0.0,"
                                    "\"strategy_next\":\"...\","
                                    "\"focus_symbols\":[...],"
                                    "\"do_not_do\":[...]}"
                                    "\n\nContext:\n"
                                    f"- watch_symbols: {json.dumps(critic_context.get('watch_symbols'), ensure_ascii=False)}\n"
                                    f"- market_pulse: {json.dumps(critic_context.get('market_pulse'), ensure_ascii=False)}\n"
                                    f"- daily_target: {json.dumps(critic_context.get('daily_target'), ensure_ascii=False)}\n"
                                    f"- capital_profile: {json.dumps(critic_context.get('capital_profile'), ensure_ascii=False)}\n"
                                    f"- polymarket: {json.dumps(critic_context.get('polymarket'), ensure_ascii=False)}\n"
                                    "Rules:\n"
                                    "- keep risk controls strict\n"
                                    "- favor tiny-account survival if capital is small\n"
                                    "- only recommend aggressive posture when market pulse and local learning both support it\n"
                                    "- strategy_next must be one sentence\n"
                                )
                            }
                        ]
                    }
                ],
                "generationConfig": {
                    "temperature": 0.1,
                    "maxOutputTokens": 320,
                    "responseMimeType": "text/plain",
                },
            }
            base = os.getenv("GEMINI_API_URL", "https://generativelanguage.googleapis.com/v1beta").rstrip("/")
            api_key = self._gemini_api_key()
            url = f"{base}/models/{self.gemini_model}:generateContent"
            request = Request(
                url,
                data=json.dumps(payload).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "x-goog-api-key": api_key,
                    "User-Agent": "KiBot-Brain/1.0",
                },
            )
            with urlopen(request, timeout=max(self.request_timeout)) as response:
                raw = json.loads(response.read().decode("utf-8"))
            text = self._extract_text_from_gemini_response(raw)
            critic = self._safe_json_from_text(text)
            if critic:
                critic["provider"] = "gemini"
                critic["model"] = self.gemini_model
            return critic

        return self._cached_payload(cache_key, self.gemini_ttl_sec, loader)

    def _symbol_external_intel(self, symbol: str) -> Dict[str, Any]:
        def loader() -> Dict[str, Any]:
            news_hits = self._filter_news_for_symbol(symbol, self._get_finnhub_crypto_news())
            tavily_brief = self._get_tavily_symbol_brief(symbol)
            serper_brief = self._get_serper_symbol_brief(symbol) if not tavily_brief else {}
            ddg_brief = self._get_ddg_symbol_brief(symbol)

            texts: List[str] = []
            headlines = []
            for row in news_hits[:4]:
                headline = str(row.get("headline") or "").strip()
                if headline:
                    headlines.append(headline)
                    texts.append(headline)
                summary = str(row.get("summary") or "").strip()
                if summary:
                    texts.append(summary)
            if tavily_brief.get("answer"):
                texts.append(str(tavily_brief.get("answer")).strip())
            for item in list(tavily_brief.get("results") or [])[:2]:
                if isinstance(item, dict):
                    title = str(item.get("title") or "").strip()
                    content = str(item.get("content") or "").strip()
                    if title:
                        headlines.append(title)
                        texts.append(title)
                    if content:
                        texts.append(content)
            for item in list(serper_brief.get("organic") or [])[:2]:
                if isinstance(item, dict):
                    title = str(item.get("title") or "").strip()
                    snippet = str(item.get("snippet") or "").strip()
                    if title:
                        headlines.append(title)
                        texts.append(title)
                    if snippet:
                        texts.append(snippet)
            for item in list(ddg_brief.get("results") or [])[:2]:
                if isinstance(item, dict):
                    title = str(item.get("title") or "").strip()
                    snippet = str(item.get("content") or "").strip()
                    if title:
                        headlines.append(title)
                        texts.append(title)
                    if snippet:
                        texts.append(snippet)

            positive_hits, negative_hits = self._sentiment_counts(texts)
            if negative_hits > positive_hits + 1:
                risk_bias = "RISK_OFF"
            elif positive_hits > negative_hits + 1:
                risk_bias = "RISK_ON"
            else:
                risk_bias = "MIXED"

            summary = ""
            provider = "finnhub"
            if tavily_brief.get("answer"):
                summary = str(tavily_brief.get("answer")).strip()
                provider = "tavily"
            elif serper_brief.get("organic"):
                first = serper_brief.get("organic")[0]
                if isinstance(first, dict):
                    summary = str(first.get("snippet") or first.get("title") or "").strip()
                    provider = "serper"
            elif ddg_brief.get("results"):
                first = ddg_brief.get("results")[0]
                if isinstance(first, dict):
                    summary = str(first.get("content") or first.get("title") or "").strip()
                    provider = "ddg"
            elif headlines:
                summary = headlines[0]

            return {
                "provider": provider,
                "risk_bias": risk_bias,
                "headlines": self._dedupe_texts(headlines)[:3],
                "summary": summary[:320],
                "news_hit_count": len(news_hits),
            }

        return self._cached_payload(f"symbol_external:{symbol}", self.review_ttl_sec, loader)

    def _get_tavily_market_brief(self) -> Dict[str, Any]:
        if not self.external_research_enabled or not os.getenv("TAVILY_API_KEY"):
            return {}
        return self._cached_payload(
            "tavily_market",
            self.tavily_ttl_sec,
            lambda: self._post_json(
                "https://api.tavily.com/search",
                body={
                    "query": "crypto market today bitcoin altcoin risk catalysts regulation exploit exchange",
                    "topic": "news",
                    "search_depth": "basic",
                    "max_results": 3,
                    "include_answer": "basic",
                    "include_usage": True,
                },
                headers={"Authorization": f"Bearer {os.getenv('TAVILY_API_KEY', '')}"},
            ),
        )

    def _get_tavily_symbol_brief(self, symbol: str) -> Dict[str, Any]:
        if not self.external_research_enabled or not os.getenv("TAVILY_API_KEY"):
            return {}
        return self._cached_payload(
            f"tavily_symbol:{symbol}",
            self.tavily_ttl_sec,
            lambda: self._post_json(
                "https://api.tavily.com/search",
                body={
                    "query": f"{symbol} crypto latest news catalyst risk",
                    "topic": "news",
                    "search_depth": "basic",
                    "max_results": 3,
                    "include_answer": "basic",
                    "include_usage": True,
                },
                headers={"Authorization": f"Bearer {os.getenv('TAVILY_API_KEY', '')}"},
            ),
        )

    def _get_serper_market_brief(self) -> Dict[str, Any]:
        if not self.external_research_enabled or not os.getenv("SERPER_API_KEY"):
            return {}
        return self._cached_payload(
            "serper_market",
            self.serper_ttl_sec,
            lambda: self._post_json(
                "https://google.serper.dev/search",
                body={
                    "q": "crypto market today bitcoin altcoin risk catalysts",
                    "gl": "id",
                    "hl": self.search_lang,
                },
                headers={"X-API-KEY": os.getenv("SERPER_API_KEY", "")},
            ),
        )

    def _get_serper_symbol_brief(self, symbol: str) -> Dict[str, Any]:
        if not self.external_research_enabled or not os.getenv("SERPER_API_KEY"):
            return {}
        return self._cached_payload(
            f"serper_symbol:{symbol}",
            self.serper_ttl_sec,
            lambda: self._post_json(
                "https://google.serper.dev/search",
                body={
                    "q": f"{symbol} crypto latest news catalyst risk",
                    "gl": "id",
                    "hl": self.search_lang,
                },
                headers={"X-API-KEY": os.getenv("SERPER_API_KEY", "")},
            ),
        )

    def _get_ddg_market_brief(self) -> Dict[str, Any]:
        if not self.external_research_enabled or not self._has_ddg_client():
            return {}
        return self._cached_payload(
            "ddg_market",
            self.ddg_ttl_sec,
            lambda: self._ddg_search("crypto market today bitcoin altcoin risk catalysts exchange exploit", max_results=3),
        )

    def _get_ddg_symbol_brief(self, symbol: str) -> Dict[str, Any]:
        if not self.external_research_enabled or not self._has_ddg_client():
            return {}
        return self._cached_payload(
            f"ddg_symbol:{symbol}",
            self.ddg_ttl_sec,
            lambda: self._ddg_search(f"{symbol} crypto latest news catalyst risk", max_results=3),
        )

    def _get_finnhub_crypto_news(self) -> List[Dict[str, Any]]:
        if not self.external_research_enabled or not os.getenv("FINNHUB_API_KEY"):
            return []
        payload = self._cached_payload(
            "finnhub_crypto_news",
            self.finnhub_ttl_sec,
            lambda: self._get_json(
                "https://finnhub.io/api/v1/news",
                params={"category": "crypto", "token": os.getenv("FINNHUB_API_KEY", "")},
            ),
        )
        return payload if isinstance(payload, list) else []

    def _get_polymarket_snapshot(self) -> Dict[str, Any]:
        if not self.polymarket_state_url:
            return {}

        def loader() -> Dict[str, Any]:
            payload = self._request_json(self.polymarket_state_url)
            return payload if isinstance(payload, dict) else {}

        return self._cached_payload("polymarket_state", self.polymarket_ttl_sec, loader)

    def _provider_status(self) -> Dict[str, Dict[str, Any]]:
        out: Dict[str, Dict[str, Any]] = {}
        for name, env_key in (
            ("tavily", "TAVILY_API_KEY"),
            ("serper", "SERPER_API_KEY"),
            ("finnhub", "FINNHUB_API_KEY"),
        ):
            last = self._provider_cache.get(name) or {}
            out[name] = {
                "configured": bool(os.getenv(env_key)),
                "last_ok": bool(last.get("ok")) if last else None,
                "last_checked_at": last.get("checked_at"),
                "last_error": last.get("error", ""),
            }

        ddg_last = self._provider_cache.get("ddg") or {}
        out["ddg"] = {
            "configured": self._has_ddg_client(),
            "last_ok": bool(ddg_last.get("ok")) if ddg_last else None,
            "last_checked_at": ddg_last.get("checked_at"),
            "last_error": ddg_last.get("error", ""),
        }

        coordinator_status = self._coordinator_provider_status()
        for name, detail in coordinator_status.items():
            if not isinstance(detail, dict):
                continue
            out[name] = {
                "configured": bool(detail.get("configured")),
                "model": str(detail.get("model") or ""),
                "priority": detail.get("priority"),
                "used": detail.get("used"),
                "remaining": detail.get("remaining"),
                "pct_used": detail.get("pct_used"),
                "last_ok": out.get(name, {}).get("last_ok"),
                "last_checked_at": out.get(name, {}).get("last_checked_at"),
                "last_error": out.get(name, {}).get("last_error", ""),
            }
        return out

    def _ai_legion_status(self) -> Dict[str, Any]:
        provider_status = self._provider_status()
        configured = [name for name, detail in provider_status.items() if bool(detail.get("configured"))]
        llm_providers = {
            name: detail for name, detail in provider_status.items()
            if name in {"ollama", "groq", "gemini", "openrouter", "cohere", "jina", "nvidia"}
        }
        search_providers = {
            name: detail for name, detail in provider_status.items()
            if name in {"tavily", "serper", "finnhub", "ddg"}
        }
        return {
            "configured_count": len(configured),
            "configured_names": configured,
            "llm_providers": llm_providers,
            "search_providers": search_providers,
            "coordinator_enabled": self.ai_coordinator_enabled,
        }

    def _daily_target_snapshot(self, context: Dict[str, Any]) -> Dict[str, Any]:
        daily_pnl_pct = self._safe_float(context.get("daily_pnl_pct"))
        target_gap_pct = max(self.green_target_daily_pct - daily_pnl_pct, 0.0)
        capital_profile = context.get("capital_profile") if isinstance(context.get("capital_profile"), dict) else {}
        if daily_pnl_pct >= self.green_target_daily_pct:
            status = "AHEAD"
        elif daily_pnl_pct >= 0.0:
            status = "CHASING_GREEN"
        else:
            status = "RECOVERY_MODE"
        mode = str(capital_profile.get("mode") or "UNKNOWN")
        if mode in {"MICRO", "BUILDUP"}:
            strategy = "Trade smaller, favor liquid high-trust pairs, and require cleaner scanner confirmation."
        elif mode == "RECOVERY":
            strategy = "Freeze aggressive rotation, demand strong veto alignment, and prioritize capital protection."
        elif mode == "EXPANSION":
            strategy = "Keep discipline, but allow normal rotation when scanner and local flow confirm."
        else:
            strategy = "Stay selective and let market pulse decide whether to press or wait."
        return {
            "daily_pnl_pct": round(daily_pnl_pct, 4),
            "target_pct": round(self.green_target_daily_pct, 4),
            "gap_pct": round(target_gap_pct, 4),
            "status": status,
            "equity_idr": self._safe_float(context.get("equity_idr")),
            "free_cash_idr": self._safe_float(context.get("free_cash_idr")),
            "capital_profile": capital_profile,
            "strategy_next": strategy,
        }

    def _get_json(self, url: str, *, params: Optional[Dict[str, Any]] = None) -> Any:
        request_url = f"{url}?{urlencode(params)}" if params else url
        return self._request_json(request_url)

    def _post_json(self, url: str, *, body: Dict[str, Any], headers: Optional[Dict[str, str]] = None) -> Any:
        merged_headers = {
            "Content-Type": "application/json",
            "User-Agent": "KiBot-Brain/1.0",
        }
        if headers:
            merged_headers.update(headers)
        return self._request_json(url, body=body, headers=merged_headers)

    def _request_json(
        self,
        url: str,
        *,
        body: Optional[Dict[str, Any]] = None,
        headers: Optional[Dict[str, str]] = None,
    ) -> Any:
        request_headers = {"User-Agent": "KiBot-Brain/1.0"}
        if headers:
            request_headers.update(headers)
        data = json.dumps(body).encode("utf-8") if body is not None else None
        try:
            request = Request(url, data=data, headers=request_headers)
            with urlopen(request, timeout=max(self.request_timeout)) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as error:
            logger.warning("Brain fetch failed url=%s reason=%s", url, error)
            raise

    def _cached_payload(self, key: str, ttl_sec: int, loader) -> Any:
        now = time.time()
        cached = self._provider_cache.get(key)
        if cached and (now - float(cached.get("ts") or 0.0)) < ttl_sec:
            return cached.get("data")
        try:
            data = loader()
            self._provider_cache[key] = {
                "ts": now,
                "data": data,
                "ok": True,
                "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now)),
            }
            provider_root = key.split(":", 1)[0].split("_", 1)[0]
            self._provider_cache[provider_root] = {
                "ts": now,
                "ok": True,
                "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now)),
            }
            return data
        except Exception as error:
            cached_data = cached.get("data") if cached else {}
            provider_root = key.split(":", 1)[0].split("_", 1)[0]
            error_payload = {
                "ts": now,
                "data": cached_data,
                "ok": False,
                "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now)),
                "error": f"{type(error).__name__}: {error}",
            }
            self._provider_cache[key] = error_payload
            self._provider_cache[provider_root] = dict(error_payload)
            return cached_data

    def _status_code(self, url: str) -> int:
        try:
            request = Request(url, headers={"User-Agent": "KiBot-Brain/1.0"})
            with urlopen(request, timeout=max(self.request_timeout)) as response:
                return int(getattr(response, "status", 200))
        except Exception:
            return 0

    def _listed_on_indodax(self, symbol: str, pairs_payload: Any) -> bool:
        if not isinstance(pairs_payload, list):
            return False
        symbol = symbol.upper()
        for row in pairs_payload:
            if not isinstance(row, dict):
                continue
            quote = str(row.get("quote_currency") or row.get("base_currency") or "").lower()
            base = str(
                row.get("traded_currency")
                or row.get("traded_currency_unit")
                or row.get("base_currency")
                or ""
            ).upper()
            if base == symbol and quote == "idr":
                return True
        return False

    def _default_watch_symbols(self) -> Iterable[str]:
        raw = os.getenv("KIBOT_BRAIN_WATCH_SYMBOLS", "BTC,ETH,SOL")
        return [item.strip() for item in raw.split(",")]

    def _normalize_symbols(self, watch_symbols: Iterable[str]) -> List[str]:
        out: List[str] = []
        for raw in watch_symbols:
            text = str(raw or "").strip().upper()
            if not text:
                continue
            if "_" in text:
                text = text.split("_", 1)[0]
            if text.endswith("IDR"):
                text = text[:-3]
            if text.endswith("USDT"):
                text = text[:-4]
            if text and text not in out:
                out.append(text)
        return out

    def _filter_news_for_symbol(self, symbol: str, news_items: Sequence[Dict[str, Any]]) -> List[Dict[str, Any]]:
        symbol = symbol.upper()
        out: List[Dict[str, Any]] = []
        for row in news_items:
            if not isinstance(row, dict):
                continue
            related = str(row.get("related") or "").upper()
            headline = str(row.get("headline") or "").upper()
            summary = str(row.get("summary") or "").upper()
            if symbol in related or symbol in headline or symbol in summary:
                out.append(row)
        return out

    def _sentiment_counts(self, texts: Iterable[str]) -> Tuple[int, int]:
        positive_hits = 0
        negative_hits = 0
        for text in texts:
            lowered = str(text or "").lower()
            positive_hits += sum(1 for word in POSITIVE_HEADLINE_KEYWORDS if word in lowered)
            negative_hits += sum(1 for word in NEGATIVE_HEADLINE_KEYWORDS if word in lowered)
        return positive_hits, negative_hits

    def _dedupe_texts(self, texts: Iterable[str]) -> List[str]:
        seen = set()
        out: List[str] = []
        for text in texts:
            cleaned = str(text or "").strip()
            if not cleaned:
                continue
            key = cleaned.lower()
            if key in seen:
                continue
            seen.add(key)
            out.append(cleaned)
        return out

    def _safe_float(self, value: Any) -> float:
        try:
            number = float(value)
        except (TypeError, ValueError):
            return 0.0
        if number != number or number in (float("inf"), float("-inf")):
            return 0.0
        return number

    def _optional_modules(self) -> Dict[str, Dict[str, Any]]:
        def has_module(name: str) -> bool:
            try:
                return importlib.util.find_spec(name) is not None
            except ModuleNotFoundError:
                return False

        return {
            "ddgs": {
                "installed": has_module("ddgs"),
                "api_key_present": False,
            },
            "google.genai": {
                "installed": has_module("google.genai"),
                "api_key_present": bool(self._gemini_api_key()),
            },
            "google.generativeai": {
                "installed": has_module("google.generativeai"),
                "api_key_present": bool(self._gemini_api_key()),
                "legacy_sdk": True,
            },
            "tavily": {
                "installed": has_module("tavily"),
                "api_key_present": bool(os.getenv("TAVILY_API_KEY")),
            },
            "duckduckgo_search": {
                "installed": has_module("duckduckgo_search"),
                "api_key_present": False,
            },
            "finnhub": {
                "installed": has_module("finnhub"),
                "api_key_present": bool(os.getenv("FINNHUB_API_KEY")),
            },
            "numpy": {
                "installed": has_module("numpy"),
                "api_key_present": False,
            },
            "pandas": {
                "installed": has_module("pandas"),
                "api_key_present": False,
            },
            "talib": {
                "installed": has_module("talib"),
                "api_key_present": False,
            },
        }

    def _has_ddg_client(self) -> bool:
        for module_name in ("ddgs", "duckduckgo_search"):
            try:
                if importlib.util.find_spec(module_name) is not None:
                    return True
            except ModuleNotFoundError:
                continue
        return False

    def _ddg_search(self, query: str, max_results: int = 3) -> Dict[str, Any]:
        client_cls = None
        for module_name in ("ddgs", "duckduckgo_search"):
            try:
                module = __import__(module_name, fromlist=["DDGS"])
                client_cls = getattr(module, "DDGS", None)
                if client_cls is not None:
                    break
            except Exception:
                continue
        if client_cls is None:
            return {}

        client = None
        try:
            try:
                client = client_cls(timeout=max(self.request_timeout))
            except TypeError:
                client = client_cls()
            raw_results = client.text(query, max_results=max_results)
            results = []
            for item in list(raw_results or [])[:max_results]:
                if not isinstance(item, dict):
                    continue
                results.append(
                    {
                        "title": str(item.get("title") or "").strip(),
                        "content": str(item.get("body") or item.get("snippet") or "").strip(),
                        "url": str(item.get("href") or item.get("url") or "").strip(),
                    }
                )
            return {"query": query, "results": results}
        finally:
            closer = getattr(client, "close", None)
            if callable(closer):
                try:
                    closer()
                except Exception:
                    pass

    def _coordinator_provider_status(self) -> Dict[str, Dict[str, Any]]:
        if not self.ai_coordinator_enabled or _coordinator_provider_status_fn is None:
            return {}
        try:
            payload = _coordinator_provider_status_fn()
            return payload if isinstance(payload, dict) else {}
        except Exception as error:
            logger.warning("Brain coordinator status fetch failed: %s", error)
            return {}

    def _load_snapshot(self) -> Dict[str, Any]:
        if not self.state_file.exists():
            return {}
        try:
            return json.loads(self.state_file.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _write_snapshot(self, snapshot: Dict[str, Any]) -> None:
        tmp = self.state_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(self.state_file)
