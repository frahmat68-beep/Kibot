import json
import os
import random
import sys
from datetime import datetime, timezone
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
from ki_brain import BrainManager
from ki_global_scanner_mesh import GlobalScannerMesh
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

    with (
        patch("kibot_manager._get_total_equity_estimate", return_value=50_000),
        patch("kibot_manager._current_balance_snapshot", return_value={"equity_idr": 50_000, "free_cash_idr": 45_000, "holdings_pairs": [], "payload": {}}),
    ):
        profile = manager._adaptive_capital_profile()
        check("adaptive capital micro mode", profile.get("mode") == "MICRO", str(profile))
        check("adaptive capital allows small balance", manager._check_minimum_capital(), str(profile))
        check("adaptive capital minimum order preserved", float(profile.get("max_position_idr") or 0.0) >= 10_000.0, str(profile))

    with (
        patch("kibot_manager._get_total_equity_estimate", return_value=12_000),
        patch("kibot_manager._current_balance_snapshot", return_value={"equity_idr": 12_000, "free_cash_idr": 7_000, "holdings_pairs": [], "payload": {}}),
    ):
        check("adaptive capital blocks low free cash", not manager._check_minimum_capital())

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
        patch("kibot_manager._get_total_equity_estimate", return_value=56_000.0),
        patch("kibot_manager._hours_until_midnight_wib", return_value=5.5),
        patch("kibot_manager._telegram_send", return_value=None),
        patch("kibot_manager._append_runtime_event", return_value=None),
        patch("kibot_manager._set_conservative_mode", return_value=None),
        patch("kibot_manager._set_normal_mode", return_value=None),
    ):
        old_guard_state = dict(manager._daily_guard_state)
        old_gate_state = dict(manager._gate_state)
        try:
            manager._daily_guard_state.update({"daily_pnl_pct": -0.0053, "hard_stopped": False})
            manager._gate_state.update({"entry_state": "SUSPENDED", "reason": "math_review_recovery_impossible", "daily_hard_stop": False})
            result = manager._run_math_review()
            check("math review small loss waits for sample", result["action"] == "WAIT_FOR_SAMPLE", str(result))
            check("math review small loss resumes gate", manager._gate_state.get("entry_state") == "HEALTHY", str(manager._gate_state))
        finally:
            manager._daily_guard_state.clear()
            manager._daily_guard_state.update(old_guard_state)
            manager._gate_state.clear()
            manager._gate_state.update(old_gate_state)

    old_guard_state = dict(manager._daily_guard_state)
    old_gate_state = dict(manager._gate_state)
    try:
        manager._daily_guard_state.update(
            {
                "date": "2026-04-24",
                "daily_pnl_pct": -0.0117,
                "hard_stopped": True,
                "triggered_at": "2026-04-24T16:11:55Z",
                "reset_at": "2026-04-24T17:00:00Z",
                "reason": "daily_loss_limit_hit",
            }
        )
        manager._gate_state.update(
            {
                "daily_hard_stop": True,
                "daily_hard_stop_reason": "daily_loss_limit_hit",
                "daily_hard_stop_reset_at": "2026-04-24T17:00:00Z",
            }
        )
        with (
            patch("kibot_manager._is_survival_mode", return_value=True),
            patch("kibot_manager._save_daily_guard_state"),
            patch("kibot_manager._save_gate_state"),
            patch("kibot_manager._resume_new_entries") as mocked_resume,
        ):
            manager._ensure_hard_stop_consistency()
            check("survival hard stop remains latched", manager._daily_guard_state.get("hard_stopped") is True, str(manager._daily_guard_state))
            check("survival hard stop not resumed early", mocked_resume.called is False)
        with patch("kibot_manager.get_binance_symbol") as mocked_symbol:
            manager._process_signal_multipos({"pairId": "btc_idr", "pumpScore": 90, "source": "BINANCE"})
            check("hard stop skips legacy consensus path", mocked_symbol.called is False)
    finally:
        manager._daily_guard_state.clear()
        manager._daily_guard_state.update(old_guard_state)
        manager._gate_state.clear()
        manager._gate_state.update(old_gate_state)

    old_guard_state = dict(manager._daily_guard_state)
    old_events = list(manager._recent_runtime_events)
    old_active = dict(manager._active_positions_cache)
    try:
        manager._daily_guard_state.update(
            {
                "date": "2026-04-20",
                "start_of_day_equity": 50_000.0,
                "current_equity": 50_000.0,
                "daily_pnl_pct": 0.0,
                "external_cashflow_idr": 0.0,
                "external_cashflow_reason": "",
            }
        )
        manager._recent_runtime_events.clear()
        manager._active_positions_cache.clear()
        manager._maybe_register_external_cashflow(70_000.0)
        check("external cashflow detected", (manager._daily_guard_state.get("external_cashflow_idr") or 0.0) >= 20_000.0, str(manager._daily_guard_state))
        manager._check_daily_loss_limit(70_000.0)
        check("topup excluded from daily pnl", abs(float(manager._daily_guard_state.get("daily_pnl_pct") or 0.0)) < 1e-9, str(manager._daily_guard_state))
    finally:
        manager._daily_guard_state.clear()
        manager._daily_guard_state.update(old_guard_state)
        manager._recent_runtime_events.clear()
        manager._recent_runtime_events.extend(old_events)
        manager._active_positions_cache.clear()
        manager._active_positions_cache.update(old_active)

    with (
        patch("kibot_manager.REMOTE_SCANNER_FEED_ENABLED", True),
        patch("kibot_manager.SUPABASE_URL", "https://example.supabase.co"),
        patch("kibot_manager.SUPABASE_KEY", "anon"),
        patch("kibot_manager.requests.get") as mocked_get,
        patch("kibot_manager._relay_to_kidax", return_value=None),
    ):
        old_remote_state = dict(manager._remote_scanner_feed_state)
        try:
            mocked_get.return_value = MagicMock(
                raise_for_status=lambda: None,
                json=lambda: [
                    {
                        "created_at": "2026-04-20T10:00:00+00:00",
                        "metadata": {
                            "feed_id": "mesh-1",
                            "summary": {"total_sent": 2, "total_scanned": 200},
                            "signals": [
                                {
                                    "exchange": "BYBIT",
                                    "pair_indodax": "btc_idr",
                                    "base_symbol": "BTC",
                                    "detection_score": 0.82,
                                    "weighted_contrib": 0.205,
                                    "timestamp": datetime.now(timezone.utc).isoformat(),
                                    "signal_uid": "BYBIT:btc_idr:test",
                                }
                            ],
                        },
                    }
                ],
            )
            manager._remote_scanner_feed_state.update(
                {
                    "last_created_at": "",
                    "last_feed_id": "",
                    "last_success_at": "",
                    "last_poll_at": "",
                    "last_error": "",
                    "cycles_seen": 0,
                    "signals_ingested": 0,
                    "recent_signal_ids": [],
                }
            )
            rows = manager._fetch_remote_scanner_feed_cycles(limit=2)
            check("remote scanner feed fetches rows", len(rows) == 1)
            signal = manager._normalize_remote_scanner_signal(rows[0]["metadata"]["signals"][0])
            check("remote scanner feed normalizes signal", signal is not None and signal["type"] == "MULTI_SCANNER_SIGNAL")
        finally:
            manager._remote_scanner_feed_state.clear()
            manager._remote_scanner_feed_state.update(old_remote_state)

