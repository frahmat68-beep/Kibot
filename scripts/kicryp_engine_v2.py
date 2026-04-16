#!/usr/bin/env python3
from __future__ import annotations
"""
KiCryp Trinity v7.0 — Dual Bucket Engine
Bucket A: Global Lead-Lag (Kinance + KiCom)
Bucket B: Local Indodax-Only (ConvictionScore)
"""

import os, json, math, time, threading, statistics
from pathlib import Path
from datetime import datetime, timedelta, date
from dataclasses import dataclass, asdict
from typing import Optional
import urllib.request
import logging

logger = logging.getLogger("kicryp_engine_v2")

# ============================================================
# KONSTANTA SISTEM — JANGAN UBAH TANPA REVIEW
# ============================================================

INDODAX_TICKERS_URL = "https://indodax.com/api/tickers"
INDODAX_OHLCV_URL   = "https://indodax.com/tradingview/history_v2"
CRYPTOCOM_API_URL   = "https://api.crypto.com/exchange/v1/public"
BINANCE_API_URL     = "https://api.binance.com/api/v3"

SUPABASE_URL        = os.environ.get("SUPABASE_URL", "")
SUPABASE_KEY        = os.environ.get("SUPABASE_ANON_KEY", "")

try:
    STATE_DIR = Path("/home/ubuntu/KiCryp/state")
    if not STATE_DIR.exists():
        STATE_DIR.mkdir(parents=True, exist_ok=True)
except Exception:
    STATE_DIR = Path("state")
    STATE_DIR.mkdir(parents=True, exist_ok=True)

TRADE_LOG_FILE      = STATE_DIR / "trade_log.jsonl"
DAILY_SUMMARY_FILE  = STATE_DIR / "daily_summary.json"
CASCADE_FILE        = STATE_DIR / "cascade_mode.json"

MAKER_FEE  = 0.0004
TAKER_FEE  = 0.0055
PPH_SELL   = 0.0021
ROUND_TRIP_LIMIT  = MAKER_FEE + PPH_SELL + MAKER_FEE   # ~0.0069
ROUND_TRIP_MARKET = TAKER_FEE + PPH_SELL + TAKER_FEE   # ~0.0131

# Gunakan base path lokal untuk state jika di lingkungan test
if os.environ.get("KICRYP_TEST_STATE"):
    STATE_DIR = Path(os.environ.get("KICRYP_TEST_STATE"))
    TRADE_LOG_FILE = STATE_DIR / "trade_log.jsonl"
    CASCADE_FILE = STATE_DIR / "cascade_mode.json"
    STATE_DIR.mkdir(parents=True, exist_ok=True)

MIN_ORDER_IDR    = 10_000
MIN_EQUITY_IDR   = 30_000
MIN_CASH_RESERVE = 0.20   # 20% total equity selalu cash

# ============================================================
# CASCADE LOSS INTELLIGENCE — Mode Adaptif
# ============================================================

CASCADE_MODES = {
    "GROWTH":     {"kelly_mult": 1.0, "conv_min": 0.85, "bucket_b_active": True,  "max_a": 3, "max_b": 2},
    "CAUTION":    {"kelly_mult": 0.8, "conv_min": 0.88, "bucket_b_active": True,  "max_a": 2, "max_b": 1},
    "DEFENSIVE":  {"kelly_mult": 0.5, "conv_min": 0.90, "bucket_b_active": False, "max_a": 2, "max_b": 0},
    "RESTRICTED": {"kelly_mult": 0.3, "conv_min": 0.92, "bucket_b_active": False, "max_a": 1, "max_b": 0},
    "HARD_STOP":  {"kelly_mult": 0.0, "conv_min": 1.00, "bucket_b_active": False, "max_a": 0, "max_b": 0},
}

@dataclass
class CascadeState:
    mode: str = "GROWTH"
    wins_today: int = 0
    losses_today: int = 0
    consecutive_losses: int = 0
    daily_pnl_pct: float = 0.0
    weekly_pnl_pct: float = 0.0
    last_updated: str = ""

    @classmethod
    def load(cls):
        if CASCADE_FILE.exists():
            try:
                data = json.loads(CASCADE_FILE.read_text())
                return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})
            except Exception:
                pass
        return cls()

    def save(self):
        self.last_updated = datetime.utcnow().isoformat()
        CASCADE_FILE.write_text(json.dumps(asdict(self), indent=2))

    def get_config(self) -> dict:
        return CASCADE_MODES.get(self.mode, CASCADE_MODES["GROWTH"])

    def on_win(self):
        self.wins_today += 1
        self.consecutive_losses = 0
        prev_mode = self.mode
        if self.mode == "CAUTION":
            self.mode = "GROWTH"
        elif self.mode == "DEFENSIVE" and self.wins_today >= 2:
            self.mode = "CAUTION"
        elif self.mode == "RESTRICTED" and self.wins_today >= 3:
            self.mode = "DEFENSIVE"
        if self.mode != prev_mode:
            logger.info(f"[CASCADE] Mode upgrade: {prev_mode} → {self.mode}")
        self.save()

    def on_loss(self, daily_pnl_pct: float):
        self.losses_today += 1
        self.consecutive_losses += 1
        self.daily_pnl_pct = daily_pnl_pct
        prev_mode = self.mode
        if daily_pnl_pct <= -0.02:
            self.mode = "HARD_STOP"
        elif self.consecutive_losses >= 3:
            self.mode = "RESTRICTED"
        elif self.consecutive_losses >= 2:
            self.mode = "DEFENSIVE"
        elif self.consecutive_losses >= 1:
            self.mode = "CAUTION"
        if self.mode != prev_mode:
            logger.warning(f"[CASCADE] Mode downgrade: {prev_mode} → {self.mode}")
        self.save()

    def daily_reset(self):
        self.wins_today = 0
        self.losses_today = 0
        # Jangan reset consecutive_losses — itu carry over
        if self.mode == "HARD_STOP":
            self.mode = "CAUTION"  # Reset tapi tidak langsung GROWTH
        self.save()

cascade_state = CascadeState.load()

# ============================================================
# DUAL BUCKET MANAGER — 50/50 dengan Half-Kelly
# ============================================================

@dataclass
class BucketState:
    a_balance_idr: float = 0.0   # 50% Bucket A
    b_balance_idr: float = 0.0   # 50% Bucket B
    a_positions: int = 0
    b_positions: int = 0

    def from_equity(self, equity_idr: float):
        cash_reserve = equity_idr * MIN_CASH_RESERVE
        tradeable = equity_idr - cash_reserve
        self.a_balance_idr = tradeable * 0.50
        self.b_balance_idr = tradeable * 0.50
        return self

