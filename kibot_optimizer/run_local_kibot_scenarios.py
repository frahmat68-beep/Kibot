#!/usr/bin/env python3
"""
Local scenario runner for KiCryp manager (brain-only).
This does NOT execute real trades. It validates KiCryp decision logic quality.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List


ROOT = Path(__file__).resolve().parent
SCENARIO_FILE = ROOT / "scenarios.json"


@dataclass
class Decision:
    mode: str
    commands: List[str]
    manager_report: str


def load_payload() -> Dict:
    return json.loads(SCENARIO_FILE.read_text(encoding="utf-8"))


def evaluate_scenario(item: Dict, target_hourly: float) -> Decision:
    kidax = item["kidax"]
    kinance = item["kinance"]
    hours_elapsed = float(item["hours_elapsed"])
    pnl_today_pct = float(item["pnl_today_pct"])
    drawdown_pct = float(item["drawdown_pct"])

    pace = pnl_today_pct / max(hours_elapsed, 1.0)
    missing_target = pace < target_hourly
    kidax_online = bool(kidax["online"])
    kinance_online = bool(kinance["online"])

    commands: List[str] = []
    mode = "NORMAL"

    if drawdown_pct <= -1.9:
        mode = "CAPITAL_DEFENSE"
        commands += [
            "GLOBAL_SIZE_DOWN",
            "BLOCK_LOW_QUALITY_SETUPS",
            "FORCE_HIGH_CONF_ONLY",
        ]
    elif not kinance_online or not kidax_online:
        mode = "DEGRADED_SINGLE_ENGINE"
        commands += [
            "KINANCE_RECOVERY_ALERT" if not kinance_online else "KIDAX_RECOVERY_ALERT",
            "KIDAX_DEFENSIVE_AGGRESSION" if kidax_online else "KINANCE_DEFENSIVE_AGGRESSION",
            "FALLBACK_SIGNAL_MODE",
        ]
    elif missing_target and kidax["stagnant_minutes"] >= 20 and kinance["stagnant_minutes"] >= 20:
        mode = "EMERGENCY_PURSUIT"
        commands += [
            "KIDAX_ROTATE_STAGNANT",
            "KINANCE_EXPAND_SCAN",
            "BOTH_TIGHTEN_LOOP_1H",
        ]
    elif kinance["hourly_pnl_pct"] > 1.2 and kidax["hourly_pnl_pct"] < 0.5:
        mode = "SPLIT_BRAIN_TUNING"
        commands += [
            "KIDAX_FORCE_SLIPPAGE_GUARD_RECALIBRATE",
            "KIDAX_ROTATE_STAGNANT",
            "KINANCE_LEAD_SIGNAL_PRIORITY",
        ]
    elif kidax["hourly_pnl_pct"] > target_hourly and kinance["hourly_pnl_pct"] > target_hourly:
        mode = "CONTROLLED_OVERDRIVE"
        commands += [
            "SCALE_WINNERS",
            "LOCK_PROFIT_WINDOWS",
            "KEEP_SCANNING_ALL_MARKET",
        ]
    else:
        mode = "DISCIPLINED_CHASE"
        commands += [
            "BOTH_KEEP_AGGRESSIVE_SCAN",
            "RISK_FILTER_ON",
            "MICRO_REBALANCE_1H",
        ]

    report = (
        f"KiCryp Manager [{mode}] | pace={pace:.2f}%/jam vs target={target_hourly:.2f}%/jam | "
        f"KiDax(lat={kidax['latency_ms']}ms,conv={kidax['conversion_quality']}) | "
        f"Kinance(lat={kinance['latency_ms']}ms,conv={kinance['conversion_quality']}). "
        f"Instruksi: {', '.join(commands)}."
    )

    return Decision(mode=mode, commands=commands, manager_report=report)


def assert_expected(item: Dict, decision: Decision) -> None:
    expected = item["expected"]
    if decision.mode != expected["mode"]:
        raise AssertionError(
            f"{item['id']}: expected mode={expected['mode']} got mode={decision.mode}"
        )
    missing = [cmd for cmd in expected["must_include_commands"] if cmd not in decision.commands]
    if missing:
        raise AssertionError(
            f"{item['id']}: missing commands {missing}; got {decision.commands}"
        )


def main() -> None:
    payload = load_payload()
    target_hourly = float(payload["target_hourly_pct"])
    scenarios = payload["scenarios"]

    print("== KiCryp Local Manager Scenario Suite ==")
    print(f"Target hourly: {target_hourly:.2f}%")
    print(f"Scenario count: {len(scenarios)}")
    print("")

    passed = 0
    for item in scenarios:
        decision = evaluate_scenario(item, target_hourly)
        assert_expected(item, decision)
        passed += 1
        print(f"[PASS] {item['id']} :: {item['title']}")
        print(f"  mode: {decision.mode}")
        print(f"  commands: {', '.join(decision.commands)}")
        print(f"  report: {decision.manager_report}")
        print("")

    print(f"All scenarios passed: {passed}/{len(scenarios)}")


if __name__ == "__main__":
    main()
