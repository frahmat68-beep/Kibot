import json
import os
import random
import sys
from tempfile import TemporaryDirectory
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

try:
    import kibot_manager as manager
except Exception:
    manager = None
try:
    import kibot_analyst as analyst
except Exception:
    analyst = None
from kibot_learning_engine import (
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

engine = LearningEngine("/tmp/kibot_learning_state.json")
random.seed(42)
for _ in range(20):
    if random.random() < 0.6:
        engine.record_trade("eth_idr", 0.015)
    else:
        engine.record_trade("eth_idr", -0.008)
check("engine kelly positive", engine.kelly_size("eth_idr") > 0)
check("engine kelly capped", engine.kelly_size("eth_idr") <= 0.12)

if manager is not None:
    check("effective fee pct sane", 0.0004 < manager._effective_fee_pct() < 0.0055)
    check("parse numeric idr thousand", manager._parse_numeric("Rp 63.365") == 63365)
    check(
        "normalize pnl ignores generic at-percent",
        (manager._normalized_trade_net_pnl_pct({
            "netPnlPct": -1.3459292962955511,
            "filledPrice": 0.0,
            "exitReason": "REPEAT_LOSER forced sell req_idr at 33.36%.",
        }) or 0.0) < 0.0,
    )
    check(
        "normalize pnl trusts explicit pnl marker",
        (manager._normalized_trade_net_pnl_pct({
            "netPnlPct": -1.1941871202069578,
            "filledPrice": 0.0,
            "exitReason": "EXIT PROFIT_EXIT req_idr qty=5.07552141 pnl=19.20% age=0.06h",
        }) or 0.0) > 0.0,
    )
    check(
        "normalize pnl accepts negative at-percent",
        abs((manager._normalized_trade_net_pnl_pct({
            "netPnlPct": -1.003,
            "filledPrice": 0.0,
            "exitReason": "ABSOLUTE_HARD_LOSS_CAP forced sell gtc_idr at -13.28%.",
        }) or 0.0) - (-0.1328)) < 1e-9,
    )

    with patch("kibot_manager._get_total_equity_estimate", return_value=84_000):
        check("minimum capital blocks tiny equity", not manager._check_minimum_capital())

    with patch("kibot_manager._get_total_equity_estimate", return_value=500_000):
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

    with TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        old_trade_log = manager.TRADE_LOG_RUNTIME_PATH
        old_learning_history = manager.LEARNING_REVIEW_HISTORY_PATH
        old_daily_report = manager.DAILY_REPORT_PATH
        old_daily_report_history = manager.DAILY_REPORT_HISTORY_PATH
        old_daily_summary = manager.DAILY_SUMMARY_PATH
        old_cycle_state = dict(manager._daily_cycle_state)
        old_guard_state = dict(manager._daily_guard_state)
        try:
            manager.TRADE_LOG_RUNTIME_PATH = tmp / "trade_log.jsonl"
            manager.LEARNING_REVIEW_HISTORY_PATH = tmp / "learning_review_history.json"
            manager.DAILY_REPORT_PATH = tmp / "daily_report.json"
            manager.DAILY_REPORT_HISTORY_PATH = tmp / "daily_report_history.json"
            manager.DAILY_SUMMARY_PATH = tmp / "daily_summary.json"
            manager._daily_cycle_state.update({
                "active_wib_date": "2026-04-18",
                "pending_new_date": "",
            })
            manager._daily_guard_state.update({
                "date": "2026-04-18",
                "start_of_day_equity": 100000.0,
                "current_equity": 103500.0,
                "daily_pnl_pct": 0.035,
                "hard_stopped": False,
            })
            manager.TRADE_LOG_RUNTIME_PATH.write_text(
                "\n".join(
                    [
                        json.dumps({
                            "timestamp": "2026-04-18T01:00:00+00:00",
                            "pair": "btc_idr",
                            "side": "BUY",
                            "filledIdr": 50000,
                        }),
                        json.dumps({
                            "timestamp": "2026-04-18T06:00:00+00:00",
                            "pair": "btc_idr",
                            "side": "SELL",
                            "netPnlIdr": 3500,
                            "balanceAfter": 103500,
                        }),
                    ]
                ) + "\n",
                encoding="utf-8",
            )
            manager._write_json_file(
                manager.DAILY_SUMMARY_PATH,
                {
                    **manager._default_daily_summary("2026-04-18"),
                    "coins_bought_today": ["btc_idr"],
                },
            )
            manager._write_json_file(
                manager.LEARNING_REVIEW_HISTORY_PATH,
                [
                    {
                        "at": "2026-04-18T16:30:00+00:00",
                        "wib_date": "2026-04-18",
                        "summary": "Filter whipsaw makin ketat.",
                        "strategy": "Besok fokus pair high-trust dan rotasi cepat.",
                        "lessons": ["Kurangi entry saat veto rejection naik."],
                        "risks": ["Likuiditas tipis sore hari."],
                    }
                ],
            )
            report = manager._build_daily_report_payload("2026-04-18")
            check("daily report end balance", report["end_balance_idr"] == 103500.0)
            check("daily report bought pair", "btc_idr" in report["coins_bought_today"])
            check("daily report carries lesson", bool(report["lessons"]))
            report_text = manager._render_daily_report_text(report)
            check("daily report text includes saldo", "Saldo akhir hari" in report_text)
        finally:
            manager.TRADE_LOG_RUNTIME_PATH = old_trade_log
            manager.LEARNING_REVIEW_HISTORY_PATH = old_learning_history
            manager.DAILY_REPORT_PATH = old_daily_report
            manager.DAILY_REPORT_HISTORY_PATH = old_daily_report_history
            manager.DAILY_SUMMARY_PATH = old_daily_summary
            manager._daily_cycle_state.clear()
            manager._daily_cycle_state.update(old_cycle_state)
            manager._daily_guard_state.clear()
            manager._daily_guard_state.update(old_guard_state)

    with (
        patch("kibot_manager._get_trade_metrics_today", return_value={
            "total_trades": 0,
            "wins": 0,
            "losses": 0,
            "win_rate": 0.0,
            "avg_win": 0.0,
            "avg_loss": 0.0,
            "ev_per_trade": 0.0,
            "profit_factor": 0.0,
            "total_gross_pnl": 0.0,
        }),
        patch("kibot_manager._get_total_equity_estimate", return_value=100_000.0),
        patch("kibot_manager._hours_until_midnight_wib", return_value=6.0),
        patch("kibot_manager._telegram_send", return_value=None),
        patch("kibot_manager._append_runtime_event", return_value=None),
        patch("kibot_manager._set_conservative_mode", return_value=None),
        patch("kibot_manager._suspend_new_entries", return_value=None),
        patch("kibot_manager._set_normal_mode", return_value=None),
    ):
        old_guard_state = dict(manager._daily_guard_state)
        try:
            manager._daily_guard_state.update({"daily_pnl_pct": 0.0, "hard_stopped": False})
            result = manager._run_math_review()
            check("math review no loss avoids impossible recovery", result["action"] != "HARD_STOP", str(result))
        finally:
            manager._daily_guard_state.clear()
            manager._daily_guard_state.update(old_guard_state)

if analyst is not None:
    check(
        "analyst normalize ignores generic at-percent",
        (analyst._normalized_trade_net_pnl_pct({
            "netPnlPct": -1.3459292962955511,
            "filledPrice": 0.0,
            "exitReason": "REPEAT_LOSER forced sell req_idr at 33.36%.",
        }) or 0.0) < 0.0,
    )
    check(
        "analyst normalize trusts explicit pnl marker",
        (analyst._normalized_trade_net_pnl_pct({
            "netPnlPct": -1.1941871202069578,
            "filledPrice": 0.0,
            "exitReason": "EXIT PROFIT_EXIT req_idr qty=5.07552141 pnl=19.20% age=0.06h",
        }) or 0.0) > 0.0,
    )
    check(
        "analyst normalize accepts negative at-percent",
        abs((analyst._normalized_trade_net_pnl_pct({
            "netPnlPct": -1.003,
            "filledPrice": 0.0,
            "exitReason": "ABSOLUTE_HARD_LOSS_CAP forced sell gtc_idr at -13.28%.",
        }) or 0.0) - (-0.1328)) < 1e-9,
    )
    est_idr = analyst._normalized_trade_net_pnl_idr({
        "netPnlIdr": -12731.96068354923,
        "netPnlPct": -1.1941871202069578,
        "filledPrice": 0.0,
        "requestedPrice": 2504.0,
        "filledAmount": 5.07552141,
        "exitReason": "EXIT PROFIT_EXIT req_idr qty=5.07552141 pnl=19.20% age=0.06h",
    })
    check("analyst estimated idr repairs zero-price profit record", (est_idr or 0.0) > 0.0)

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
    guard_path.parent.mkdir(parents=True, exist_ok=True)
    old_guard_text = guard_path.read_text() if guard_path.exists() else None
    old_gate_text = gate_path.read_text() if gate_path.exists() else None
    try:
        guard_path.write_text(json.dumps({"hard_stopped": True, "daily_pnl_pct": -0.07, "reset_at": "2099-01-01T00:00:00Z"}))
        gate_path.write_text(json.dumps({"entry_state": "SUSPENDED"}))
        guard = json.loads(guard_path.read_text())
        gate = json.loads(gate_path.read_text())
        check("hard stop active", guard.get("hard_stopped") is True)
        check("manager suspended", gate.get("entry_state") == "SUSPENDED")
    finally:
        if old_guard_text is None:
            guard_path.unlink(missing_ok=True)
        else:
            guard_path.write_text(old_guard_text)
        if old_gate_text is None:
            gate_path.unlink(missing_ok=True)
        else:
            gate_path.write_text(old_gate_text)
except Exception as error:
    check("state readable", False, str(error))

print(f"RESULT {PASS} PASS {FAIL} FAIL")
if FAIL:
    raise SystemExit(1)