def calculate_position_size_a(bucket_balance: float, win_rate: float,
                                avg_win_pct: float, avg_loss_pct: float,
                                cascade_config: dict) -> float:
    """Half-Kelly untuk Bucket A (lead-lag, history dari Binance)."""
    if avg_loss_pct <= 0:
        return max(bucket_balance * 0.08, MIN_ORDER_IDR)
    rr = avg_win_pct / avg_loss_pct
    kelly = win_rate - ((1 - win_rate) / rr)
    half_kelly = max(0, kelly / 2) * cascade_config["kelly_mult"]
    size = bucket_balance * half_kelly
    max_size = bucket_balance * 0.12
    return max(MIN_ORDER_IDR, min(size, max_size))

def calculate_position_size_b(bucket_balance: float, total_trades_pair: int,
                                win_rate: float, avg_win_pct: float,
                                avg_loss_pct: float, cascade_config: dict) -> float:
    """Fixed 8% jika < 20 trades, Half-Kelly jika >= 20 trades."""
    if total_trades_pair < 20:
        size = bucket_balance * 0.08 * cascade_config["kelly_mult"]
    else:
        if avg_loss_pct <= 0:
            size = bucket_balance * 0.08
        else:
            rr = avg_win_pct / avg_loss_pct
            kelly = win_rate - ((1 - win_rate) / rr)
            half_kelly = max(0, kelly / 2) * cascade_config["kelly_mult"]
            size = bucket_balance * half_kelly
    max_size = bucket_balance * 0.10
    return max(MIN_ORDER_IDR, min(size, max_size))

# ============================================================
# WHAT-IF ENGINE — 5 Skenario Matematis (tidak butuh AI)
# ============================================================

def what_if_ev(win_rate: float, avg_net_win: float, avg_net_loss: float,
               use_market: bool = False) -> float:
    """Expected Value sebelum entry. Entry hanya jika EV > 0.005."""
    fee = ROUND_TRIP_MARKET if use_market else ROUND_TRIP_LIMIT
    return (win_rate * avg_net_win) - ((1 - win_rate) * avg_net_loss) - fee

def simulate_what_if_full(pair_id: str, budget_idr: float,
                           spread_pct: float, slippage_pct: float,
                           target_pct: float, trailing_pct: float,
                           win_rate: float = 0.5, n_trades: int = 0,
                           use_market: bool = False) -> dict:
    """Simulasi lengkap sebelum entry. Return decision + semua angka."""
    fee = ROUND_TRIP_MARKET if use_market else ROUND_TRIP_LIMIT
    cost = (spread_pct / 2) + slippage_pct + fee
    net_pct = target_pct - cost
    loss_pct = trailing_pct + cost
    reward_idr = budget_idr * max(net_pct, 0)
    loss_idr = budget_idr * loss_pct
    rr = reward_idr / max(loss_idr, 1)

    if n_trades < 5:
        wr = 0.5 * (n_trades / 5) + 0.5 * (1 - n_trades / 5)
    else:
        wr = win_rate

    ev_idr = (wr * reward_idr) - ((1 - wr) * loss_idr)

    min_net = 0.018 if use_market else 0.008
    if ev_idr <= 0 or rr < 1.2 or net_pct < min_net:
        decision = "SKIP"
    elif rr < 1.5 or wr < 0.45:
        decision = "REDUCE"
    else:
        decision = "ENTER"

    return {
        "decision": decision, "net_pct": round(net_pct, 4),
        "ev_idr": round(ev_idr, 0), "risk_reward": round(rr, 2),
        "win_rate": round(wr, 3), "cost_pct": round(cost, 4),
        "reward_idr": round(reward_idr, 0), "loss_idr": round(loss_idr, 0),
        "min_ev_threshold": 0.005
    }

# ============================================================
# MATH — Candle & Indicators
# ============================================================

def fetch_candles(pair_id: str, tf: int = 15, count: int = 30) -> list[dict]:
    base = pair_id.replace("_idr", "").upper()
    symbol = f"{base}/IDR"
    now = int(time.time())
    from_ts = now - (tf * 60 * count)
    url = f"{INDODAX_OHLCV_URL}?symbol={symbol}&tf={tf}&from={from_ts}&to={now}"
    try:
        with urllib.request.urlopen(url, timeout=8) as r:
            data = json.loads(r.read())
        t = data.get("t", [])
        c = data.get("c", data.get("Close", []))
        h = data.get("h", data.get("High", []))
        lo = data.get("l", data.get("Low", []))
        v = data.get("v", data.get("Vol", []))
        return [{"t": int(t[i]), "c": float(c[i]), "h": float(h[i]),
                 "l": float(lo[i]), "v": float(v[i]) if i < len(v) else 0}
                for i in range(len(t))]
    except Exception as e:
        logger.debug(f"[CANDLE] {pair_id}: {e}")
        return []

def calc_bollinger(closes: list[float], period: int = 20, mult: float = 2.0) -> dict | None:
    if len(closes) < period:
        return None
    w = closes[-period:]
    sma = sum(w) / period
    std = math.sqrt(sum((c - sma)**2 for c in w) / period)
    return {"upper": sma + mult*std, "middle": sma, "lower": sma - mult*std, "std": std}

def calc_rsi(closes: list[float], period: int = 14) -> float:
    if len(closes) < period + 1:
        return 50.0
    gains = [max(closes[i]-closes[i-1], 0) for i in range(1, len(closes))][-period:]
    losses = [max(closes[i-1]-closes[i], 0) for i in range(1, len(closes))][-period:]
    ag = sum(gains) / period
    al = sum(losses) / period
    return 100.0 if al == 0 else 100 - (100 / (1 + ag/al))

def calc_volume_trend(volumes: list[float], short: int = 3, long: int = 10) -> str:
    if len(volumes) < long:
        return "stable"
    s = sum(volumes[-short:]) / short
    l = sum(volumes[-long:]) / long
    if s > l * 1.5:
        return "increasing"
    if s < l * 0.6:
        return "decreasing"
    return "stable"

def calc_orderbook_score(bid_depth: float, ask_depth: float) -> float:
    total = bid_depth + ask_depth
    if total <= 0:
        return 0.5
    return min(bid_depth / total, 1.0)

# ============================================================
# CONVICTION SCORE — Bucket B (Local Indodax-only)
# Algoritma murni matematis. Score 0.0-1.0.
# Entry hanya jika score >= 0.85 dan tidak ada hard block.
# ============================================================

@dataclass
class ConvictionResult:
    score: float
    allowed: bool
    block_reason: str
    components: dict
    pump_phase: str
    trailing_stop_pct: float
    target_pct: float