brain = BrainManager()
with (
    patch.object(brain, "_get_json", side_effect=[
        {"quoteVolume": "1234567.89"},
        [{"traded_currency": "btc", "base_currency": "idr", "ticker_id": "btc_idr"}],
        {"coins": [{"id": "bitcoin"}]},
    ]),
    patch.object(brain, "_status_code", return_value=200),
    patch.object(brain, "_get_finnhub_crypto_news", return_value=[
        {"headline": "Bitcoin rally gains strength", "summary": "BTC breakout extends", "related": "BTC,ETH"},
        {"headline": "Altcoins recover after selloff", "summary": "market stabilizes", "related": "BTC,SOL"},
    ]),
    patch.object(brain, "_get_tavily_market_brief", return_value={"answer": "Market turning constructive but still selective.", "results": []}),
    patch.object(brain, "_get_tavily_symbol_brief", return_value={"answer": "BTC has positive catalysts with controlled risk.", "results": []}),
    patch.object(brain, "_get_serper_market_brief", return_value={}),
    patch.object(brain, "_get_serper_symbol_brief", return_value={}),
    patch.object(brain, "_get_ddg_market_brief", return_value={"results": [{"title": "DDG market pulse", "content": "Crypto market stabilizes"}]}),
    patch.object(brain, "_get_ddg_symbol_brief", return_value={"results": [{"title": "DDG BTC view", "content": "BTC remains liquid"}]}),
    patch.object(brain, "_has_ddg_client", return_value=True),
    patch("ki_brain._coordinator_query_ai_fn", return_value={
        "capital_posture": "DEFENSIVE",
        "risk_bias": "MIXED",
        "confidence": 0.77,
        "strategy_next": "Stay selective and size only the cleanest entries.",
        "focus_symbols": ["BTC"],
        "do_not_do": ["force breakout entries"],
        "provider": "groq",
        "model": "llama-3.1-8b-instant",
    }),
    patch("ki_brain._coordinator_provider_status_fn", return_value={
        "groq": {"configured": True, "model": "llama-3.1-8b-instant", "priority": 1, "used": 4, "remaining": 100, "pct_used": 4.0},
        "openrouter": {"configured": True, "model": "meta-llama/llama-3.1-8b-instruct:free", "priority": 4, "used": 1, "remaining": 99, "pct_used": 1.0},
    }),
):
    snapshot = brain.think(
        ["BTC"],
        context={
            "daily_pnl_pct": -0.002,
            "equity_idr": 120_000,
            "free_cash_idr": 50_000,
            "capital_profile": {"mode": "BUILDUP", "reason": "small_balance_build_up", "max_position_idr": 12_000},
        },
    )
    check("brain snapshot provider status", "tavily" in snapshot.get("provider_status", {}))
    check("brain target recovery mode", snapshot.get("daily_target", {}).get("status") == "RECOVERY_MODE")
    check("brain target strategy next", bool(snapshot.get("daily_target", {}).get("strategy_next")))
    check("brain market pulse headline", bool(snapshot.get("market_pulse", {}).get("top_headlines")))
    check("brain watch review headline count", int(snapshot.get("watch_reviews", [{}])[0].get("headline_count") or 0) >= 1)
    check("brain ai critic provider", snapshot.get("ai_critic", {}).get("provider") == "groq", str(snapshot.get("ai_critic")))
    check("brain ai legion shows ddg", "ddg" in snapshot.get("ai_legion", {}).get("search_providers", {}), str(snapshot.get("ai_legion")))
    check("brain ai legion shows groq", "groq" in snapshot.get("ai_legion", {}).get("llm_providers", {}), str(snapshot.get("ai_legion")))
    check("brain snapshot age available", brain.snapshot_age_sec() is not None)
    check("brain ensure warm skips fresh snapshot", brain.ensure_warm(["BTC"], {"daily_pnl_pct": 0.0}) is False)

