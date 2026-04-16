import json
import os
import random
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

try:
    import kicryp_manager as manager
except Exception:
    manager = None
from kicryp_learning_engine import (
    LearningEngine,
    PairStats,
    ROUND_TRIP_MAKER,
    ROUND_TRIP_TAKER,
    VWAPRegimeDetector,
)

PASS = 0
FAIL = 0


def check(name: str, condition: bool, detail: str = "") -> None:
    global PASS, FAIL
    if condition:
        print(f"PASS {name}")
        PASS += 1
    else:
        print(f"FAIL {name} {detail}".rstrip())
        FAIL += 1


stats = PairStats(pair="btc_idr")
for _ in range(10):
    stats.record_trade(0.015)
for _ in range(5):
    stats.record_trade(-0.008)
check("kelly positive", stats.kelly_fraction() > 0)
check("kelly capped", stats.kelly_fraction() <= 0.12)
check("bayesian win prob sane", 0.6 < stats.win_probability < 0.85)
check("profit factor > 1", stats.profit_factor > 1)

bad = PairStats(pair="bad")
for _ in range(5):
    bad.record_trade(-0.01)
check("no-edge kelly zero", bad.kelly_fraction() == 0.0)
allowed, _ = bad.should_entry()
check("no-edge blocked", not allowed)

lossy = PairStats(pair="lossy")
lossy.record_trade(-0.035)
allowed, _ = lossy.should_entry()
check("cooldown after big loss", not allowed)

check("maker fee round trip", abs(ROUND_TRIP_MAKER - 0.003) < 1e-9)
check("taker fee round trip", abs(ROUND_TRIP_TAKER - 0.006) < 1e-9)

engine = LearningEngine("/tmp/kicryp_learning_state.json")
random.seed(42)
for _ in range(20):
    if random.random() < 0.6:
        engine.record_trade("eth_idr", 0.015, True)
    else:
        engine.record_trade("eth_idr", -0.008, True)
check("engine kelly positive", engine.kelly_size("eth_idr") > 0)
check("engine kelly capped", engine.kelly_size("eth_idr") <= 0.12)

if manager is not None:
    check("effective fee pct sane", 0.0004 < manager._effective_fee_pct() < 0.0055)

    with patch("kicryp_manager.requests.get") as mocked_get:
        mocked_response = MagicMock()
        mocked_response.raise_for_status.return_value = None
        mocked_response.json.return_value = {"totalEquityIdr": 84_000}
        mocked_get.return_value = mocked_response
        check("minimum capital blocks tiny equity", not manager._check_minimum_capital())

    with patch("kicryp_manager.requests.get") as mocked_get:
        mocked_response = MagicMock()
        mocked_response.raise_for_status.return_value = None
        mocked_response.json.return_value = {"totalEquityIdr": 500_000}
        mocked_get.return_value = mocked_response
        check("minimum capital allows viable equity", manager._check_minimum_capital())

    what_if = manager._simulate_what_if(
        pair_id="btc_idr",
        entry_price=100.0,
        budget_idr=84_000.0,
        spread_pct=0.01,
        slippage_pct=0.02,
    )
    check("what-if exposes fee round trip", what_if["fee_round_trip_pct"] > 0.0)
    check("what-if skips when net negative", what_if["recommendation"] == "SKIP")

detector = VWAPRegimeDetector()
bullish = [{"close": 100 + i, "high": 101 + i, "low": 99 + i, "volume": 2000} for i in range(15)]
bearish = [{"close": 200 - i, "high": 201 - i, "low": 199 - i, "volume": 2500} for i in range(15)]
sideways = [{"close": 100 + (i % 3 - 1), "high": 102, "low": 98, "volume": 500} for i in range(15)]
panic = sideways[:14] + [{"close": 85, "high": 98, "low": 84, "volume": 8000}]
bullish[-1]["volume"] = 5000
bearish[-1]["volume"] = 6500
check("bullish regime", detector.detect(bullish) == "BULLISH")
check("bearish regime", detector.detect(bearish) == "BEARISH")
check("sideways regime", detector.detect(sideways) == "SIDEWAYS")
check("panic regime", detector.detect(panic) == "BREAKDOWN_PANIC")

try:
    guard_path = Path("state/daily_guard.json")
    gate_path = Path("state/manager_gate.json")
    if not guard_path.exists():
        guard_path.parent.mkdir(parents=True, exist_ok=True)
        guard_path.write_text(json.dumps({"hard_stopped": True, "daily_pnl_pct": -0.07, "reset_at": "2099-01-01T00:00:00Z"}))
    if not gate_path.exists():
        gate_path.parent.mkdir(parents=True, exist_ok=True)
        gate_path.write_text(json.dumps({"entry_state": "SUSPENDED"}))
    guard = json.loads(guard_path.read_text())
    gate = json.loads(gate_path.read_text())
    check("hard stop active", guard.get("hard_stopped") is True)
    check("manager suspended", gate.get("entry_state") == "SUSPENDED")
except Exception as error:
    check("state readable", False, str(error))

print(f"RESULT {PASS} PASS {FAIL} FAIL")
if FAIL:
    raise SystemExit(1)