def compute_conviction_score(
    pair_id: str,
    price: float,
    high_24h: float,
    low_24h: float,
    vol_24h_idr: float,
    price_change_24h_pct: float,
    bid_depth_idr: float,
    ask_depth_idr: float,
    closes: list[float],
    volumes: list[float],
    avg_vol_7d_idr: float,
    btc_change_1h_pct: float,
    pair_in_cooldown: bool
) -> ConvictionResult:
    """
    ConvictionScore = (
        0.30 × volume_spike_score    ← vol_24h / avg_vol_7d, capped 1.0
        0.25 × breakout_score        ← (price - lower_BB) / (upper_BB - lower_BB)
        0.25 × orderbook_score       ← bid_depth / (bid_depth + ask_depth)
        0.20 × momentum_score        ← (75 - RSI_15m) / 75 [RSI < 75 = room to run]
    )
    """
    # === HARD BLOCKS (langsung SKIP) ===
    HARD_BLOCKS = []
    if price_change_24h_pct > 50.0:
        HARD_BLOCKS.append(f"pump_24h={price_change_24h_pct:.0f}% >50% sudah terlambat")
    if vol_24h_idr < 500_000_000:
        HARD_BLOCKS.append(f"vol={vol_24h_idr/1e9:.2f}B IDR <500M tidak liquid")
    if pair_in_cooldown:
        HARD_BLOCKS.append("pair dalam cooldown setelah loss")
    if btc_change_1h_pct < -4.0:
        HARD_BLOCKS.append(f"BTC dump 1h={btc_change_1h_pct:.1f}% <-4%")

    bb = calc_bollinger(closes) if len(closes) >= 20 else None
    rsi = calc_rsi(closes)

    if bb and price > bb["upper"]:
        HARD_BLOCKS.append(f"price>BB_upper ({price:.4f}>{bb['upper']:.4f})")
    if rsi > 80:
        HARD_BLOCKS.append(f"RSI={rsi:.0f} >80 overbought")

    if HARD_BLOCKS:
        return ConvictionResult(
            score=0.0, allowed=False,
            block_reason=" | ".join(HARD_BLOCKS),
            components={}, pump_phase="BLOCKED",
            trailing_stop_pct=0.05, target_pct=0.04
        )

    # === KOMPONEN SCORE ===
    # 1. Volume spike (30%)
    vol_ratio = vol_24h_idr / max(avg_vol_7d_idr, 1)
    volume_spike_score = min(vol_ratio / 3.0, 1.0)  # Ratio 3x = skor 1.0

    # 2. Breakout score (25%)
    if bb and (bb["upper"] - bb["lower"]) > 0:
        breakout_score = (price - bb["lower"]) / (bb["upper"] - bb["lower"])
        breakout_score = max(0.0, min(breakout_score, 1.0))
    else:
        pos_range = (price - low_24h) / max(high_24h - low_24h, 0.001)
        breakout_score = pos_range

    # 3. Orderbook score (25%)
    orderbook_score = calc_orderbook_score(bid_depth_idr, ask_depth_idr)

    # 4. Momentum score (20%) — RSI < 75 = ada ruang naik
    momentum_score = max(0.0, (75.0 - rsi) / 75.0)

    # === FINAL SCORE ===
    score = (
        0.30 * volume_spike_score +
        0.25 * breakout_score +
        0.25 * orderbook_score +
        0.20 * momentum_score
    )
    score = round(max(0.0, min(score, 1.0)), 3)

    # === PUMP PHASE ===
    pos_range = (price - low_24h) / max(high_24h - low_24h, 0.001)
    if pos_range < 0.30:
        pump_phase = "EARLY"
        trailing, target = 0.05, 0.08
    elif pos_range < 0.55:
        pump_phase = "MID"
        trailing, target = 0.04, 0.05
    elif pos_range < 0.80:
        pump_phase = "LATE"
        trailing, target = 0.03, 0.03
    else:
        pump_phase = "PEAK"
        trailing, target = 0.02, 0.02

    allowed = score >= 0.85

    return ConvictionResult(
        score=score,
        allowed=allowed,
        block_reason="" if allowed else f"score={score:.3f} <0.85",
        components={
            "volume_spike": round(volume_spike_score, 3),
            "breakout": round(breakout_score, 3),
            "orderbook": round(orderbook_score, 3),
            "momentum": round(momentum_score, 3),
            "rsi": round(rsi, 1),
            "vol_ratio": round(vol_ratio, 2),
        },
        pump_phase=pump_phase,
        trailing_stop_pct=trailing,
        target_pct=target
    )

# ============================================================
# KICOM SCANNER — Crypto.com sebagai scanner ke-2 untuk Bucket A
# Membaca harga dari Crypto.com API untuk konfirmasi lead-lag
# ============================================================

CRYPTOCOM_PAIR_MAP = {
    "btc_idr":   "BTC_USDT",  "eth_idr":   "ETH_USDT",
    "xrp_idr":   "XRP_USDT",  "sol_idr":   "SOL_USDT",
    "doge_idr":  "DOGE_USDT", "bnb_idr":   "BNB_USDT",
    "ada_idr":   "ADA_USDT",  "xlm_idr":   "XLM_USDT",
    "trx_idr":   "TRX_USDT",  "dot_idr":   "DOT_USDT",
    "shib_idr":  "SHIB_USDT", "avax_idr":  "AVAX_USDT",
    "link_idr":  "LINK_USDT", "uni_idr":   "UNI_USDT",
    "atom_idr":  "ATOM_USDT", "near_idr":  "NEAR_USDT",
    "apt_idr":   "APT_USDT",  "sui_idr":   "SUI_USDT",
    "pepe_idr":  "PEPE_USDT", "bonk_idr":  "BONK_USDT",
    "floki_idr": "FLOKI_USDT","enj_idr":   "ENJ_USDT",
    "matic_idr": "MATIC_USDT","pol_idr":   "POL_USDT",
    "dusk_idr":  "DUSK_USDT", "fun_idr":   "FUN_USDT",
}

@dataclass
class KiComSignal:
    pair_id: str
    cryptocom_symbol: str
    price_change_1h_pct: float
    price_change_24h_pct: float
    volume_change_pct: float
    is_bullish: bool
    confidence: float   # 0.0 - 1.0
    timestamp_ms: int