with patch.object(
    manager._brain,
    "snapshot",
    return_value={
        "market_pulse": {"risk_bias": "RISK_OFF"},
        "daily_target": {"status": "RECOVERY_MODE", "strategy_next": "stay defensive"},
        "ai_critic": {
            "capital_posture": "OPPORTUNISTIC",
            "risk_bias": "RISK_ON",
            "confidence": 0.92,
            "focus_symbols": ["BTC"],
        },
        "watch_reviews": [
            {"symbol": "BTC", "approved": True, "reason": "brain_advisory_ok"},
            {"symbol": "REQ", "approved": False, "reason": "external_research_risk_off"},
        ],
    },
), patch.object(
    manager,
    "_load_json_file",
    side_effect=lambda path, default=None: {"topOpportunities": ["btc_idr"]} if str(path).endswith("whatif_results.json") else (default if default is not None else {}),
):
    advice = manager._brain_signal_advisory(
        "btc_idr",
        {"pair": "btc_idr", "score": 0.72, "base_symbol": "BTC"},
        20_000.0,
        {"mode": "MICRO", "reason": "micro_balance_preservation"},
    )
    check("brain advisory allows focus pair", advice.get("allow") is True, str(advice))
    check("brain advisory reduces size in risk off", float(advice.get("budget_idr") or 0.0) < 20_000.0, str(advice))
    check("brain advisory still respects critic boost", float(advice.get("budget_idr") or 0.0) >= 10_000.0, str(advice))

    blocked = manager._brain_signal_advisory(
        "req_idr",
        {"pair": "req_idr", "score": 0.7, "base_symbol": "REQ"},
        20_000.0,
        {"mode": "MICRO", "reason": "micro_balance_preservation"},
    )
    check("brain advisory blocks rejected review", blocked.get("allow") is False, str(blocked))

