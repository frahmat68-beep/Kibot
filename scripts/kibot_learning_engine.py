from __future__ import annotations

import json
import os
import time
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, Optional, Tuple
import re

STATE_PATH = Path(os.getenv("KIBOT_LEARNING_STATE_PATH", "state/learning_state.json"))
INDODAX_TAKER_FEE = 0.003
INDODAX_MAKER_FEE = 0.0015
ROUND_TRIP_TAKER = INDODAX_TAKER_FEE * 2
ROUND_TRIP_MAKER = INDODAX_MAKER_FEE * 2
HALF_KELLY_MULT = 0.5
MIN_TRADES_FOR_KELLY = 3
MAX_KELLY_FRACTION = 0.12
MIN_KELLY_FRACTION = 0.02
EMA_DECAY = float(os.getenv("KIBOT_HFT_EMA_DECAY", "0.80"))
MIN_PROFIT_FACTOR = 1.30
VOLATILITY_GUARD_THRESHOLD = float(os.getenv("KIBOT_HFT_VOLATILITY_GUARD_THRESHOLD", "0.05"))


@dataclass
class PairStats:
    pair: str
    alpha: float = 1.0
    beta: float = 1.0
    trade_count: int = 0
    win_count: int = 0
    loss_count: int = 0
    total_gross_pnl: float = 0.0
    total_fees_paid: float = 0.0
    sum_wins: float = 0.0
    sum_losses: float = 0.0
    ema_pnl: float = 0.0
    last_trade_ts: float = 0.0
    last_win_ts: float = 0.0
    last_loss_ts: float = 0.0
    cooldown_until_ts: float = 0.0

    def to_dict(self) -> dict:
        return asdict(self)

    @staticmethod
    def from_dict(data: dict) -> "PairStats":
        fields = PairStats.__dataclass_fields__
        return PairStats(**{key: value for key, value in data.items() if key in fields})

    @property
    def win_probability(self) -> float:
        return self.alpha / max(1e-9, self.alpha + self.beta)

    @property
    def profit_factor(self) -> float:
        return 2.0 if self.sum_losses <= 0 else self.sum_wins / self.sum_losses

    @property
    def avg_win(self) -> float:
        return self.sum_wins / max(1, self.win_count)

    @property
    def avg_loss(self) -> float:
        return self.sum_losses / max(1, self.loss_count)

    @property
    def reward_risk_ratio(self) -> float:
        if self.avg_loss <= 1e-9:
            return 1.5
        return self.avg_win / self.avg_loss

    def kelly_fraction(self, current_volatility: float = 0.0) -> float:
        if self.trade_count < MIN_TRADES_FOR_KELLY:
            return MIN_KELLY_FRACTION
        if self.win_count <= 0:
            return 0.0
        win_rate = self.win_probability
        loss_rate = 1.0 - win_rate
        rr = self.reward_risk_ratio
        if rr <= 0:
            return MIN_KELLY_FRACTION
        full = win_rate - (loss_rate / rr)
        if full <= 0:
            return 0.0
        
        # Volatility Guard: Slash size if market is too manic
        mult = HALF_KELLY_MULT
        if current_volatility > VOLATILITY_GUARD_THRESHOLD:
            # Reduce sizing by half again if over threshold
            mult *= 0.5
            
        half = full * mult
        return max(MIN_KELLY_FRACTION, min(MAX_KELLY_FRACTION, half))

    def should_entry(self) -> Tuple[bool, str]:
        now = time.time()
        if now < self.cooldown_until_ts:
            return False, f"cooldown {int(self.cooldown_until_ts - now)}s"
        if self.trade_count >= MIN_TRADES_FOR_KELLY:
            if self.profit_factor < MIN_PROFIT_FACTOR:
                return False, f"profit_factor={self.profit_factor:.2f}<{MIN_PROFIT_FACTOR}"
            if self.ema_pnl < -ROUND_TRIP_TAKER:
                return False, f"ema_pnl={self.ema_pnl:.4f} negative trend"
            if self.kelly_fraction() <= 0:
                return False, "kelly=0 no edge detected"
        return True, "ok"

    def record_trade(self, net_pnl_pct: float) -> None:
        net_pnl = net_pnl_pct
        self.trade_count += 1
        self.last_trade_ts = time.time()
        self.ema_pnl = net_pnl if self.trade_count == 1 else (EMA_DECAY * self.ema_pnl + (1 - EMA_DECAY) * net_pnl)
        if net_pnl > 0:
            self.win_count += 1
            self.alpha += 1.0
            self.sum_wins += abs(net_pnl)
            self.last_win_ts = self.last_trade_ts
        else:
            self.loss_count += 1
            self.beta += 1.0
            self.sum_losses += abs(net_pnl)
            self.last_loss_ts = self.last_trade_ts
            severity = abs(net_pnl)
            cooldown = 3600 if severity > 0.03 else 1200 if severity > 0.015 else 300
            self.cooldown_until_ts = time.time() + cooldown