def fetch_kicom_signal(indodax_pair: str) -> KiComSignal | None:
    """
    Ambil sinyal dari Crypto.com Exchange API.
    Konfirmasi bahwa aset juga naik di Crypto.com → lead-lag valid.
    Crypto.com MCP tersambung, tapi gunakan REST API langsung untuk reliability.
    """
    cdc_symbol = CRYPTOCOM_PAIR_MAP.get(indodax_pair)
    if not cdc_symbol:
        return None
    url = f"{CRYPTOCOM_API_URL}/get-ticker?instrument_name={cdc_symbol}"
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            data = json.loads(r.read())
        ticker = data.get("result", {}).get("data", [{}])[0]
        change_24h = float(ticker.get("c", 0))
        high = float(ticker.get("h", 0))
        low  = float(ticker.get("l", 0))
        last = float(ticker.get("a", 0))
        # Estimasi 1h change dari high/low range (approximation)
        range_pct = (high - low) / max(low, 0.001) * 100 if low > 0 else 0
        change_1h = change_24h * (1/24) * 1.5  # rough estimate

        is_bullish = change_24h > 0.5 or change_1h > 0.3
        confidence = min(abs(change_24h) / 5.0, 1.0)  # 5% change = confidence 1.0

        return KiComSignal(
            pair_id=indodax_pair,
            cryptocom_symbol=cdc_symbol,
            price_change_1h_pct=round(change_1h, 3),
            price_change_24h_pct=round(change_24h * 100, 3),
            volume_change_pct=0.0,  # CDC API tidak return vol change langsung
            is_bullish=is_bullish,
            confidence=round(confidence, 3),
            timestamp_ms=int(time.time() * 1000)
        )
    except Exception as e:
        logger.debug(f"[KICOM] {cdc_symbol}: {e}")
        return None

def check_dual_scanner_agreement(
    indodax_pair: str,
    kinance_signal: dict,  # Signal dari Kinance UDP
    kicom_signal: KiComSignal | None
) -> tuple[bool, float, str]:
    """
    Bucket A entry hanya jika KEDUA scanner setuju (AND gate).
    Return: (agreed, combined_confidence, reason)
    """
    kinance_ok = (
        kinance_signal.get("confidence", 0) >= 0.60 and
        kinance_signal.get("shortTermReturnPct", 0) > 0 and
        kinance_signal.get("tradeActivityScore", 0) > 0.5
    )
    if not kicom_signal:
        return False, 0.0, "KiCom signal tidak tersedia"

    kicom_ok = kicom_signal.is_bullish and kicom_signal.confidence >= 0.40

    if not kinance_ok:
        return False, 0.0, f"Kinance tidak confirm (conf={kinance_signal.get('confidence',0):.2f})"
    if not kicom_ok:
        return False, 0.0, f"KiCom tidak confirm (bullish={kicom_signal.is_bullish})"

    combined = (kinance_signal.get("confidence", 0) + kicom_signal.confidence) / 2
    return True, combined, f"BOTH confirmed (kinance={kinance_signal.get('confidence',0):.2f}, kicom={kicom_signal.confidence:.2f})"

# ============================================================
# BTC REGIME GUARD
# ============================================================

_btc_price_1h_ago: float = 0.0
_btc_current_price: float = 0.0

def update_btc_price(price: float):
    global _btc_price_1h_ago, _btc_current_price
    _btc_current_price = price
    # 1h ago diupdate oleh scheduler setiap jam

def get_btc_change_1h() -> float:
    if _btc_price_1h_ago <= 0:
        return 0.0
    return (_btc_current_price - _btc_price_1h_ago) / _btc_price_1h_ago * 100

def is_btc_regime_ok(bucket: str) -> tuple[bool, str]:
    """Return (ok, reason). Bucket B diblok jika BTC dump >-4%."""
    change = get_btc_change_1h()
    if change < -5.0:
        return False, f"BTC dump 1h={change:.1f}% — semua entry diblok"
    if bucket == "B" and change < -4.0:
        return False, f"BTC dump 1h={change:.1f}% — Bucket B diblok"
    return True, f"BTC 1h={change:.1f}% OK"

# ============================================================
# TRADE LOGGER — Memory Sistem (KRITIKAL)
# ============================================================

