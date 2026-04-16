#!/usr/bin/env python3
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import sys
from pathlib import Path

import psycopg


ROOT_DIR = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT_DIR / ".env"
MANIFEST_PATH = ROOT_DIR / ".dist" / "android" / "stable" / "latest.json"


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def normalize_json(value):
    if value is None:
        return {}
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return {}
    return {}


def main() -> int:
    parser = argparse.ArgumentParser(description="Check learning-based bot update recommendations.")
    parser.add_argument("--json", action="store_true", dest="as_json", help="Output JSON")
    parser.add_argument("--bot-id", default=None, help="Override bot id (default from .env or main)")
    args = parser.parse_args()

    env = load_env(ENV_PATH)
    bot_id = args.bot_id or env.get("KICRYP_BOT_ID", "main")
    db_url = env.get("SUPABASE_POOLER_URL") or env.get("SUPABASE_DB_URL")
    if not db_url:
        print("SUPABASE_POOLER_URL / SUPABASE_DB_URL belum ada di .env", file=sys.stderr)
        return 1

    result: dict[str, object] = {
        "bot_id": bot_id,
        "status": "ok",
        "recommendation_count": 0,
        "recommendations": [],
        "latest_weekly_review": None,
        "bot_state": None,
        "ui_sync_gaps": [],
        "suggestions": [],
        "recent_triggers": [],
        "release_manifest": None,
    }

    if MANIFEST_PATH.exists():
        result["release_manifest"] = json.loads(MANIFEST_PATH.read_text())

    with psycopg.connect(db_url, sslmode="require") as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                select
                    version_tag,
                    created_at,
                    change_summary,
                    parameters,
                    created_by_device_id
                from public.parameter_versions
                where bot_id = %s
                  and scope = 'update_recommendation'
                order by created_at desc
                limit 10
                """,
                (bot_id,),
            )
            recommendations = []
            for version_tag, created_at, change_summary, parameters, created_by_device_id in cur.fetchall():
                summary = normalize_json(change_summary)
                evidence = normalize_json(parameters)
                recommendations.append(
                    {
                        "version_tag": version_tag,
                        "created_at": created_at.isoformat() if created_at else None,
                        "title": summary.get("title", "Update recommendation"),
                        "summary": summary.get("summary", ""),
                        "reason_code": summary.get("reason_code", ""),
                        "severity": summary.get("severity", "MEDIUM"),
                        "confidence_score": summary.get("confidence_score", 0),
                        "source": summary.get("source", "control_plane"),
                        "recommended_actions": summary.get("recommended_actions", []),
                        "evidence": evidence,
                        "created_by_device_id": created_by_device_id,
                    }
                )
            result["recommendations"] = recommendations
            result["recommendation_count"] = len(recommendations)

            cur.execute(
                """
                select
                    desired_state,
                    effective_state,
                    active_device_id,
                    current_pair,
                    sync_health,
                    operating_mode,
                    edge_confidence,
                    market_regime,
                    last_heartbeat_at
                from public.bot_state
                where bot_id = %s
                limit 1
                """,
                (bot_id,),
            )
            bot_state_row = cur.fetchone()
            if bot_state_row:
                (
                    desired_state,
                    effective_state,
                    active_device_id,
                    current_pair,
                    sync_health,
                    operating_mode,
                    edge_confidence,
                    market_regime,
                    last_heartbeat_at,
                ) = bot_state_row
                result["bot_state"] = {
                    "desired_state": desired_state,
                    "effective_state": effective_state,
                    "active_device_id": active_device_id,
                    "current_pair": current_pair,
                    "sync_health": sync_health,
                    "operating_mode": operating_mode,
                    "edge_confidence": edge_confidence,
                    "market_regime": market_regime,
                    "last_heartbeat_at": last_heartbeat_at.isoformat() if last_heartbeat_at else None,
                }

            cur.execute(
                """
                select
                    period_start,
                    period_end,
                    false_entry_rate,
                    productive_utilization_pct,
                    missed_opportunity_rate,
                    tactical_expectancy,
                    swing_expectancy,
                    adaptation_plan,
                    notes
                from public.weekly_learning_reviews
                where bot_id = %s
                order by period_end desc
                limit 1
                """,
                (bot_id,),
            )
            row = cur.fetchone()
            if row:
                (
                    period_start,
                    period_end,
                    false_entry_rate,
                    productive_utilization_pct,
                    missed_opportunity_rate,
                    tactical_expectancy,
                    swing_expectancy,
                    adaptation_plan,
                    notes,
                ) = row
                result["latest_weekly_review"] = {
                    "period_start": period_start.isoformat(),
                    "period_end": period_end.isoformat(),
                    "false_entry_rate": float(false_entry_rate),
                    "productive_utilization_pct": float(productive_utilization_pct),
                    "missed_opportunity_rate": float(missed_opportunity_rate),
                    "tactical_expectancy": float(tactical_expectancy),
                    "swing_expectancy": float(swing_expectancy),
                    "adaptation_plan": normalize_json(adaptation_plan),
                    "notes": normalize_json(notes) if not isinstance(notes, list) else notes,
                }

            cur.execute(
                """
                select created_at, category, message
                from public.logs
                where bot_id = %s
                  and category in ('UPDATE_HINT', 'LEARNING_HINT')
                order by created_at desc
                limit 8
                """,
                (bot_id,),
            )
            result["recent_triggers"] = [
                {
                    "created_at": created_at.isoformat(),
                    "category": category,
                    "message": message,
                }
                for created_at, category, message in cur.fetchall()
            ]

    bot_state = result.get("bot_state") or {}
    latest_review = result.get("latest_weekly_review") or {}
    ui_sync_gaps: list[str] = []
    suggestions: list[str] = []

    if bot_state:
        heartbeat_at = bot_state.get("last_heartbeat_at")
        if heartbeat_at:
            heartbeat_age = (
                datetime.now(timezone.utc) - datetime.fromisoformat(heartbeat_at.replace("Z", "+00:00"))
            ).total_seconds()
            if heartbeat_age > 60:
                ui_sync_gaps.append("heartbeat_stale")
                suggestions.append("Heartbeat engine sudah lebih dari 60 detik, jadi sync/engine health perlu dicek dulu.")
        if bot_state.get("sync_health") != "HEALTHY":
            ui_sync_gaps.append("sync_not_healthy")
            suggestions.append("Sync health belum HEALTHY, jadi patch sync dan performa harus diprioritaskan.")
        if not bot_state.get("active_device_id"):
            ui_sync_gaps.append("no_active_engine")
            suggestions.append("Belum ada active engine yang jelas, jadi lease/control-plane perlu dipastikan stabil.")

    if not latest_review:
        ui_sync_gaps.append("weekly_review_missing")
        suggestions.append("Review mingguan belum terbentuk, jadi learning otomatis dari data live perlu terus dijaga aktif.")
    else:
        if latest_review.get("false_entry_rate", 0) >= 0.30:
            suggestions.append("False entry mingguan masih tinggi, threshold entry dan filter spread/slippage layak diperketat.")
        if latest_review.get("productive_utilization_pct", 1) <= 0.35:
            suggestions.append("Produktivitas modal masih rendah, deployment logic bisa ditinjau agar modal sehat tidak terlalu idle.")

    if result["recommendation_count"] == 0 and result["recent_triggers"]:
        suggestions.append("Sudah ada trigger belajar, tapi belum cukup evidence untuk update recommendation formal.")

    result["ui_sync_gaps"] = ui_sync_gaps
    result["suggestions"] = list(dict.fromkeys(suggestions))

    if args.as_json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0

    release_manifest = result.get("release_manifest") or {}
    recommendations = result["recommendations"]
    print("KiCryp update check")
    print(f"- Bot: {bot_id}")
    if release_manifest:
        print(
            "- Release lokal: "
            f"{release_manifest.get('versionName', '?')} "
            f"(code {release_manifest.get('versionCode', '?')})"
        )
    print(f"- Rekomendasi update pending: {result['recommendation_count']}")

    if bot_state:
        print(
            "- Runtime: "
            f"{bot_state.get('effective_state', '?')} • "
            f"{bot_state.get('sync_health', '?')} • "
            f"{bot_state.get('operating_mode', '?')} • "
            f"{bot_state.get('current_pair', '-') or '-'}"
        )

    if latest_review:
        print(
            "- Review mingguan: "
            f"false entry {latest_review['false_entry_rate']:.2f}, "
            f"productive util {latest_review['productive_utilization_pct']:.2f}, "
            f"missed opp {latest_review['missed_opportunity_rate']:.2f}"
        )

    if recommendations:
        print("\nTop recommendations:")
        for item in recommendations[:3]:
            print(
                f"- [{item['severity']}] {item['title']} "
                f"({item['reason_code']})"
            )
            print(f"  {item['summary']}")
            for action in item.get("recommended_actions", [])[:2]:
                print(f"  aksi: {action}")
    else:
        print("\nBelum ada rekomendasi update yang pending.")

    if result["ui_sync_gaps"]:
        print("\nGap terdeteksi:")
        for item in result["ui_sync_gaps"][:4]:
            print(f"- {item}")

    if result["suggestions"]:
        print("\nSaran otomatis:")
        for item in result["suggestions"][:4]:
            print(f"- {item}")

    triggers = result["recent_triggers"]
    if triggers:
        print("\nTrigger terbaru:")
        for item in triggers[:3]:
            print(f"- {item['created_at']} {item['category']}: {item['message']}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