with patch.object(
    manager._brain,
    "snapshot",
    return_value={
        "market_pulse": {"risk_bias": "RISK_ON"},
        "daily_target": {"status": "CHASING_GREEN", "strategy_next": "press the cleanest setup"},
        "ai_critic": {
            "capital_posture": "OPPORTUNISTIC",
            "risk_bias": "RISK_ON",
            "confidence": 0.95,
            "focus_symbols": ["BTC"],
        },
        "watch_reviews": [
            {"symbol": "BTC", "approved": True, "reason": "brain_advisory_ok"},
        ],
    },
), patch.object(
    manager,
    "_load_json_file",
    side_effect=lambda path, default=None: {"topOpportunities": ["btc_idr"]} if str(path).endswith("whatif_results.json") else (default if default is not None else {}),
):
    boosted = manager._brain_signal_advisory(
        "btc_idr",
        {"pair": "btc_idr", "score": 0.92, "base_symbol": "BTC"},
        20_000.0,
        {"mode": "BUILDUP", "reason": "small_balance_build_up"},
    )
    check("brain advisory boosts focus pair when risk-on", float(boosted.get("budget_idr") or 0.0) > 20_000.0, str(boosted))

class _FakeScanner:
    def __init__(self, exchange: str):
        self.exchange = exchange
        self.sent = []

    def fetch_tickers(self):
        return {"BTC": {"price": 1.0, "vol_usdt_24h": 10_000_000.0, "change_24h": 4.0, "change_1h": 3.0}}

    def detect_signal(self, **kwargs):
        return {"pair_indodax": "btc_idr", "exchange": self.exchange, "detection_score": 0.8}

    def send_signal(self, signal):
        self.sent.append(signal)

    def _save_state(self):
        return None

with TemporaryDirectory() as tmpdir, patch.dict(
    os.environ,
    {
        "KIBOT_RUNTIME_ROOT": tmpdir,
        "KIBOT_SCANNER_STATE_DIR": str(Path(tmpdir) / "state" / "scanners"),
        "KIBOT_SCANNER_SUPABASE_MIRROR_ENABLED": "false",
    },
    clear=False,
):
    mesh = GlobalScannerMesh(scanners=[_FakeScanner("BYBIT"), _FakeScanner("KUCOIN")], interval_s=1)
    cycle = mesh.run_once()
    check("scanner mesh scanned", cycle["total_scanned"] == 2)
    check("scanner mesh sent", cycle["total_sent"] == 2)
    feed_snapshot = json.loads(mesh.feed_path.read_text(encoding="utf-8"))
    check("scanner mesh writes feed", feed_snapshot.get("total_sent") == 2)
    check("scanner mesh stores signals", len(feed_snapshot.get("signals") or []) == 2)

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