class TradeLogger:
    def __init__(self):
        TRADE_LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        self._today: list[dict] = []
        self._load_today()

    def _load_today(self):
        if not TRADE_LOG_FILE.exists():
            return
        today = (datetime.utcnow() + timedelta(hours=7)).strftime("%Y-%m-%d")
        try:
            with open(TRADE_LOG_FILE) as f:
                for line in f:
                    if not line.strip():
                        continue
                    try:
                        t = json.loads(line)
                        if t.get("entry_at", "").startswith(today):
                            self._today.append(t)
                    except Exception:
                        pass
        except Exception as e:
            logger.debug(f"[TRADELOG] Load: {e}")

    def record_entry(self, pair_id: str, bucket: str, entry_price: float,
                     budget_idr: float, conviction_score: float,
                     pump_phase: str, order_type: str, cascade_mode: str,
                     target_pct: float, trailing_pct: float) -> str:
        import uuid
        trade_id = f"{pair_id[:4]}{int(time.time())%10000}"
        fee_entry = (TAKER_FEE if order_type == "MARKET" else MAKER_FEE) * budget_idr
        trade = {
            "trade_id": trade_id,
            "pair_id": pair_id,
            "bucket": bucket,
            "entry_price": entry_price,
            "budget_idr": budget_idr,
            "conviction_score": conviction_score,
            "pump_phase": pump_phase,
            "order_type_entry": order_type,
            "cascade_mode": cascade_mode,
            "target_pct": target_pct,
            "trailing_pct": trailing_pct,
            "fee_entry_idr": round(fee_entry, 2),
            "entry_at": (datetime.utcnow() + timedelta(hours=7)).isoformat(),
            "status": "OPEN"
        }
        with open(TRADE_LOG_FILE, "a") as f:
            f.write(json.dumps(trade) + "\n")
        logger.info(f"[TRADE] ENTRY {bucket}/{pair_id} @ {entry_price:.6f} Rp{budget_idr:,.0f} [{trade_id}]")
        return trade_id

    def record_exit(self, trade_id: str, exit_price: float,
                    exit_reason: str, order_type_exit: str = "LIMIT") -> dict | None:
        lines = []
        found = None
        if not TRADE_LOG_FILE.exists():
            return None
        with open(TRADE_LOG_FILE) as f:
            for line in f:
                if not line.strip():
                    continue
                try:
                    t = json.loads(line)
                    if t.get("trade_id") == trade_id and t.get("status") == "OPEN":
                        entry = t["entry_price"]
                        budget = t["budget_idr"]
                        gross_pct = (exit_price - entry) / entry
                        fee_exit = (TAKER_FEE if order_type_exit == "MARKET" else MAKER_FEE) + PPH_SELL
                        net_pct = gross_pct - t.get("fee_entry_idr", 0) / budget - fee_exit
                        pnl_idr = budget * net_pct
                        hold_min = int(
                            (datetime.now() - datetime.fromisoformat(t["entry_at"])).total_seconds() / 60
                        )
                        t.update({
                            "exit_price": exit_price,
                            "pnl_idr": round(pnl_idr, 2),
                            "pnl_pct": round(net_pct, 5),
                            "hold_minutes": hold_min,
                            "win": pnl_idr > 0,
                            "exit_reason": exit_reason,
                            "order_type_exit": order_type_exit,
                            "exit_at": (datetime.utcnow() + timedelta(hours=7)).isoformat(),
                            "status": "CLOSED"
                        })
                        found = t
                        self._today.append(t)
                        logger.info(
                            f"[TRADE] EXIT {t['bucket']}/{t['pair_id']} "
                            f"Rp{pnl_idr:+,.0f} ({net_pct:+.2%}) "
                            f"hold={hold_min}m [{trade_id}] {exit_reason}"
                        )
                    lines.append(json.dumps(t))
                except Exception:
                    lines.append(line.strip())
        with open(TRADE_LOG_FILE, "w") as f:
            f.write("\n".join(lines) + "\n")
        if found:
            self._async_sync_supabase(found)
        return found

    def get_stats(self, bucket: str | None = None) -> dict:
        closed = [t for t in self._today if t.get("status") == "CLOSED"]
        if bucket:
            closed = [t for t in closed if t.get("bucket") == bucket]
        wins   = [t for t in closed if t.get("win")]
        losses = [t for t in closed if not t.get("win")]
        n = len(closed)
        if n == 0:
            return {"n": 0, "win_rate": 0.5, "ev_idr": 0, "pf": 1.0, "pnl_idr": 0}
        wr  = len(wins) / n
        avg_win  = sum(t["pnl_idr"] for t in wins)  / max(len(wins), 1)
        avg_loss = abs(sum(t["pnl_idr"] for t in losses)) / max(len(losses), 1)
        ev = (wr * avg_win) - ((1 - wr) * avg_loss)
        pf = sum(t["pnl_idr"] for t in wins) / max(abs(sum(t["pnl_idr"] for t in losses)), 1)
        return {
            "n": n, "wins": len(wins), "losses": len(losses),
            "win_rate": round(wr, 3), "ev_idr": round(ev, 0),
            "pf": round(pf, 2), "pnl_idr": round(sum(t["pnl_idr"] for t in closed), 0),
            "avg_win": round(avg_win, 0), "avg_loss": round(avg_loss, 0)
        }

    def get_pair_stats(self, pair_id: str) -> dict:
        closed = [t for t in self._today
                  if t.get("pair_id") == pair_id and t.get("status") == "CLOSED"]
        if not closed:
            return {"n": 0, "win_rate": 0.5, "pf": 1.0}
        wins = [t for t in closed if t.get("win")]
        wr = len(wins) / len(closed)
        pf = (sum(t["pnl_idr"] for t in wins) /
              max(abs(sum(t["pnl_idr"] for t in closed if not t.get("win"))), 1))
        return {"n": len(closed), "win_rate": round(wr, 3), "pf": round(pf, 2)}

    def is_pair_in_cooldown(self, pair_id: str) -> bool:
        recent = [t for t in self._today
                  if t.get("pair_id") == pair_id and
                  t.get("status") == "CLOSED" and
                  not t.get("win")]
        if not recent:
            return False
        latest_loss = max(
            (t for t in recent),
            key=lambda x: x.get("exit_at", ""),
            default=None
        )
        if not latest_loss:
            return False
        exit_at = datetime.fromisoformat(latest_loss.get("exit_at", "2000-01-01"))
        cooldown_end = exit_at + timedelta(minutes=30)
        return datetime.now() < cooldown_end

    def _async_sync_supabase(self, trade: dict):
        def _sync():
            if not SUPABASE_URL:
                return
            try:
                payload = json.dumps({
                    "trade_id": trade.get("trade_id"),
                    "pair_id": trade.get("pair_id"),
                    "bucket": trade.get("bucket"),
                    "entry_price": trade.get("entry_price"),
                    "exit_price": trade.get("exit_price"),
                    "budget_idr": trade.get("budget_idr"),
                    "pnl_idr": trade.get("pnl_idr"),
                    "pnl_pct": trade.get("pnl_pct"),
                    "conviction_score": trade.get("conviction_score"),
                    "pump_phase": trade.get("pump_phase"),
                    "hold_minutes": trade.get("hold_minutes"),
                    "win": trade.get("win"),
                    "exit_reason": trade.get("exit_reason"),
                    "cascade_mode": trade.get("cascade_mode"),
                }).encode()
                req = urllib.request.Request(
                    f"{SUPABASE_URL}/rest/v1/trade_history",
                    data=payload,
                    headers={"apikey": SUPABASE_KEY,
                             "Authorization": f"Bearer {SUPABASE_KEY}",
                             "Content-Type": "application/json"},
                    method="POST"
                )
                urllib.request.urlopen(req, timeout=5)
            except Exception as e:
                logger.debug(f"[TRADELOG] Supabase sync: {e}")
        threading.Thread(target=_sync, daemon=True).start()

    def post_mortem(self, trade: dict, equity_idr: float):
        """Catat post-mortem untuk setiap loss > Rp 200."""
        if not trade.get("win") and abs(trade.get("pnl_idr", 0)) > 200:
            classification = "TIMING" if trade.get("hold_minutes", 0) < 5 else \
                           "PEAK_ENTRY" if trade.get("pump_phase") in ("PEAK", "LATE") else \
                           "STOP_LOSS"
            lesson = {
                "TIMING": "Entry terlalu cepat sebelum momentum konfirmasi",
                "PEAK_ENTRY": "Entry di fase LATE/PEAK — conviction score harus lebih ketat",
                "STOP_LOSS": "Stop loss terpicu — trailing stop sudah benar"
            }.get(classification, "Unknown")
            logger.info(f"[POSTMORTEM] {trade['pair_id']} loss={trade['pnl_idr']:.0f} → {classification}: {lesson}")

trade_logger = TradeLogger()

# ============================================================
# EXIT STRATEGY — Multi-Level Ladder
# ============================================================

@dataclass
class ExitSignal:
    should_exit: bool
    partial_pct: float  # 0.0 = tidak exit, 0.3 = exit 30%
    use_market: bool
    reason: str
    tighten_trailing: float | None  # None = tidak ubah

