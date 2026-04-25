#!/usr/bin/env python3
from __future__ import annotations

import json
import os
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
        ROOT_DIR / "scripts" / ".env",
        Path(".env.polymarket"),
        Path(".env.kibot_manager"),
        Path(".env.kibot"),
        Path(".env.server"),
        Path(".env"),
        Path("scripts/.env"),
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
            return [item for item in payload if isinstance(item, dict)] if isinstance(payload, list) else []
        except Exception:
            return []

    def _market_score(self, market: Dict[str, Any]) -> float:
        liquidity = _safe_float(market.get("liquidityClob") or market.get("liquidity"))
        volume_24h = _safe_float(market.get("volume24hr") or market.get("volume24h"))
        spread = _safe_float(market.get("spread"))
        best_ask = _safe_float(market.get("bestAsk"))
        best_bid = _safe_float(market.get("bestBid"))
        if spread <= 0 and best_ask > 0 and best_bid > 0:
            spread = max(best_ask - best_bid, 0.0)
        depth_score = min(liquidity / 100_000.0, 2.5)
        volume_score = min(volume_24h / 100_000.0, 2.5)
        spread_score = max(0.0, 1.0 - min(spread / max(MAX_SPREAD, 0.001), 1.0))
        tick_score = 0.3 if _safe_float(market.get("orderPriceMinTickSize")) <= 0.01 else 0.0
        return round((depth_score * 0.45) + (volume_score * 0.35) + (spread_score * 0.15) + tick_score, 4)

    def _top_opportunities(self, markets: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        opportunities: List[Dict[str, Any]] = []
        for market in markets:
            if not market.get("active") or market.get("closed") or not market.get("acceptingOrders"):
                continue
            liquidity = _safe_float(market.get("liquidityClob") or market.get("liquidity"))
            if liquidity < MIN_LIQUIDITY:
                continue
            token_ids = _ensure_list(market.get("clobTokenIds"))
            outcomes = _ensure_list(market.get("outcomes"))
            spread = _safe_float(market.get("spread"))
            best_ask = _safe_float(market.get("bestAsk"))
            best_bid = _safe_float(market.get("bestBid"))
            if spread <= 0 and best_ask > 0 and best_bid > 0:
                spread = max(best_ask - best_bid, 0.0)
            if spread > MAX_SPREAD:
                continue
            opportunities.append(
                {
                    "question": str(market.get("question") or ""),
                    "slug": str(market.get("slug") or market.get("questionID") or ""),
                    "condition_id": str(market.get("conditionId") or ""),
                    "yes_token_id": str(token_ids[0]) if len(token_ids) >= 1 else "",
                    "no_token_id": str(token_ids[1]) if len(token_ids) >= 2 else "",
                    "yes_outcome": str(outcomes[0]) if len(outcomes) >= 1 else "YES",
                    "no_outcome": str(outcomes[1]) if len(outcomes) >= 2 else "NO",
                    "best_bid": best_bid,
                    "best_ask": best_ask,
                    "spread": spread,
                    "liquidity": liquidity,
                    "volume24hr": _safe_float(market.get("volume24hr") or market.get("volume24h")),
                    "fees_enabled": bool(market.get("feesEnabled")),
                    "order_min_size": _safe_float(market.get("orderMinSize")),
                    "min_tick": _safe_float(market.get("orderPriceMinTickSize")),
                    "score": self._market_score(market),
                }
            )
        opportunities.sort(key=lambda item: item.get("score") or 0.0, reverse=True)
        return opportunities[:TOP_MARKETS]

    def refresh_state(self) -> Dict[str, Any]:
        geoblock = self._geoblock()
        gamma_markets = self._fetch_markets()
        simplified_markets = self._fetch_simplified_markets()
        top_opportunities = self._top_opportunities(gamma_markets)
        wallet_address = self.wallet_address()
        native_balance = self._native_balance_matic()
        balance_allowance = self._balance_allowance()
        state = {
            "ok": True,
            "service": "kibot-polymarket",
            "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "ready": bool(wallet_address) and not bool(geoblock.get("blocked")),
            "execution_enabled": EXECUTION_ENABLED,
            "sdk_ready": bool(self._build_client()),
            "wallet_address": wallet_address,
            "native_balance_matic": native_balance,
            "geoblock": geoblock,
            "gamma_market_count": len(gamma_markets),
            "simplified_market_count": len(simplified_markets),
            "top_opportunities": top_opportunities,
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
