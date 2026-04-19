from __future__ import annotations

import importlib.util
import json
import logging
import os
import time
from pathlib import Path
from typing import Any, Dict, Iterable, Optional, Tuple
from urllib.parse import urlencode
from urllib.request import Request, urlopen


logger = logging.getLogger("KiBrain")


def _load_dotenv_early() -> None:
    candidates = [Path(".env"), Path("scripts/.env"), Path("../.env")]
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


class BrainManager:
    """
    Advisory-only research helper.

    Rules:
    - Never block the live trading hot path on external network calls.
    - Keep all internet access bounded by short timeouts.
    - Persist a lightweight heartbeat/status file so operators can see whether
      research connectivity is alive.
    """

    def __init__(self) -> None:
        state_root = Path(os.getenv("KIBOT_MANAGER_STATE_DIR", "state"))
        state_root.mkdir(parents=True, exist_ok=True)
        self.state_file = state_root / "brain_status.json"
        self.request_timeout = (
            float(os.getenv("KIBOT_BRAIN_CONNECT_TIMEOUT_SEC", "2.0")),
            float(os.getenv("KIBOT_BRAIN_READ_TIMEOUT_SEC", "4.0")),
        )
        self.review_ttl_sec = int(os.getenv("KIBOT_BRAIN_REVIEW_TTL_SEC", "300"))
        self._pair_cache: Dict[str, Dict[str, Any]] = {}
        self._last_snapshot: Dict[str, Any] = self._load_snapshot()

    def get_market_intel(self, symbol: str) -> Dict[str, Any]:
        symbol = (symbol or "").strip().upper()
        if not symbol:
            return {"symbol": "", "ok": False, "reason": "empty_symbol"}

        now = time.time()
        cached = self._pair_cache.get(symbol)
        if cached and (now - float(cached.get("ts") or 0.0)) < self.review_ttl_sec:
            return dict(cached)

        pair = f"{symbol}USDT"
        intel = {
            "symbol": symbol,
            "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now)),
            "binance": self._get_json(
                "https://api.binance.com/api/v3/ticker/24hr",
                params={"symbol": pair},
            ),
            "indodax_pairs": self._get_json("https://indodax.com/api/pairs"),
            "coingecko_search": self._get_json(
                "https://api.coingecko.com/api/v3/search",
                params={"query": symbol},
            ),
            "ts": now,
        }
        intel["ok"] = True
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
        listed_on_indodax = self._listed_on_indodax(symbol, intel.get("indodax_pairs"))
        if not listed_on_indodax:
            return False, "symbol_not_listed_on_indodax"

        binance = intel.get("binance") or {}
        try:
            quote_volume = float(binance.get("quoteVolume") or 0.0)
        except (TypeError, ValueError):
            quote_volume = 0.0
        if quote_volume <= 0:
            return False, "missing_or_zero_quote_volume"

        return True, "brain_advisory_ok"

    def think(self, watch_symbols: Optional[Iterable[str]] = None) -> Dict[str, Any]:
        """
        Background connectivity / research pulse.
        Safe to run in a background thread with short network timeouts.
        """
        symbols = [s.strip().upper() for s in (watch_symbols or self._default_watch_symbols()) if str(s).strip()]
        snapshot = {
            "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "mode": "advisory_only",
            "optional_modules": self._optional_modules(),
            "internet_checks": {
                "binance": self._status_code("https://api.binance.com/api/v3/ping"),
                "bybit": self._status_code("https://api.bybit.com/v5/market/tickers?category=spot"),
                "kucoin": self._status_code("https://api.kucoin.com/api/v1/market/allTickers"),
                "mexc": self._status_code("https://api.mexc.com/api/v3/ticker/24hr"),
                "indodax": self._status_code("https://indodax.com/api/pairs"),
            },
            "watch_symbols": symbols[:5],
            "watch_reviews": [],
        }
        for symbol in symbols[:5]:
            approved, reason = self.vet_signal(symbol, 0.7)
            snapshot["watch_reviews"].append(
                {
                    "symbol": symbol,
                    "approved": approved,
                    "reason": reason,
                }
            )

        self._last_snapshot = snapshot
        self._write_snapshot(snapshot)
        return dict(snapshot)

    def snapshot(self) -> Dict[str, Any]:
        if self._last_snapshot:
            return dict(self._last_snapshot)
        self._last_snapshot = self._load_snapshot()
        return dict(self._last_snapshot)

    def _get_json(self, url: str, *, params: Optional[Dict[str, Any]] = None) -> Any:
        try:
            request_url = f"{url}?{urlencode(params)}" if params else url
            request = Request(request_url, headers={"User-Agent": "KiBot-Brain/1.0"})
            with urlopen(request, timeout=max(self.request_timeout)) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as error:
            logger.warning("Brain fetch failed url=%s reason=%s", url, error)
            return {}

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

    def _optional_modules(self) -> Dict[str, Dict[str, Any]]:
        def has_module(name: str) -> bool:
            return importlib.util.find_spec(name) is not None

        return {
            "google.generativeai": {
                "installed": has_module("google.generativeai"),
                "api_key_present": bool(os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")),
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