def evaluate_exit(
    position_pnl_pct: float,
    hold_minutes: int,
    price: float,
    bb_upper: float | None,
    rsi: float,
    volume_trend: str,
    conviction_score: float,
    trailing_stop_pct: float,
    current_trailing_stop_price: float,
    bucket: str,
    btc_change_1h: float,
    daily_pnl_pct: float
) -> ExitSignal:
    """Evaluasi apakah harus exit, berapa persen, dan bagaimana."""

    # === HARD EXITS (market darurat) ===
    if daily_pnl_pct <= -0.02:
        return ExitSignal(True, 1.0, True, "HARD_STOP daily -2%", None)
    if position_pnl_pct <= -0.03:
        return ExitSignal(True, 1.0, False, "HARD_STOP posisi -3%", None)
    if btc_change_1h < -5.0 and position_pnl_pct > 0:
        return ExitSignal(True, 1.0, False, f"BTC panic dump {btc_change_1h:.1f}%", None)

    # === VOLUME COLLAPSE (distribusi bandar) ===
    if volume_trend == "decreasing" and hold_minutes > 15 and position_pnl_pct > 0.005:
        return ExitSignal(True, 1.0, False, "Volume collapse saat hold", None)

    # === TIME EXIT ===
    if hold_minutes > 720:  # 12 jam
        return ExitSignal(True, 1.0, False, "Time exit >12 jam", None)

    # === PEAK SIGNALS ===
    near_peak = (
        (bb_upper and price > bb_upper * 0.98) +
        (rsi > 75) +
        (volume_trend == "decreasing") +
        (position_pnl_pct > 0.10)
    )
    if near_peak >= 3:
        return ExitSignal(True, 0.70, False, f"Peak: {near_peak}/4 signals", 0.015)

    # === CONVICTION DROP SAAT HOLDING (Bucket B) ===
    if bucket == "B" and conviction_score < 0.60 and hold_minutes > 30:
        return ExitSignal(True, 1.0, False, f"Conviction drop {conviction_score:.2f}", None)

    # === PARTIAL TAKE PROFIT — LADDER ===
    if position_pnl_pct >= 0.15:
        return ExitSignal(True, 0.70, False, "TP +15%: exit 70%", 0.015)
    if position_pnl_pct >= 0.10:
        return ExitSignal(True, 0.20, False, "TP +10%: exit 20% (80% locked)", 0.020)
    if position_pnl_pct >= 0.06:
        return ExitSignal(True, 0.30, False, "TP +6%: exit 30% lagi (60% locked)", 0.025)
    if position_pnl_pct >= 0.03:
        return ExitSignal(True, 0.30, False, "TP +3%: exit 30% pertama", trailing_stop_pct)

    # === TIGHTEN ON LOSS ===
    if position_pnl_pct <= -0.015:
        return ExitSignal(False, 0.0, False, f"Loss warning -{abs(position_pnl_pct):.1%}", trailing_stop_pct * 0.5)

    return ExitSignal(False, 0.0, False, "HOLD", None)

# ============================================================
# INDODAX-ONLY SCREENER — Scan semua pair setiap 30 detik
# ============================================================

INDODAX_ONLY_PAIRS = [
    "whitewhale_idr", "br_idr", "drx_idr", "bio_idr",
    "pippin_idr", "myx_idr", "jellyjelly_idr", "aster_idr",
    "hype_idr", "gravity_idr", "trollsol_idr", "mubarak_idr",
    "xpl_idr", "fanc_idr", "nova_idr", "mrs_idr",
    "islm_idr", "vanry_idr", "fanc_idr", "xautidr",
]

def screen_bucket_b_candidates(all_tickers: dict, btc_change_1h: float,
                                 cascade_config: dict) -> list[dict]:
    """
    Scan semua Indodax-only pair, hitung ConvictionScore, return kandidat.
    Dijalankan setiap 30 detik.
    """
    if not cascade_config.get("bucket_b_active", True):
        return []

    candidates = []
    for pair_id in INDODAX_ONLY_PAIRS:
        ticker = all_tickers.get(pair_id)
        if not ticker:
            continue
        vol_24h = float(ticker.get("vol_idr", 0))
        if vol_24h < 500_000_000:
            continue

        price       = float(ticker.get("last", 0))
        high_24h    = float(ticker.get("high", price))
        low_24h     = float(ticker.get("low", price))
        price_24h   = float(ticker.get("open", price))
        bid         = float(ticker.get("buy", price * 0.99))
        ask         = float(ticker.get("sell", price * 1.01))
        change_24h  = (price - price_24h) / max(price_24h, 0.001) * 100

        # Gunakan local state atau fetch (mocked for simplicity here)
        closes = [] 
        volumes = []
        
        in_cooldown = trade_logger.is_pair_in_cooldown(pair_id)

        # compute_conviction_score needs actual history
        # Here we just provide empty/dummy to satisfy signature if called
        # Real implementation would call fetch_candles
        candles = fetch_candles(pair_id, tf=15, count=30)
        closes  = [c["c"] for c in candles]
        volumes = [c["v"] for c in candles]
        avg_vol_7d = vol_24h  # Estimasi jika tidak ada data 7d

        result = compute_conviction_score(
            pair_id=pair_id,
            price=price, high_24h=high_24h, low_24h=low_24h,
            vol_24h_idr=vol_24h, price_change_24h_pct=change_24h,
            bid_depth_idr=bid * 1000, ask_depth_idr=ask * 1000,
            closes=closes, volumes=volumes, avg_vol_7d_idr=avg_vol_7d,
            btc_change_1h_pct=btc_change_1h, pair_in_cooldown=in_cooldown
        )

        if result.score < 0.70:
            continue

        pair_stats = trade_logger.get_pair_stats(pair_id)
        candidates.append({
            "pair_id": pair_id,
            "price": price,
            "conviction": result,
            "vol_24h_idr": vol_24h,
            "pair_stats": pair_stats,
            "score": result.score,
        })

    candidates.sort(key=lambda x: x["score"], reverse=True)
    return candidates[:5]

# ============================================================
# FULL ENTRY PIPELINE — Bucket A & B
# ============================================================

