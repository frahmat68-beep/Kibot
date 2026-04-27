#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional

import requests

ROOT_DIR = Path(__file__).resolve().parent.parent


def _load_dotenv_early() -> None:
    candidates = [
        ROOT_DIR / ".env.polymarket",
        ROOT_DIR / ".env.kibot_manager",
        ROOT_DIR / ".env.kibot",
        ROOT_DIR / ".env.server",
        ROOT_DIR / ".env",
        Path(".env.polymarket"),
        Path(".env.kibot_manager"),
        Path(".env.kibot"),
        Path(".env.server"),
        Path(".env"),
        Path("../.env"),
    ]
    explicit = os.getenv("KIBOT_POLYMARKET_ENV_FILE")
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
    from eth_account import Account
except Exception:
    Account = None

try:
    from py_clob_client.client import ClobClient
    from py_clob_client.clob_types import BalanceAllowanceParams, MarketOrderArgs
except Exception:
    ClobClient = None
    BalanceAllowanceParams = None
    MarketOrderArgs = None


ROOT = Path(os.getenv("KIBOT_RUNTIME_ROOT", Path(__file__).resolve().parent.parent))
STATE_DIR = ROOT / "state"
STATE_DIR.mkdir(parents=True, exist_ok=True)
STATE_FILE = STATE_DIR / "polymarket_state.json"

BIND_HOST = os.getenv("KIBOT_POLYMARKET_BIND_HOST", "127.0.0.1")
BIND_PORT = int(os.getenv("KIBOT_POLYMARKET_BIND_PORT", "11600"))
REFRESH_SEC = int(os.getenv("KIBOT_POLYMARKET_REFRESH_SEC", "45"))
HTTP_TIMEOUT_SEC = float(os.getenv("KIBOT_POLYMARKET_TIMEOUT_SEC", "12"))
TOP_MARKETS = int(os.getenv("KIBOT_POLYMARKET_TOP_MARKETS", "12"))
MIN_LIQUIDITY = float(os.getenv("KIBOT_POLYMARKET_MIN_LIQUIDITY", "5000"))
MAX_SPREAD = float(os.getenv("KIBOT_POLYMARKET_MAX_SPREAD", "0.05"))
EXECUTION_ENABLED = os.getenv("KIBOT_POLYMARKET_ENABLE_LIVE_EXECUTION", "false").lower() == "true"
AUTH_TOKEN = (
    os.getenv("KIBOT_POLYMARKET_API_TOKEN")
    or os.getenv("KIBOT_OLLAMA_GATEWAY_TOKEN")
    or os.getenv("OLLAMA_API_KEY")
    or ""
).strip()

GEOBLOCK_URL = os.getenv("KIBOT_POLYMARKET_GEOBLOCK_URL", "https://polymarket.com/api/geoblock")
GAMMA_MARKETS_URL = os.getenv("KIBOT_POLYMARKET_GAMMA_URL", "https://gamma-api.polymarket.com/markets")
CLOB_HOST = os.getenv("KIBOT_POLYMARKET_CLOB_HOST", "https://clob-v2.polymarket.com")
SIMPLIFIED_MARKETS_URL = os.getenv("KIBOT_POLYMARKET_SIMPLIFIED_URL", f"{CLOB_HOST}/simplified-markets")
DATA_API_BASE_URL = os.getenv("KIBOT_POLYMARKET_DATA_API_URL", "https://data-api.polymarket.com")
POSITIONS_URL = os.getenv("KIBOT_POLYMARKET_POSITIONS_URL", f"{DATA_API_BASE_URL}/positions")
CLOSED_POSITIONS_URL = os.getenv("KIBOT_POLYMARKET_CLOSED_POSITIONS_URL", f"{DATA_API_BASE_URL}/closed-positions")
VALUE_URL = os.getenv("KIBOT_POLYMARKET_VALUE_URL", f"{DATA_API_BASE_URL}/value")
ACTIVITY_URL = os.getenv("KIBOT_POLYMARKET_ACTIVITY_URL", f"{DATA_API_BASE_URL}/activity")
ALCHEMY_RPC_URL = (
    os.getenv("ALCHEMY_URL")
    or os.getenv("POLYGON_MAINNET_RPC_URL")
    or os.getenv("POLYGON_RPC_URL")
    or ""
).strip()
PRIVATE_KEY = (
    os.getenv("KIBOT_POLYMARKET_PRIVATE_KEY")
    or os.getenv("POLYGON_KEY")
    or os.getenv("PHANTOM_WALLET_PRIVATE_KEY")
    or ""
).strip()
CHAIN_ID = int(os.getenv("KIBOT_POLYMARKET_CHAIN_ID", "137"))

