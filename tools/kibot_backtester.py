#!/usr/bin/env python3
"""
KiBot Trinity — Trade Journal Backtester v1.0
================================================
Analyzes historical trade_log.jsonl to compute:
- Win rate, average PnL, profit factor
- Best/worst pairs, optimal holding times
- Time-of-day performance heatmap
- Drawdown series and recovery analysis
- Bucket A vs B performance comparison
- Recommendations for parameter tuning

Usage:
  python3 tools/kibot_backtester.py                     # last 30 days
  python3 tools/kibot_backtester.py --days 90           # last 90 days
  python3 tools/kibot_backtester.py --output report.json # save JSON report
"""

import argparse
import json
import math
import os
import sys
import statistics
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


ROOT_DIR = Path(__file__).resolve().parent.parent
STATE_ROOT = Path(os.getenv("KIBOT_MANAGER_STATE_DIR", str(ROOT_DIR / "state")))
TRADE_LOG = STATE_ROOT / "trade_log.jsonl"
WIB = timezone(timedelta(hours=7))


def _load_trades(path: Path, days: int = 30) -> List[Dict[str, Any]]:
    """Load closed trades from trade_log.jsonl within the given timeframe."""
    if not path.exists():
        print(f"[BACKTESTER] ❌ Trade log not found: {path}")
        return []
    
    cutoff = datetime.now(WIB) - timedelta(days=days)
    trades = []
    
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                trade = json.loads(line)
                if trade.get("status") != "CLOSED":
                    continue
                # Parse exit_at timestamp
                exit_at = trade.get("exit_at", "")
                if exit_at:
                    try:
                        dt = datetime.fromisoformat(exit_at)
                        if dt.tzinfo is None:
                            dt = dt.replace(tzinfo=WIB)
                        if dt < cutoff:
                            continue
                    except Exception:
                        pass
                trades.append(trade)
            except json.JSONDecodeError:
                continue
    
    return trades


def _safe_float(val: Any, default: float = 0.0) -> float:
    try:
        return float(val or default)
    except (TypeError, ValueError):
        return default


