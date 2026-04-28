"""
KiBot Trinity - Multi-Timeframe Analyzer v2
============================================
Confirms trend alignment across 1m, 15m, 60m, 4h timeframes via Indodax UDF API.
Uses stdlib only (no requests dependency). Includes caching and graceful fallback.

Filosofi: Hanya entry kalau trend align di multiple timeframe.
"""

import json
import os
import time
import urllib.request
from typing import Dict, Optional, Tuple

# Cache TTL per timeframe fetch (seconds)
_cache: Dict[str, Tuple[float, str]] = {}
CACHE_TTL = int(os.getenv("KIBOT_TF_CACHE_TTL_SEC", "60"))


class TimeframeScore:
    """Trend alignment score across 4 timeframes."""

    def __init__(self, t1m: str, t15m: str, t60m: str, t4h: str = "SIDEWAYS"):
        self.t1m = t1m    # UP, DOWN, SIDEWAYS
        self.t15m = t15m
        self.t60m = t60m
        self.t4h = t4h

    def entry_quality(self) -> str:
        """
        A:  All timeframes bullish — strong trend
        A-: 3/4 bullish — probable trend
        B:  2/4 bullish — early breakout, proceed with caution
        C:  Mixed — uncertain, reduce position
        D:  Majority bearish — skip
        """
        bullish_count = sum(1 for tf in [self.t1m, self.t15m, self.t60m, self.t4h] if tf == "UP")
        bearish_count = sum(1 for tf in [self.t1m, self.t15m, self.t60m, self.t4h] if tf == "DOWN")

        if bullish_count == 4:
            return "A"
        if bullish_count == 3 and bearish_count == 0:
            return "A-"
        if bullish_count >= 2 and bearish_count <= 1:
            return "B"
        if bearish_count >= 2:
            return "D"
        return "C"

    def confluence_multiplier(self) -> float:
        """Return a multiplier for conviction score based on TF alignment."""
        grade = self.entry_quality()
        return {"A": 1.15, "A-": 1.08, "B": 1.0, "C": 0.85, "D": 0.65}.get(grade, 0.85)

    def to_dict(self) -> dict:
        return {
            "t1m": self.t1m,
            "t15m": self.t15m,
            "t60m": self.t60m,
            "t4h": self.t4h,
            "quality": self.entry_quality(),
            "confluence_multiplier": self.confluence_multiplier(),
        }


def _fetch_candle_direction(pair_id: str, resolution: str, lookback_candles: int = 5) -> str:
    """
    Fetch candle direction from Indodax TradingView UDF API.
    Returns "UP", "DOWN", or "SIDEWAYS".
    Uses urllib (stdlib) — no external dependencies.
    """
    cache_key = f"{pair_id}:{resolution}"
    now = time.time()

    # Check cache
    if cache_key in _cache:
        cached_ts, cached_dir = _cache[cache_key]
        if now - cached_ts < CACHE_TTL:
            return cached_dir

    # Build symbol: pair_id "btc_idr" -> "BTC/IDR" for UDF API
    base = pair_id.replace("_idr", "").upper()
    symbol = f"{base}/IDR"

    now_ts = int(now)
    # For resolution in minutes, calculate the from timestamp
    try:
        res_minutes = int(resolution)
    except ValueError:
        res_minutes = 15
    from_ts = now_ts - (res_minutes * 60 * lookback_candles * 2)  # 2x buffer

    url = (
        f"https://indodax.com/tradingview/history_v2"
        f"?symbol={symbol}&tf={resolution}&from={from_ts}&to={now_ts}"
    )

    try:
        req = urllib.request.Request(url, headers={"User-Agent": "KiBot-TF/2.0"})
        with urllib.request.urlopen(req, timeout=5) as response:
            data = json.loads(response.read().decode("utf-8"))

        if not isinstance(data, dict):
            return "SIDEWAYS"

        closes = data.get("c", [])
        if len(closes) < 3:
            return "SIDEWAYS"

        # Compare last candle vs candle N-3 (or N-lookback)
        idx = min(lookback_candles, len(closes) - 1)
        c_curr = float(closes[-1])
        c_prev = float(closes[-idx])

        if c_prev <= 0:
            return "SIDEWAYS"

        change_pct = (c_curr - c_prev) / c_prev

        # Thresholds scaled by timeframe
        if res_minutes <= 5:
            threshold = 0.001   # 0.1% for short TF
        elif res_minutes <= 60:
            threshold = 0.002   # 0.2% for medium TF
        else:
            threshold = 0.005   # 0.5% for long TF

        if change_pct > threshold:
            direction = "UP"
        elif change_pct < -threshold:
            direction = "DOWN"
        else:
            direction = "SIDEWAYS"

        _cache[cache_key] = (now, direction)
        return direction

    except Exception:
        # On error, return SIDEWAYS (neutral) — never block on network failure
        return "SIDEWAYS"


def analyze_timeframes(pair_id: str) -> TimeframeScore:
    """
    Analyze trend direction across 4 timeframes for a given pair.
    Returns TimeframeScore with entry quality grade and confluence multiplier.

    Non-blocking: errors default to SIDEWAYS (no entry penalty, no boost).
    """
    t1m = _fetch_candle_direction(pair_id, "1")
    t15m = _fetch_candle_direction(pair_id, "15")
    t60m = _fetch_candle_direction(pair_id, "60")
    t4h = _fetch_candle_direction(pair_id, "240")

    return TimeframeScore(t1m, t15m, t60m, t4h)


def clear_cache() -> None:
    """Clear the candle direction cache."""
    _cache.clear()


if __name__ == "__main__":
    # Smoke test
    import sys
    pair = sys.argv[1] if len(sys.argv) > 1 else "btc_idr"
    print(f"Analyzing {pair}...")
    score = analyze_timeframes(pair)
    print(json.dumps(score.to_dict(), indent=2))
    print(f"Entry Quality: {score.entry_quality()}")
    print(f"Confluence Multiplier: {score.confluence_multiplier():.2f}x")