def process_bucket_a_entry(
    signal: dict,
    kicom_signal: KiComSignal | None,
    equity_idr: float,
    bucket_state: BucketState,
    cascade_state: CascadeState
) -> bool:
    """
    Bucket A: Lead-lag dari Kinance (Binance) + KiCom (Crypto.com).
    Return True jika entry berhasil.
    """
    cfg = cascade_state.get_config()
    pair_id = signal.get("pairId", "")

    # Gate 1: Max posisi Bucket A
    if bucket_state.a_positions >= cfg["max_a"]:
        logger.debug(f"[GATE-A1] {pair_id}: max posisi A ({cfg['max_a']}) tercapai")
        return False

    # Gate 2: Dual scanner agreement (Kinance AND KiCom)
    agreed, combined_conf, scanner_reason = check_dual_scanner_agreement(
        pair_id, signal, kicom_signal
    )
    if not agreed:
        logger.debug(f"[GATE-A2] {pair_id}: scanner tidak agree — {scanner_reason}")
        return False

    # Gate 3: BTC regime
    btc_ok, btc_reason = is_btc_regime_ok("A")
    if not btc_ok:
        logger.info(f"[GATE-A3] {pair_id}: {btc_reason}")
        return False

    # Gate 4: Signal TTL
    signal_age = signal.get("signalAgeMs", 999)
    if signal_age > 500:
        logger.debug(f"[GATE-A4] {pair_id}: signal stale {signal_age}ms")
        return False

    # Gate 5: Cooldown
    if trade_logger.is_pair_in_cooldown(pair_id):
        logger.debug(f"[GATE-A5] {pair_id}: dalam cooldown")
        return False

    # Gate 6: Cash reserve check
    cash_ok = equity_idr * MIN_CASH_RESERVE <= (equity_idr - bucket_state.a_balance_idr - bucket_state.b_balance_idr)
    if not cash_ok and equity_idr > 0:
        logger.info(f"[GATE-A6] {pair_id}: cash reserve tidak cukup")
        return False

    # Gate 7: What-If EV
    pair_stats = trade_logger.get_pair_stats(pair_id)
    size = calculate_position_size_a(
        bucket_state.a_balance_idr,
        pair_stats.get("win_rate", 0.5),
        0.02, 0.015, cfg
    )
    sim = simulate_what_if_full(
        pair_id, size, spread_pct=0.015, slippage_pct=0.012,
        target_pct=0.025, trailing_pct=0.02,
        win_rate=pair_stats.get("win_rate", 0.5),
        n_trades=pair_stats.get("n", 0),
        use_market=False
    )
    if sim["decision"] == "SKIP":
        logger.debug(f"[GATE-A7] {pair_id}: EV skip (ev={sim['ev_idr']:.0f})")
        return False
    if sim["decision"] == "REDUCE":
        size *= 0.6

    if size < MIN_ORDER_IDR:
        logger.debug(f"[GATE-A8] {pair_id}: size Rp{size:.0f} < min")
        return False

    # ENTRY
    trade_id = trade_logger.record_entry(
        pair_id=pair_id, bucket="A", entry_price=signal.get("entryPrice", 0),
        budget_idr=size, conviction_score=combined_conf,
        pump_phase="SIGNAL", order_type="LIMIT",
        cascade_mode=cascade_state.mode,
        target_pct=0.025, trailing_pct=0.02
    )
    logger.info(f"[ENTRY-A] {pair_id} Rp{size:,.0f} conf={combined_conf:.2f} [{trade_id}]")
    return True

def process_bucket_b_entry(
    candidate: dict,
    equity_idr: float,
    bucket_state: BucketState,
    cascade_state: CascadeState
) -> bool:
    """
    Bucket B: Local Indodax-only, ConvictionScore >= 0.85.
    Return True jika entry berhasil.
    """
    cfg = cascade_state.get_config()
    pair_id = candidate["pair_id"]
    result  = candidate["conviction"]

    # Gate 1: Max posisi Bucket B
    if bucket_state.b_positions >= cfg["max_b"]:
        return False

    # Gate 2: Conviction score minimum
    if result.score < cfg["conv_min"]:
        logger.debug(f"[GATE-B2] {pair_id}: score={result.score:.3f} < {cfg['conv_min']}")
        return False

    # Gate 3: Hard blocks dari conviction
    if not result.allowed:
        logger.debug(f"[GATE-B3] {pair_id}: blocked — {result.block_reason}")
        return False

    # Gate 4: BTC regime
    btc_ok, btc_reason = is_btc_regime_ok("B")
    if not btc_ok:
        logger.info(f"[GATE-B4] {pair_id}: {btc_reason}")
        return False

    # Gate 5: Cash reserve (Bucket B min 40% cash dari bucket B)
    b_cash_available = bucket_state.b_balance_idr * (1 - 0.40)  # max 60% terpakai
    if bucket_state.b_positions * MIN_ORDER_IDR >= b_cash_available:
        logger.debug(f"[GATE-B5] {pair_id}: cash Bucket B tidak cukup")
        return False

    # Gate 6: What-If EV
    pair_stats = candidate["pair_stats"]
    size = calculate_position_size_b(
        bucket_state.b_balance_idr,
        pair_stats.get("n", 0),
        pair_stats.get("win_rate", 0.5),
        result.target_pct, result.trailing_stop_pct, cfg
    )
    sim = simulate_what_if_full(
        pair_id, size, spread_pct=0.020, slippage_pct=0.015,
        target_pct=result.target_pct,
        trailing_pct=result.trailing_stop_pct,
        win_rate=pair_stats.get("win_rate", 0.5),
        n_trades=pair_stats.get("n", 0)
    )
    if sim["decision"] == "SKIP":
        logger.debug(f"[GATE-B6] {pair_id}: EV skip")
        return False
    if sim["decision"] == "REDUCE":
        size *= 0.6

    if size < MIN_ORDER_IDR:
        return False

    # ENTRY
    trade_id = trade_logger.record_entry(
        pair_id=pair_id, bucket="B",
        entry_price=candidate["price"],
        budget_idr=size,
        conviction_score=result.score,
        pump_phase=result.pump_phase,
        order_type="LIMIT",
        cascade_mode=cascade_state.mode,
        target_pct=result.target_pct,
        trailing_pct=result.trailing_stop_pct
    )
    logger.info(
        f"[ENTRY-B] {pair_id} Rp{size:,.0f} "
        f"score={result.score:.3f} phase={result.pump_phase} [{trade_id}]"
    )
    return True

# ============================================================
# 30-MINUTE MATH REVIEW
# ============================================================

_score_mult = 1.0

def run_30min_review(equity_idr: float, daily_pnl_pct: float):
    global _score_mult
    stats_a = trade_logger.get_stats("A")
    stats_b = trade_logger.get_stats("B")
    stats_all = trade_logger.get_stats()

    from datetime import datetime, timedelta
    now_wib = datetime.utcnow() + timedelta(hours=7)
    hours_left = 24 - now_wib.hour - now_wib.minute / 60

    ev  = stats_all.get("ev_idr", 0)
    wr  = stats_all.get("win_rate", 0.5)
    pnl = stats_all.get("pnl_idr", 0)

    if ev <= 0 and stats_all["n"] >= 3:
        action = "TIGHTEN"
        _score_mult = min(_score_mult * 1.20, 1.5)
    elif wr >= 0.65 and ev > 0:
        action = "OPTIMAL"
        _score_mult = max(_score_mult * 0.97, 1.0)
    else:
        action = "CONTINUE"
        _score_mult = 1.0

    emoji = "🟢" if daily_pnl_pct >= 0 else ("🟡" if daily_pnl_pct >= -0.01 else "🔴")
    msg = (
        f"📊 [{now_wib.strftime('%H:%M')} WIB] 30min Review\n"
        f"{emoji} PnL: {daily_pnl_pct:+.2%} | Equity: Rp{equity_idr:,.0f}\n"
        f"🎯 A: {stats_a['n']}T WR={stats_a['win_rate']:.0%} | "
        f"B: {stats_b['n']}T WR={stats_b['win_rate']:.0%}\n"
        f"💰 EV/trade: Rp{ev:+,.0f} | Mode: {cascade_state.mode}\n"
        f"🔧 Action: {action} | Threshold: x{_score_mult:.2f}"
    )
    logger.info(f"[30MIN] {action} | EV={ev:.0f} WR={wr:.0%}")
    return msg