class LearningEngine:
    def __init__(self, path: Path | str = STATE_PATH) -> None:
        self.path = Path(path)
        self._stats: Dict[str, PairStats] = {}
        self._load()

    def _load(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        try:
            if self.path.exists():
                raw = json.loads(self.path.read_text(encoding="utf-8"))
                for pair, payload in raw.items():
                    self._stats[pair] = PairStats.from_dict(payload)
        except Exception:
            self._stats = {}
            
        self.ingest_trade_log()

    def ingest_trade_log(self, file_path="state/trade_log.jsonl") -> None:
        p = Path(file_path)
        if not p.exists():
            return
        # Reset current stats if relying on JSONL
        try:
            self._stats = {}
            with open(p, "r", encoding="utf-8") as f:
                for line in f:
                    if not line.strip(): continue
                    trade = json.loads(line)
                    if trade.get("side") == "SELL":
                        pair = trade.get("pair")
                        if pair:
                            if pair not in self._stats:
                                self._stats[pair] = PairStats(pair=pair)
                            pnl_pct = self._normalize_trade_pnl_pct(trade)
                            if pnl_pct is None:
                                continue
                            self._stats[pair].record_trade(pnl_pct)
        except Exception as e:
            print(f"Failed to ingest trade log: {e}")

    @staticmethod
    def _extract_reason_pnl_pct(exit_reason: object) -> float | None:
        text = str(exit_reason or "").strip()
        if not text:
            return None
        for pattern in (r"pnl=([+-]?\d+(?:\.\d+)?)%", r"at ([+-]?\d+(?:\.\d+)?)%"):
            match = re.search(pattern, text, flags=re.IGNORECASE)
            if match:
                try:
                    return float(match.group(1)) / 100.0
                except Exception:
                    return None
        return None

    @classmethod
    def _normalize_trade_pnl_pct(cls, trade: dict) -> float | None:
        direct = trade.get("netPnlPct")
        try:
            direct_val = float(direct) if direct is not None else None
        except Exception:
            direct_val = None
        filled_price = trade.get("filledPrice")
        try:
            filled_price_val = float(filled_price) if filled_price is not None else None
        except Exception:
            filled_price_val = None
        inferred = cls._extract_reason_pnl_pct(trade.get("exitReason"))
        if inferred is not None:
            if direct_val is None:
                return inferred
            if filled_price_val is None or filled_price_val <= 0.0 or (direct_val < -0.90 and inferred > 0.0):
                return inferred
        return direct_val

    def _save(self) -> None:
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        tmp.write_text(json.dumps({pair: stats.to_dict() for pair, stats in self._stats.items()}, indent=2), encoding="utf-8")
        os.replace(tmp, self.path)

    def get(self, pair: str) -> PairStats:
        if pair not in self._stats:
            self._stats[pair] = PairStats(pair=pair)
        return self._stats[pair]

    def record_trade(self, pair: str, net_pnl_pct: float) -> PairStats:
        stats = self.get(pair)
        stats.record_trade(net_pnl_pct)
        self._save()
        return stats

    def kelly_size(self, pair: str) -> float:
        return self.get(pair).kelly_fraction()

    def should_entry(self, pair: str) -> Tuple[bool, str]:
        return self.get(pair).should_entry()

    def score_penalty(self, pair: str) -> float:
        stats = self.get(pair)
        if stats.trade_count < 3:
            return 0.0
        penalty = 0.0
        if stats.profit_factor < 1.0:
            penalty += 0.20
        elif stats.profit_factor < MIN_PROFIT_FACTOR:
            penalty += 0.10
        if stats.ema_pnl < -ROUND_TRIP_TAKER:
            penalty += 0.10
        if stats.kelly_fraction() <= 0:
            penalty += 0.15
        return min(penalty, 0.30)

    def regime_adjust_size(self, base_fraction: float, regime: str) -> float:
        multipliers = {
            "BULLISH": 1.0,
            "HEALTHY_UPTREND": 1.0,
            "HEALTHY_SIDEWAYS": 0.6,
            "SIDEWAYS": 0.6,
            "HIGH_VOLATILITY_UNCLEAR": 0.4,
            "BEARISH": 0.3,
            "BREAKDOWN_PANIC": 0.0,
        }
        return base_fraction * multipliers.get(regime, 0.6)


class VWAPRegimeDetector:
    def detect(self, candles: list) -> str:
        if len(candles) < 5:
            return "SIDEWAYS"
        try:
            tp_vol = sum(((c["high"] + c["low"] + c["close"]) / 3.0) * c["volume"] for c in candles)
            total_vol = sum(c["volume"] for c in candles)
            if total_vol <= 0:
                return "SIDEWAYS"
            vwap = tp_vol / total_vol
            last_price = candles[-1]["close"]
            avg_vol = total_vol / len(candles)
            vol_ratio = candles[-1]["volume"] / avg_vol if avg_vol > 0 else 1.0
            closes = [c["close"] for c in candles[-5:]]
            ema5 = closes[0]
            for price in closes[1:]:
                ema5 = 0.7 * ema5 + 0.3 * price
            above_vwap = last_price > vwap
            high_volume = vol_ratio > 1.5
            trend_ratio = last_price / max(1e-9, closes[0])
            if trend_ratio > 1.01 and above_vwap and high_volume:
                return "BULLISH"
            if trend_ratio < 0.95 and high_volume:
                return "BREAKDOWN_PANIC"
            if trend_ratio < 0.99 and not above_vwap and high_volume:
                return "BEARISH"
            if trend_ratio < 1.0 and high_volume and ema5 <= vwap:
                return "BEARISH"
            return "SIDEWAYS"
        except Exception:
            return "SIDEWAYS"


_engine: Optional[LearningEngine] = None
_regime_detector: Optional[VWAPRegimeDetector] = None


def get_engine() -> LearningEngine:
    global _engine
    if _engine is None:
        _engine = LearningEngine()
    return _engine


def get_regime_detector() -> VWAPRegimeDetector:
    global _regime_detector
    if _regime_detector is None:
        _regime_detector = VWAPRegimeDetector()
    return _regime_detector