def compute_stats(trades: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Compute comprehensive trading statistics from a list of closed trades."""
    if not trades:
        return {"error": "No closed trades found in the given period."}
    
    # Basic metrics
    total = len(trades)
    wins = [t for t in trades if t.get("win")]
    losses = [t for t in trades if not t.get("win")]
    win_rate = len(wins) / total if total > 0 else 0
    
    pnl_list = [_safe_float(t.get("pnl_idr")) for t in trades]
    pnl_pct_list = [_safe_float(t.get("pnl_pct")) for t in trades]
    hold_list = [_safe_float(t.get("hold_minutes")) for t in trades]
    
    total_pnl = sum(pnl_list)
    avg_pnl = statistics.mean(pnl_list) if pnl_list else 0
    avg_pnl_pct = statistics.mean(pnl_pct_list) if pnl_pct_list else 0
    
    gross_profit = sum(p for p in pnl_list if p > 0)
    gross_loss = abs(sum(p for p in pnl_list if p < 0))
    profit_factor = gross_profit / gross_loss if gross_loss > 0 else float("inf")
    
    # Win/Loss breakdown
    avg_win = statistics.mean([_safe_float(t.get("pnl_idr")) for t in wins]) if wins else 0
    avg_loss = statistics.mean([_safe_float(t.get("pnl_idr")) for t in losses]) if losses else 0
    avg_win_pct = statistics.mean([_safe_float(t.get("pnl_pct")) for t in wins]) if wins else 0
    avg_loss_pct = statistics.mean([_safe_float(t.get("pnl_pct")) for t in losses]) if losses else 0
    
    # Risk/Reward ratio
    rr_ratio = abs(avg_win / avg_loss) if avg_loss != 0 else float("inf")
    
    # Expectancy: (win_rate * avg_win) - ((1-win_rate) * abs(avg_loss))
    expectancy = (win_rate * avg_win) - ((1 - win_rate) * abs(avg_loss))
    expectancy_pct = (win_rate * avg_win_pct) - ((1 - win_rate) * abs(avg_loss_pct))
    
    # Holding time analysis
    avg_hold = statistics.mean(hold_list) if hold_list else 0
    win_hold = statistics.mean([_safe_float(t.get("hold_minutes")) for t in wins]) if wins else 0
    loss_hold = statistics.mean([_safe_float(t.get("hold_minutes")) for t in losses]) if losses else 0
    
    # Drawdown series
    equity_curve = []
    running = 0
    peak = 0
    max_dd = 0
    max_dd_trades = 0
    dd_counter = 0
    for t in trades:
        running += _safe_float(t.get("pnl_idr"))
        equity_curve.append(running)
        peak = max(peak, running)
        dd = peak - running
        if dd > max_dd:
            max_dd = dd
        if _safe_float(t.get("pnl_idr")) < 0:
            dd_counter += 1
            max_dd_trades = max(max_dd_trades, dd_counter)
        else:
            dd_counter = 0
    
    # Per-pair breakdown
    pair_stats: Dict[str, Dict[str, Any]] = defaultdict(lambda: {"wins": 0, "losses": 0, "total_pnl": 0.0, "trades": 0})
    for t in trades:
        pair = t.get("pair_id", "unknown")
        pair_stats[pair]["trades"] += 1
        pair_stats[pair]["total_pnl"] += _safe_float(t.get("pnl_idr"))
        if t.get("win"):
            pair_stats[pair]["wins"] += 1
        else:
            pair_stats[pair]["losses"] += 1
    
    # Sort pairs by PnL
    best_pairs = sorted(pair_stats.items(), key=lambda x: x[1]["total_pnl"], reverse=True)[:10]
    worst_pairs = sorted(pair_stats.items(), key=lambda x: x[1]["total_pnl"])[:10]
    
    # Bucket breakdown
    bucket_a = [t for t in trades if t.get("bucket", "").upper() in ("A", "BUCKET_A", "LEAD_LAG")]
    bucket_b = [t for t in trades if t.get("bucket", "").upper() in ("B", "BUCKET_B", "INDODAX_ONLY")]
    
    def _bucket_summary(bucket_trades: List[Dict]) -> Dict:
        if not bucket_trades:
            return {"trades": 0, "win_rate": 0, "total_pnl": 0, "avg_pnl_pct": 0}
        b_wins = sum(1 for t in bucket_trades if t.get("win"))
        return {
            "trades": len(bucket_trades),
            "win_rate": round(b_wins / len(bucket_trades), 4),
            "total_pnl": round(sum(_safe_float(t.get("pnl_idr")) for t in bucket_trades), 2),
            "avg_pnl_pct": round(statistics.mean([_safe_float(t.get("pnl_pct")) for t in bucket_trades]) * 100, 4),
        }
    
    # Time-of-day analysis (WIB hours)
    hour_stats: Dict[int, Dict[str, Any]] = defaultdict(lambda: {"wins": 0, "losses": 0, "total_pnl": 0.0})
    for t in trades:
        entry_at = t.get("entry_at", "")
        if entry_at:
            try:
                dt = datetime.fromisoformat(entry_at)
                hour = dt.hour
                hour_stats[hour]["total_pnl"] += _safe_float(t.get("pnl_idr"))
                if t.get("win"):
                    hour_stats[hour]["wins"] += 1
                else:
                    hour_stats[hour]["losses"] += 1
            except Exception:
                pass
    
    # Best/worst hours
    sorted_hours = sorted(hour_stats.items(), key=lambda x: x[1]["total_pnl"], reverse=True)
    
    # Consecutive streak analysis
    max_win_streak = 0
    max_loss_streak = 0
    current_streak = 0
    streak_type = None
    for t in trades:
        is_win = bool(t.get("win"))
        if is_win:
            if streak_type == "win":
                current_streak += 1
            else:
                current_streak = 1
                streak_type = "win"
            max_win_streak = max(max_win_streak, current_streak)
        else:
            if streak_type == "loss":
                current_streak += 1
            else:
                current_streak = 1
                streak_type = "loss"
            max_loss_streak = max(max_loss_streak, current_streak)
    
    # Exit reason breakdown
    exit_reasons: Dict[str, int] = defaultdict(int)
    for t in trades:
        reason = str(t.get("exit_reason", "UNKNOWN")).upper()
        exit_reasons[reason] += 1
    
    # Generate recommendations
    recommendations = _generate_recommendations(
        win_rate, avg_hold, win_hold, loss_hold, profit_factor,
        rr_ratio, expectancy_pct, best_pairs, worst_pairs, sorted_hours
    )
    
    return {
        "period": {
            "total_trades": total,
            "wins": len(wins),
            "losses": len(losses),
        },
        "performance": {
            "win_rate": round(win_rate, 4),
            "profit_factor": round(profit_factor, 4) if profit_factor != float("inf") else "∞",
            "total_pnl_idr": round(total_pnl, 2),
            "avg_pnl_idr": round(avg_pnl, 2),
            "avg_pnl_pct": round(avg_pnl_pct * 100, 4),
            "expectancy_idr": round(expectancy, 2),
            "expectancy_pct": round(expectancy_pct * 100, 4),
            "risk_reward_ratio": round(rr_ratio, 4) if rr_ratio != float("inf") else "∞",
        },
        "win_loss_breakdown": {
            "avg_win_idr": round(avg_win, 2),
            "avg_loss_idr": round(avg_loss, 2),
            "avg_win_pct": round(avg_win_pct * 100, 4),
            "avg_loss_pct": round(avg_loss_pct * 100, 4),
            "gross_profit_idr": round(gross_profit, 2),
            "gross_loss_idr": round(gross_loss, 2),
        },
        "holding_time": {
            "avg_minutes": round(avg_hold, 1),
            "avg_win_minutes": round(win_hold, 1),
            "avg_loss_minutes": round(loss_hold, 1),
        },
        "risk": {
            "max_drawdown_idr": round(max_dd, 2),
            "max_consecutive_losses": max_dd_trades,
            "max_consecutive_wins": max_win_streak,
        },
        "bucket_comparison": {
            "bucket_a_lead_lag": _bucket_summary(bucket_a),
            "bucket_b_indodax_only": _bucket_summary(bucket_b),
        },
        "top_pairs": {
            "best": [{"pair": p, "pnl_idr": round(s["total_pnl"], 2), "trades": s["trades"], "win_rate": round(s["wins"]/max(s["trades"],1), 2)} for p, s in best_pairs[:5]],
            "worst": [{"pair": p, "pnl_idr": round(s["total_pnl"], 2), "trades": s["trades"], "win_rate": round(s["wins"]/max(s["trades"],1), 2)} for p, s in worst_pairs[:5]],
        },
        "time_of_day_wib": {
            "best_hours": [{"hour": h, "pnl_idr": round(s["total_pnl"], 2), "trades": s["wins"]+s["losses"]} for h, s in sorted_hours[:3]],
            "worst_hours": [{"hour": h, "pnl_idr": round(s["total_pnl"], 2), "trades": s["wins"]+s["losses"]} for h, s in sorted_hours[-3:]],
        },
        "exit_reasons": dict(exit_reasons),
        "recommendations": recommendations,
    }


def _generate_recommendations(
    win_rate: float, avg_hold: float, win_hold: float, loss_hold: float,
    profit_factor: float, rr_ratio: float, expectancy_pct: float,
    best_pairs: list, worst_pairs: list, sorted_hours: list
) -> List[str]:
    """Generate actionable recommendations based on statistics."""
    recs = []
    
    if win_rate < 0.45:
        recs.append("⚠️ Win rate below 45% — consider tightening conviction_min threshold (currently 0.85)")
    elif win_rate > 0.65:
        recs.append("✅ Strong win rate — system is well-calibrated for entry quality")
    
    if isinstance(profit_factor, (int, float)) and profit_factor < 1.0:
        recs.append("🔴 Profit factor < 1.0 — system is NET LOSING. Review exit strategy urgently")
    elif isinstance(profit_factor, (int, float)) and profit_factor > 2.0:
        recs.append("✅ Profit factor > 2.0 — excellent risk management")
    
    if loss_hold > 0 and win_hold > 0 and loss_hold > win_hold * 1.5:
        recs.append(f"⚠️ Losing trades held {loss_hold:.0f}m avg vs winners {win_hold:.0f}m — cut losers faster")
    
    if isinstance(rr_ratio, (int, float)) and rr_ratio < 1.0:
        recs.append("⚠️ Risk/Reward ratio < 1.0 — winners are smaller than losers. Widen take-profit target")
    
    if expectancy_pct < 0:
        recs.append(f"🔴 Negative expectancy ({expectancy_pct:.3f}%) — every trade has negative expected value")
    elif expectancy_pct > 0.3:
        recs.append(f"✅ Positive expectancy ({expectancy_pct:.3f}%) — mathematical edge confirmed")
    
    # Worst pair recommendations
    for pair, stats in worst_pairs[:3]:
        if stats["total_pnl"] < -10000 and stats["trades"] >= 3:
            wr = stats["wins"] / max(stats["trades"], 1)
            recs.append(f"📉 Consider blacklisting {pair}: {stats['trades']} trades, WR={wr:.0%}, PnL=Rp{stats['total_pnl']:,.0f}")
    
    if not recs:
        recs.append("✅ System performing within normal parameters")
    
    return recs


def print_report(stats: Dict[str, Any]) -> None:
    """Pretty-print the backtesting report."""
    if "error" in stats:
        print(f"\n❌ {stats['error']}")
        return
    
    perf = stats["performance"]
    period = stats["period"]
    wl = stats["win_loss_breakdown"]
    hold = stats["holding_time"]
    risk = stats["risk"]
    bucket = stats["bucket_comparison"]
    
    print("\n" + "=" * 60)
    print("  🔬 KIBOT TRINITY — TRADE JOURNAL BACKTEST REPORT")
    print("=" * 60)
    
    print(f"\n📊 OVERVIEW")
    print(f"   Total Trades:    {period['total_trades']}")
    print(f"   Wins / Losses:   {period['wins']} / {period['losses']}")
    print(f"   Win Rate:        {perf['win_rate']:.1%}")
    print(f"   Profit Factor:   {perf['profit_factor']}")
    
    print(f"\n💰 P&L SUMMARY")
    print(f"   Total PnL:       Rp{perf['total_pnl_idr']:>+15,.2f}")
    print(f"   Avg PnL/Trade:   Rp{perf['avg_pnl_idr']:>+15,.2f}")
    print(f"   Avg PnL %:       {perf['avg_pnl_pct']:>+.4f}%")
    print(f"   Expectancy:      Rp{perf['expectancy_idr']:>+15,.2f} ({perf['expectancy_pct']:+.4f}%)")
    print(f"   Risk/Reward:     {perf['risk_reward_ratio']}")
    
    print(f"\n📈 WIN/LOSS BREAKDOWN")
    print(f"   Avg Win:         Rp{wl['avg_win_idr']:>+12,.2f} ({wl['avg_win_pct']:+.4f}%)")
    print(f"   Avg Loss:        Rp{wl['avg_loss_idr']:>+12,.2f} ({wl['avg_loss_pct']:+.4f}%)")
    print(f"   Gross Profit:    Rp{wl['gross_profit_idr']:>12,.2f}")
    print(f"   Gross Loss:      Rp{wl['gross_loss_idr']:>12,.2f}")
    
    print(f"\n⏱️  HOLDING TIME")
    print(f"   Average:         {hold['avg_minutes']:.0f} minutes")
    print(f"   Winners:         {hold['avg_win_minutes']:.0f} minutes")
    print(f"   Losers:          {hold['avg_loss_minutes']:.0f} minutes")
    
    print(f"\n📉 RISK METRICS")
    print(f"   Max Drawdown:    Rp{risk['max_drawdown_idr']:>12,.2f}")
    print(f"   Max Loss Streak: {risk['max_consecutive_losses']}")
    print(f"   Max Win Streak:  {risk['max_consecutive_wins']}")
    
    # Bucket comparison
    ba = bucket.get("bucket_a_lead_lag", {})
    bb = bucket.get("bucket_b_indodax_only", {})
    if ba.get("trades") or bb.get("trades"):
        print(f"\n🪣 BUCKET COMPARISON")
        print(f"   {'':15s} {'Bucket A':>12s} {'Bucket B':>12s}")
        print(f"   {'Trades':15s} {ba.get('trades', 0):>12d} {bb.get('trades', 0):>12d}")
        print(f"   {'Win Rate':15s} {ba.get('win_rate', 0):>11.1%} {bb.get('win_rate', 0):>11.1%}")
        print(f"   {'Total PnL':15s} Rp{ba.get('total_pnl', 0):>+9,.0f} Rp{bb.get('total_pnl', 0):>+9,.0f}")
    
    # Top pairs
    top = stats.get("top_pairs", {})
    if top.get("best"):
        print(f"\n🏆 TOP PERFORMERS")
        for p in top["best"][:5]:
            print(f"   {p['pair']:20s} Rp{p['pnl_idr']:>+10,.0f}  ({p['trades']} trades, WR={p['win_rate']:.0%})")
    
    if top.get("worst"):
        print(f"\n⚠️  WORST PERFORMERS")
        for p in top["worst"][:5]:
            print(f"   {p['pair']:20s} Rp{p['pnl_idr']:>+10,.0f}  ({p['trades']} trades, WR={p['win_rate']:.0%})")
    
    # Time of day
    tod = stats.get("time_of_day_wib", {})
    if tod.get("best_hours"):
        print(f"\n🕐 BEST TRADING HOURS (WIB)")
        for h in tod["best_hours"]:
            print(f"   {h['hour']:02d}:00  Rp{h['pnl_idr']:>+10,.0f}  ({h['trades']} trades)")
    
    # Recommendations
    recs = stats.get("recommendations", [])
    if recs:
        print(f"\n💡 RECOMMENDATIONS")
        for r in recs:
            print(f"   {r}")
    
    print("\n" + "=" * 60)


def main():
    parser = argparse.ArgumentParser(description="KiBot Trade Journal Backtester")
    parser.add_argument("--days", type=int, default=30, help="Number of days to analyze (default: 30)")
    parser.add_argument("--log", type=str, default=str(TRADE_LOG), help="Path to trade_log.jsonl")
    parser.add_argument("--output", type=str, default="", help="Save JSON report to file")
    parser.add_argument("--json", action="store_true", help="Output as JSON instead of pretty-print")
    args = parser.parse_args()
    
    log_path = Path(args.log)
    print(f"[BACKTESTER] Loading trades from {log_path} (last {args.days} days)...")
    trades = _load_trades(log_path, args.days)
    print(f"[BACKTESTER] Found {len(trades)} closed trades")
    
    stats = compute_stats(trades)
    
    if args.json:
        print(json.dumps(stats, indent=2, ensure_ascii=False, default=str))
    else:
        print_report(stats)
    
    if args.output:
        output_path = Path(args.output)
        output_path.write_text(json.dumps(stats, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
        print(f"\n📁 Report saved to {output_path}")


if __name__ == "__main__":
    main()