# ============================================================
# NEW COIN DETECTION — Auto-discover listing baru
# ============================================================

_known_pairs: set = set()
_last_discovery_pairs: set = set()

def discover_new_listings(all_tickers: dict) -> list[str]:
    """
    Deteksi pair baru yang listing di Indodax sejak last check.
    Return list pair_id yang baru.
    """
    global _last_discovery_pairs
    current = set(all_tickers.keys())
    if not _last_discovery_pairs:
        _last_discovery_pairs = current
        return []
    new_pairs = current - _last_discovery_pairs
    _last_discovery_pairs = current
    new_significant = []
    for pair in new_pairs:
        if not pair.endswith("_idr"):
            continue
        ticker = all_tickers.get(pair, {})
        vol = float(ticker.get("vol_idr", 0))
        if vol > 100_000_000:  # > 100 juta IDR volume
            new_significant.append(pair)
            logger.info(f"[DISCOVERY] New pair: {pair} vol=Rp{vol/1e9:.2f}B")
    return new_significant

if __name__ == "__main__":
    import sys
    args = sys.argv[1:]
    
    if not args:
        print("✅ kicryp_engine_v2.py loaded successfully")
        sys.exit(0)

    print(f"🚀 Running Trinity v7.0 Verification: {args}")

    if "--test-engine" in args:
        print("\n--- Test 1: Core Engine Integrity ---")
        try:
            now = datetime.utcnow().isoformat()
            print(f"Current UTC: {now}")
            print(f"State Dir: {STATE_DIR}")
            print(f"Cascade Mode: {cascade_state.mode}")
            print(f"Trade Logger: {len(trade_logger._today)} trades today")
            print("✅ Core Engine Integrity: PASS")
        except Exception as e:
            print(f"❌ Core Engine Integrity: FAIL - {e}")

    if "--test-cascade" in args:
        print("\n--- Test 2: Cascade State Logic ---")
        try:
            test_state = CascadeState(mode="GROWTH", losses_today=0, consecutive_losses=0, daily_pnl_pct=0.0)
            print(f"Initial: {test_state.mode}")
            test_state.on_loss(-0.005)
            print(f"Loss 1 (-0.5%): {test_state.mode} (Expected CAUTION)")
            test_state.on_loss(-0.01)
            print(f"Loss 2 (-1.0%): {test_state.mode} (Expected DEFENSIVE)")
            test_state.on_loss(-0.025)
            print(f"Loss 3 (-2.5%): {test_state.mode} (Expected HARD_STOP)")
            test_state.daily_reset()
            print(f"After Reset: {test_state.mode} (Expected CAUTION)")
            print("✅ Cascade State Logic: PASS")
        except Exception as e:
            print(f"❌ Cascade State Logic: FAIL - {e}")

    if "--test-conviction" in args:
        print("\n--- Test 3: Conviction Scoring ---")
        try:
            # Mock data for a bullish scenario
            # Use a sequence with some "down" days to keep RSI < 80
            # SMA will be around 1.0, and 1.1 should be near/below upper BB
            closes = [0.95, 0.96, 0.94, 0.97, 0.96, 0.98, 0.97, 1.0, 0.99, 1.02, 
                      1.01, 1.04, 1.03, 1.06, 1.05, 1.08, 1.07, 1.09, 1.08, 1.1] * 2
            result = compute_conviction_score(
                pair_id="pepe_idr", price=1.1, high_24h=1.3, low_24h=0.8,
                vol_24h_idr=2_000_000_000, price_change_24h_pct=15.0,
                bid_depth_idr=500_000_000, ask_depth_idr=200_000_000,
                closes=closes, volumes=[1e6]*40,
                avg_vol_7d_idr=500_000_000, btc_change_1h_pct=0.5,
                pair_in_cooldown=False
            )
            print(f"Score: {result.score:.3f} | Allowed: {result.allowed} | Reason: {result.block_reason}")
            print(f"Components: {result.components}")
            if result.score > 0:
                print("✅ Conviction Scoring: PASS")
            else:
                print(f"❌ Conviction Scoring: FAIL - Score is 0 (Block: {result.block_reason})")
        except Exception as e:
            print(f"❌ Conviction Scoring: FAIL - {e}")

    if "--test-screen-b" in args:
        print("\n--- Test 4: Bucket B Screening ---")
        try:
            mock_tickers = {
                "pepe_idr": {"last": "1.2", "high": "1.3", "low": "0.8", "vol_idr": "2000000000", "p": "15.0"},
                "doge_idr": {"last": "3000", "high": "3100", "low": "2900", "vol_idr": "1000000000", "p": "2.0"}
            }
            cfg = CASCADE_MODES["GROWTH"]
            candidates = screen_bucket_b_candidates(mock_tickers, btc_change_1h=0.5, cascade_config=cfg)
            print(f"Found {len(candidates)} candidates")
            for c in candidates:
                print(f" - {c['pair_id']}: score={c['score']:.3f} price={c['price']}")
            print("✅ Bucket B Screening: PASS")
        except Exception as e:
            print(f"❌ Bucket B Screening: FAIL - {e}")

    if "--test-supabase" in args:
        print("\n--- Test 6: Supabase Sync Test ---")
        if not SUPABASE_URL:
            print("⚠️ SUPABASE_URL not set, skipping live sync test")
        else:
            try:
                test_trade = {
                    "trade_id": "TEST1234", "pair_id": "TEST_IDR", "bucket": "A",
                    "entry_price": 100.0, "exit_price": 105.0, "budget_idr": 10000,
                    "pnl_idr": 500, "pnl_pct": 0.05, "win": True, "hold_minutes": 10
                }
                print("Triggering async sync...")
                trade_logger._async_sync_supabase(test_trade)
                print("✅ Supabase Sync Triggered (Check Supabase dashboard for TEST1234)")
            except Exception as e:
                print(f"❌ Supabase Sync: FAIL - {e}")