_state_lock = threading.RLock()
_cached_state: Dict[str, Any] = {}


def _atomic_write(path: Path, payload: Dict[str, Any]) -> None:
    tmp = path.with_name(f"{path.name}.tmp.{os.getpid()}")
    with open(tmp, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(tmp, path)


def _safe_float(value: Any) -> float:
    try:
        if value in (None, ""):
            return 0.0
        return float(value)
    except Exception:
        return 0.0


def _safe_int(value: Any) -> int:
    try:
        if value in (None, ""):
            return 0
        return int(float(value))
    except Exception:
        return 0


def _ensure_list(value: Any) -> List[Any]:
    if isinstance(value, list):
        return value
    if isinstance(value, str):
        raw = value.strip()
        if raw.startswith("[") and raw.endswith("]"):
            try:
                parsed = json.loads(raw)
                return parsed if isinstance(parsed, list) else []
            except Exception:
                return []
    return []


ASSET_KEYWORDS: Dict[str, List[str]] = {
    "btc": ["btc", "bitcoin"],
    "eth": ["eth", "ethereum"],
    "sol": ["sol", "solana"],
    "xrp": ["xrp", "ripple"],
    "doge": ["doge", "dogecoin"],
    "bnb": ["bnb", "binance coin"],
    "ada": ["ada", "cardano"],
    "sui": ["sui"],
    "ltc": ["ltc", "litecoin"],
    "altseason": ["altseason", "alt season", "altcoin season"],
}

CRYPTO_CONTEXT_HINTS = (
    "bitcoin",
    "ethereum",
    "solana",
    "ripple",
    "dogecoin",
    "cardano",
    "litecoin",
    "binance",
    "crypto",
    "cryptocurrency",
    "token",
    "tokens",
    "coin",
    "coins",
    "altcoin",
    "blockchain",
    "defi",
    "etf",
    "spot etf",
    "sec",
    "wallet",
    "mainnet",
    "layer 1",
    "layer 2",
)

ASSET_REGEX: Dict[str, List[re.Pattern[str]]] = {
    asset: [
        re.compile(rf"(?<![a-z0-9]){re.escape(alias.lower())}(?![a-z0-9])")
        for alias in aliases
    ]
    for asset, aliases in ASSET_KEYWORDS.items()
}

BULLISH_HINTS = (
    "above",
    "reach",
    "hits",
    "hit",
    "surpass",
    "break",
    "approval",
    "approved",
    "bull",
    "rally",
    "ath",
    "up",
    "rise",
)

BEARISH_HINTS = (
    "below",
    "delay",
    "delayed",
    "ban",
    "hack",
    "exploit",
    "crash",
    "recession",
    "lawsuit",
    "depeg",
    "default",
    "fails",
    "failure",
    "selloff",
    "drops",
)


class PolymarketEngine:
    def __init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update({"User-Agent": "KiBot-Polymarket/1.0"})
        self._client: Optional[Any] = None
        self._client_lock = threading.RLock()

    def _request_json(self, url: str, *, params: Optional[Dict[str, Any]] = None, method: str = "GET", body: Optional[Dict[str, Any]] = None) -> Any:
        response = self.session.request(method, url, params=params, json=body, timeout=HTTP_TIMEOUT_SEC)
        response.raise_for_status()
        return response.json()

    def _rpc_call(self, method: str, params: List[Any]) -> Any:
        if not ALCHEMY_RPC_URL:
            return None
        payload = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
        response = self.session.post(ALCHEMY_RPC_URL, json=payload, timeout=HTTP_TIMEOUT_SEC)
        response.raise_for_status()
        data = response.json()
        if isinstance(data, dict) and data.get("error"):
            return None
        return data.get("result") if isinstance(data, dict) else None

    def wallet_address(self) -> str:
        if not PRIVATE_KEY or Account is None:
            return ""
        try:
            return str(Account.from_key(PRIVATE_KEY).address)
        except Exception:
            return ""

    def _native_balance_matic(self) -> Optional[float]:
        address = self.wallet_address()
        if not address:
            return None
        raw = self._rpc_call("eth_getBalance", [address, "latest"])
        if not raw:
            return None
        try:
            return int(raw, 16) / (10 ** 18)
        except Exception:
            return None

    def _build_client(self) -> Optional[Any]:
        if not PRIVATE_KEY or ClobClient is None:
            return None
        with self._client_lock:
            if self._client is not None:
                return self._client
            try:
                client = ClobClient(CLOB_HOST, chain_id=CHAIN_ID, key=PRIVATE_KEY)
                creds = client.create_or_derive_api_creds()
                client.set_api_creds(creds)
                self._client = client
            except Exception:
                self._client = None
            return self._client

    def _balance_allowance(self) -> Dict[str, Any]:
        client = self._build_client()
        if client is None or BalanceAllowanceParams is None:
            return {}
        try:
            payload = client.get_balance_allowance(BalanceAllowanceParams(asset_type="COLLATERAL"))
            return payload if isinstance(payload, dict) else {"raw": payload}
        except Exception as error:
            return {"error": str(error)}

    def _geoblock(self) -> Dict[str, Any]:
        try:
            payload = self._request_json(GEOBLOCK_URL)
            return payload if isinstance(payload, dict) else {}
        except Exception as error:
            return {"blocked": None, "error": str(error)}

    def _fetch_markets(self) -> List[Dict[str, Any]]:
        try:
            payload = self._request_json(
                GAMMA_MARKETS_URL,
                params={"active": "true", "closed": "false", "limit": "200"},
            )
            return [item for item in payload if isinstance(item, dict)] if isinstance(payload, list) else []
        except Exception:
            return []

    def _fetch_simplified_markets(self) -> List[Dict[str, Any]]:
        try:
            payload = self._request_json(SIMPLIFIED_MARKETS_URL)
            if isinstance(payload, dict):
                data = payload.get("data")
                return [item for item in data if isinstance(item, dict)] if isinstance(data, list) else []
            return [item for item in payload if isinstance(item, dict)] if isinstance(payload, list) else []
        except Exception:
            return []

    def _fetch_data_snapshot(self, url: str, *, address: str) -> Any:
        if not address:
            return {}
        try:
            return self._request_json(url, params={"user": address})
        except Exception:
            return {}

    def _market_score(self, market: Dict[str, Any]) -> float:
        liquidity = _safe_float(market.get("liquidity"))
        volume_24h = _safe_float(market.get("volume24hr"))
        spread = _safe_float(market.get("spread"))
        liquidity_score = min(liquidity / 150_000.0, 1.0)
        volume_score = min(volume_24h / 125_000.0, 1.0)
        spread_score = max(0.0, 1.0 - min(spread / max(MAX_SPREAD, 0.005), 1.0))
        reward_score = min(_safe_float(market.get("reward_daily_rate")) / 10.0, 1.0)
        momentum_score = min(abs(_safe_float(market.get("one_day_price_change"))) / 0.20, 1.0)
        return round(
            (liquidity_score * 0.32)
            + (volume_score * 0.28)
            + (spread_score * 0.20)
            + (reward_score * 0.10)
            + (momentum_score * 0.10),
            4,
        )

    def _maker_score(self, market: Dict[str, Any]) -> float:
        spread = _safe_float(market.get("spread"))
        reward_daily_rate = _safe_float(market.get("reward_daily_rate"))
        fees_enabled = bool(market.get("fees_enabled"))
        reward_spread_ok = spread <= max(_safe_float(market.get("reward_max_spread")) / 100.0, MAX_SPREAD)
        liquidity = _safe_float(market.get("liquidity"))
        volume_24h = _safe_float(market.get("volume24hr"))
        return round(
            (0.35 if fees_enabled else 0.0)
            + (0.20 if reward_daily_rate > 0 else 0.0)
            + (0.15 if reward_spread_ok else 0.0)
            + min(liquidity / 200_000.0, 0.20)
            + min(volume_24h / 200_000.0, 0.10),
            4,
        )

    def _asset_signal(self, market: Dict[str, Any]) -> Dict[str, Any]:
        question = str(market.get("question") or "").lower()
        description = str(market.get("description") or "").lower()
        category = str(market.get("category") or "").lower()
        tags = " ".join(str(item).lower() for item in _ensure_list(market.get("tags")))
        event_titles = " ".join(str(item.get("title") or "").lower() for item in _ensure_list(market.get("events")) if isinstance(item, dict))
        event_descriptions = " ".join(
            str(item.get("description") or "").lower() for item in _ensure_list(market.get("events")) if isinstance(item, dict)
        )
        context_blob = " ".join(part for part in [question, description, category, tags, event_titles, event_descriptions] if part)
        has_crypto_context = any(token in context_blob for token in CRYPTO_CONTEXT_HINTS)
        matched_asset = ""
        for asset, aliases in ASSET_KEYWORDS.items():
            patterns = ASSET_REGEX.get(asset) or []
            if any(pattern.search(question) for pattern in patterns):
                matched_asset = asset
                break
        if not matched_asset:
            return {}
        strong_alias_present = any(
            len(alias) > 3 and alias in context_blob
            for alias in ASSET_KEYWORDS.get(matched_asset, [])
        )
        price_context = any(token in question for token in ("$", "price", "priced", "market cap", "etf", "all-time high"))
        if not (has_crypto_context or strong_alias_present or price_context):
            return {}

        yes_prob = _safe_float(market.get("implied_prob_yes"))
        conviction = min(abs(yes_prob - 0.5) * 2.0, 1.0)
        bullish = any(token in question for token in BULLISH_HINTS)
        bearish = any(token in question for token in BEARISH_HINTS)
        direction = ""
        if bullish and yes_prob >= 0.55:
            direction = "LONG"
        elif bullish and yes_prob <= 0.45:
            direction = "SHORT"
        elif bearish and yes_prob >= 0.55:
            direction = "SHORT"
        elif bearish and yes_prob <= 0.45:
            direction = "LONG"
        if not direction:
            return {}

        score = round(
            min(
                1.0,
                (conviction * 0.55)
                + min(_safe_float(market.get("liquidity")) / 250_000.0, 0.20)
                + min(_safe_float(market.get("volume24hr")) / 250_000.0, 0.20)
                + min(abs(_safe_float(market.get("one_day_price_change"))) / 0.10, 0.05),
            ),
            4,
        )
        return {
            "asset": matched_asset,
            "direction": direction,
            "score": score,
            "question": str(market.get("question") or ""),
            "mapped_pair": "" if matched_asset == "altseason" else f"{matched_asset}_idr",
        }

    def _normalize_market(self, market: Dict[str, Any]) -> Dict[str, Any]:
        token_ids = _ensure_list(market.get("clobTokenIds"))
        outcomes = _ensure_list(market.get("outcomes"))
        best_ask = _safe_float(market.get("bestAsk"))
        best_bid = _safe_float(market.get("bestBid"))
        spread = _safe_float(market.get("spread"))
        if spread <= 0 and best_ask > 0 and best_bid > 0:
            spread = max(best_ask - best_bid, 0.0)
        outcome_prices = _ensure_list(market.get("outcomePrices"))
        outcome_price_yes = _safe_float(outcome_prices[0] if outcome_prices else 0.0)
        midpoint = 0.0
        if best_bid > 0 and best_ask > 0:
            midpoint = (best_bid + best_ask) / 2.0
        elif outcome_price_yes > 0:
            midpoint = outcome_price_yes
        reward_daily_rate = sum(_safe_float(item.get("rewardsDailyRate")) for item in _ensure_list(market.get("clobRewards")))
        reward_min_size = _safe_float(market.get("rewardsMinSize"))
        reward_max_spread = _safe_float(market.get("rewardsMaxSpread"))
        normalized = {
            "question": str(market.get("question") or ""),
            "slug": str(market.get("slug") or market.get("questionID") or ""),
            "category": str(market.get("category") or ""),
            "description": str(market.get("description") or ""),
            "tags": _ensure_list(market.get("tags")),
            "events": _ensure_list(market.get("events")),
            "condition_id": str(market.get("conditionId") or ""),
            "yes_token_id": str(token_ids[0]) if len(token_ids) >= 1 else "",
            "no_token_id": str(token_ids[1]) if len(token_ids) >= 2 else "",
            "yes_outcome": str(outcomes[0]) if len(outcomes) >= 1 else "YES",
            "no_outcome": str(outcomes[1]) if len(outcomes) >= 2 else "NO",
            "best_bid": best_bid,
            "best_ask": best_ask,
            "spread": spread,
            "liquidity": _safe_float(market.get("liquidityClob") or market.get("liquidity")),
            "volume24hr": _safe_float(market.get("volume24hr") or market.get("volume24h")),
            "volume1wk": _safe_float(market.get("volume1wk") or market.get("volume1wkClob")),
            "volume1mo": _safe_float(market.get("volume1mo") or market.get("volume1moClob")),
            "one_day_price_change": _safe_float(market.get("oneDayPriceChange")),
            "one_week_price_change": _safe_float(market.get("oneWeekPriceChange")),
            "one_month_price_change": _safe_float(market.get("oneMonthPriceChange")),
            "fees_enabled": bool(market.get("feesEnabled")),
            "order_min_size": _safe_float(market.get("orderMinSize")),
            "min_tick": _safe_float(market.get("orderPriceMinTickSize")),
            "midpoint": round(midpoint, 4),
            "implied_prob_yes": round(midpoint, 4),
            "last_trade_price": _safe_float(market.get("lastTradePrice")),
            "reward_daily_rate": reward_daily_rate,
            "reward_min_size": reward_min_size,
            "reward_max_spread": reward_max_spread,
            "end_date": str(market.get("endDate") or ""),
            "accepting_orders": bool(market.get("acceptingOrders")),
            "active": bool(market.get("active")),
            "closed": bool(market.get("closed")),
        }
        normalized["score"] = self._market_score(normalized)
        normalized["maker_score"] = self._maker_score(normalized)
        normalized["asset_signal"] = self._asset_signal(normalized)
        normalized["alpha_score"] = round(
            normalized["score"] * 0.60 + _safe_float((normalized["asset_signal"] or {}).get("score")) * 0.40,
            4,
        )
        normalized["execution_style"] = (
            "MAKER_REBATE"
            if normalized["maker_score"] >= 0.55 and normalized["fees_enabled"]
            else ("PASSIVE_REWARD" if normalized["maker_score"] >= 0.45 else "OBSERVE")
        )
        return normalized

    def _normalized_markets(self, markets: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        normalized: List[Dict[str, Any]] = []
        for market in markets:
            if not market.get("active") or market.get("closed") or not market.get("acceptingOrders"):
                continue
            liquidity = _safe_float(market.get("liquidityClob") or market.get("liquidity"))
            if liquidity < MIN_LIQUIDITY:
                continue
            spread = _safe_float(market.get("spread"))
            best_ask = _safe_float(market.get("bestAsk"))
            best_bid = _safe_float(market.get("bestBid"))
            if spread <= 0 and best_ask > 0 and best_bid > 0:
                spread = max(best_ask - best_bid, 0.0)
            if spread > MAX_SPREAD:
                continue
            normalized.append(self._normalize_market(market))
        return normalized

    def _top_opportunities(self, markets: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        return sorted(markets, key=lambda item: item.get("score") or 0.0, reverse=True)[:TOP_MARKETS]

    def _maker_candidates(self, markets: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        candidates = [
            {
                "question": item.get("question"),
                "slug": item.get("slug"),
                "condition_id": item.get("condition_id"),
                "maker_score": item.get("maker_score"),
                "reward_daily_rate": item.get("reward_daily_rate"),
                "fees_enabled": item.get("fees_enabled"),
                "spread": item.get("spread"),
                "liquidity": item.get("liquidity"),
                "execution_style": item.get("execution_style"),
            }
            for item in markets
            if item.get("maker_score", 0.0) >= 0.35
        ]
        return sorted(candidates, key=lambda item: item.get("maker_score") or 0.0, reverse=True)[:TOP_MARKETS]

    def _alpha_candidates(self, markets: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        candidates: List[Dict[str, Any]] = []
        for item in markets:
            signal = item.get("asset_signal") if isinstance(item.get("asset_signal"), dict) else {}
            if not signal:
                continue
            candidates.append(
                {
                    "question": item.get("question"),
                    "slug": item.get("slug"),
                    "condition_id": item.get("condition_id"),
                    "asset": signal.get("asset"),
                    "mapped_pair": signal.get("mapped_pair"),
                    "direction": signal.get("direction"),
                    "signal_score": signal.get("score"),
                    "alpha_score": item.get("alpha_score"),
                    "implied_prob_yes": item.get("implied_prob_yes"),
                    "spread": item.get("spread"),
                    "liquidity": item.get("liquidity"),
                    "volume24hr": item.get("volume24hr"),
                }
            )
        return sorted(candidates, key=lambda item: item.get("alpha_score") or 0.0, reverse=True)[:TOP_MARKETS]

    def _cross_market_bias(self, markets: List[Dict[str, Any]]) -> Dict[str, Any]:
        aggregate: Dict[str, Dict[str, Any]] = {}
        for item in markets:
            signal = item.get("asset_signal") if isinstance(item.get("asset_signal"), dict) else {}
            asset = str(signal.get("asset") or "")
            direction = str(signal.get("direction") or "")
            score = _safe_float(signal.get("score"))
            if not asset or not direction or score <= 0:
                continue
            weight = score if direction == "LONG" else -score
            entry = aggregate.setdefault(
                asset,
                {
                    "net_score": 0.0,
                    "count": 0,
                    "top_questions": [],
                    "mapped_pairs": [],
                },
            )
            entry["net_score"] += weight
            entry["count"] += 1
            entry["top_questions"].append(str(item.get("question") or ""))
            mapped_pair = str(signal.get("mapped_pair") or "")
            if mapped_pair and mapped_pair not in entry["mapped_pairs"]:
                entry["mapped_pairs"].append(mapped_pair)
        out: Dict[str, Any] = {}
        for asset, entry in aggregate.items():
            net_score = float(entry.get("net_score") or 0.0)
            out[asset] = {
                "direction": "LONG" if net_score >= 0 else "SHORT",
                "score": round(min(abs(net_score), 1.0), 4),
                "count": int(entry.get("count") or 0),
                "top_questions": list(entry.get("top_questions") or [])[:3],
                "mapped_pairs": list(entry.get("mapped_pairs") or [])[:3],
            }
        return out

    def _wallet_summary(self, address: str) -> Dict[str, Any]:
        positions = self._fetch_data_snapshot(POSITIONS_URL, address=address)
        closed_positions = self._fetch_data_snapshot(CLOSED_POSITIONS_URL, address=address)
        value = self._fetch_data_snapshot(VALUE_URL, address=address)
        activity = self._fetch_data_snapshot(ACTIVITY_URL, address=address)
        position_rows = positions if isinstance(positions, list) else []
        closed_rows = closed_positions if isinstance(closed_positions, list) else []
        activity_rows = activity if isinstance(activity, list) else []
        return {
            "open_positions": len(position_rows),
            "closed_positions": len(closed_rows),
            "recent_activity": len(activity_rows),
            "position_value": value if isinstance(value, dict) else {},
            "top_positions": position_rows[:5],
        }

    def _ops_alerts(self, *, geoblock: Dict[str, Any], wallet_summary: Dict[str, Any], native_balance: Optional[float], cross_market_bias: Dict[str, Any]) -> List[str]:
        alerts: List[str] = []
        if bool(geoblock.get("blocked")):
            alerts.append("polymarket geoblock active")
        if EXECUTION_ENABLED and (native_balance or 0.0) < 0.02:
            alerts.append("polygon gas balance is low")
        if EXECUTION_ENABLED and int(wallet_summary.get("open_positions") or 0) >= 8:
            alerts.append("open polymarket positions already dense")
        if not cross_market_bias:
            alerts.append("no strong cross-market bias detected")
        return alerts[:4]

    def refresh_state(self) -> Dict[str, Any]:
        geoblock = self._geoblock()
        gamma_markets = self._fetch_markets()
        simplified_markets = self._fetch_simplified_markets()
        wallet_address = self.wallet_address()
        native_balance = self._native_balance_matic()
        balance_allowance = self._balance_allowance()
        normalized_markets = self._normalized_markets(gamma_markets)
        top_opportunities = self._top_opportunities(normalized_markets)
        maker_candidates = self._maker_candidates(normalized_markets)
        alpha_candidates = self._alpha_candidates(normalized_markets)
        cross_market_bias = self._cross_market_bias(normalized_markets)
        wallet_summary = self._wallet_summary(wallet_address)
        ops_alerts = self._ops_alerts(
            geoblock=geoblock,
            wallet_summary=wallet_summary,
            native_balance=native_balance,
            cross_market_bias=cross_market_bias,
        )
        state = {
            "ok": True,
            "service": "kibot-polymarket",
            "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "ready": bool(wallet_address) and not bool(geoblock.get("blocked")),
            "analysis_ready": bool(normalized_markets),
            "execution_enabled": EXECUTION_ENABLED,
            "sdk_ready": bool(self._build_client()),
            "wallet_address": wallet_address,
            "native_balance_matic": native_balance,
            "wallet_summary": wallet_summary,
            "geoblock": geoblock,
            "gamma_market_count": len(gamma_markets),
            "simplified_market_count": len(simplified_markets),
            "top_opportunities": top_opportunities,
            "maker_candidates": maker_candidates,
            "alpha_candidates": alpha_candidates,
            "cross_market_bias": cross_market_bias,
            "top_markets": [str(item.get("question") or "") for item in top_opportunities[:6]],
            "ops_alerts": ops_alerts,
            "balance_allowance": balance_allowance,
            "clob_host": CLOB_HOST,
        }
        with _state_lock:
            _cached_state.clear()
            _cached_state.update(state)
        _atomic_write(STATE_FILE, state)
        return state

    def place_market_order(self, token_id: str, side: str, amount: float, price: float = 0.0, order_type: str = "FAK") -> Dict[str, Any]:
        if not EXECUTION_ENABLED:
            raise RuntimeError("polymarket execution disabled")
        client = self._build_client()
        if client is None or MarketOrderArgs is None:
            raise RuntimeError("polymarket sdk unavailable")
        payload = MarketOrderArgs(
            token_id=str(token_id),
            amount=float(amount),
            side=str(side).upper(),
            price=float(price or 0.0),
            order_type=str(order_type).upper(),
        )
        order = client.create_market_order(payload)
        result = client.post_order(order, str(order_type).upper())
        return result if isinstance(result, dict) else {"raw": result}


ENGINE = PolymarketEngine()


def _state_payload() -> Dict[str, Any]:
    with _state_lock:
        if _cached_state:
            return dict(_cached_state)
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return ENGINE.refresh_state()


class Handler(BaseHTTPRequestHandler):
    server_version = "KiBotPolymarket/1.0"

    def _json(self, code: int, payload: Dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        if not AUTH_TOKEN:
            return False
        return self.headers.get("Authorization", "").strip() == f"Bearer {AUTH_TOKEN}"

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            state = _state_payload()
            self._json(HTTPStatus.OK if state.get("ready") else HTTPStatus.BAD_GATEWAY, state)
            return
        if self.path == "/api/state":
            self._json(HTTPStatus.OK, _state_payload())
            return
        self._json(HTTPStatus.NOT_FOUND, {"ok": False, "error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path == "/api/refresh":
            if not self._authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"ok": False, "error": "unauthorized"})
                return
            try:
                self._json(HTTPStatus.OK, ENGINE.refresh_state())
            except Exception as error:
                self._json(HTTPStatus.INTERNAL_SERVER_ERROR, {"ok": False, "error": str(error)})
            return
        if self.path == "/api/order":
            if not self._authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"ok": False, "error": "unauthorized"})
                return
            length = int(self.headers.get("Content-Length", "0") or 0)
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                payload = json.loads(body or "{}")
                result = ENGINE.place_market_order(
                    token_id=str(payload.get("token_id") or ""),
                    side=str(payload.get("side") or "BUY"),
                    amount=float(payload.get("amount") or 0.0),
                    price=float(payload.get("price") or 0.0),
                    order_type=str(payload.get("order_type") or "FAK"),
                )
                self._json(HTTPStatus.OK, {"ok": True, "result": result})
            except Exception as error:
                self._json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": str(error)})
            return
        self._json(HTTPStatus.NOT_FOUND, {"ok": False, "error": "not_found"})

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[POLYMARKET] {self.address_string()} {fmt % args}", flush=True)


def _refresh_loop() -> None:
    while True:
        try:
            state = ENGINE.refresh_state()
            top = state.get("top_opportunities") if isinstance(state.get("top_opportunities"), list) else []
            print(
                f"[POLYMARKET] refresh ready={state.get('ready')} markets={state.get('gamma_market_count')} top={len(top)}",
                flush=True,
            )
        except Exception as error:
            print(f"[POLYMARKET][ERROR] refresh failed: {error}", flush=True)
        time.sleep(max(15, REFRESH_SEC))


def main() -> None:
    ENGINE.refresh_state()
    threading.Thread(target=_refresh_loop, name="kibot-polymarket-refresh", daemon=True).start()
    server = ThreadingHTTPServer((BIND_HOST, BIND_PORT), Handler)
    print(f"[POLYMARKET] listening on {BIND_HOST}:{BIND_PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
