#!/usr/bin/env python3
"""
AI auditor for rolling 30-minute trading performance.

Usage:
  python3 scripts/audit_trading_30m_ai.py --input /path/to/last_30m.json
  python3 scripts/audit_trading_30m_ai.py --input /path/to/last_30m.json --provider gemini
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import textwrap
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Dict, List, Tuple, Optional, Union, Any


SYSTEM_ROLE = (
    "You are a Ruthless Algorithmic Trading Auditor focusing on High Capital Velocity. "
    "Your job is to critically analyze the rolling 30-minute trading performance data of a "
    "Kotlin-based crypto bot. You must aggressively hunt for capital inefficiencies, slow "
    "rotations, and missed momentum. Produce a highly technical, developer-facing bug report "
    "and optimization guide. Your output will be executed by an AI Coding Assistant "
    "(Codex/Copilot) to continuously rewrite and tighten the bot's logic."
)

AUDIT_INSTRUCTIONS = textwrap.dedent(
    """
    Instructions for Output:
    Analyze the provided JSON data of the last 30 minutes of trades. Format your response in strict Markdown.
    You MUST include exactly these sections:

    ## Symptom
    ## Root Cause Hypothesis
    ## Actionable Refactor Directives

    Constraints:
    - Be technical and developer-facing.
    - Be ruthless and specific.
    - Focus on capital velocity and missed opportunity cost.
    - Do not write Kotlin code; give architecture-level directives only.
    - In directives, use imperative language and include thresholds/timers where relevant.
    - If data quality is weak or incomplete, explicitly call that out in Symptom.
    """
).strip()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Audit 30-minute trading JSON using LLM providers.")
    parser.add_argument("--input", required=True, help="Path to rolling 30-minute JSON data.")
    parser.add_argument(
        "--provider",
        choices=["auto", "gemini", "openrouter", "groq", "cohere", "blackbox"],
        default="auto",
        help="Provider selection. default=auto (fallback chain).",
    )
    parser.add_argument(
        "--model",
        default="",
        help="Optional model override for selected provider.",
    )
    parser.add_argument(
        "--all-providers",
        action="store_true",
        help="Run every provider and write outputs (does not stop at first success).",
    )
    parser.add_argument(
        "--output-dir",
        default="",
        help="Directory to write markdown outputs and summary JSON.",
    )
    parser.add_argument(
        "--providers",
        default="",
        help="Optional comma-separated provider list override.",
    )
    parser.add_argument(
        "--provider-state-file",
        default="",
        help="JSON file storing provider cooldown state between runs.",
    )
    parser.add_argument(
        "--cooldown-hours",
        type=float,
        default=6.0,
        help="Default cooldown hours after quota or transient provider failures.",
    )
    return parser.parse_args()


def load_env_files() -> Dict[str, str]:
    result: Dict[str, str] = {}
    candidates = [Path(".env"), Path(".env.server"), Path("apps/mac-engine/.env")]
    for env_file in candidates:
        if not env_file.exists():
            continue
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            if key and value and key not in result:
                result[key] = value
    result.update({k: v for k, v in os.environ.items() if v})
    return result


def env_first(env: Dict[str, str], *keys: str) -> str:
    for key in keys:
        value = env.get(key, "").strip()
        if value:
            return value
    return ""


def http_json(
    url: str,
    payload: Dict,
    headers: Dict[str, str],
    timeout: int = 40,
) -> Dict:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url=url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    for key, value in headers.items():
        req.add_header(key, value)
    with urllib.request.urlopen(req, timeout=timeout) as response:  # nosec B310
        body = response.read().decode("utf-8", errors="replace")
        return json.loads(body)


def build_user_prompt(trade_json: str) -> str:
    return (
        f"{AUDIT_INSTRUCTIONS}\n\n"
        "Below is the rolling 30-minute trading performance JSON to audit:\n"
        "```json\n"
        f"{trade_json}\n"
        "```"
    )


def call_gemini(trade_json: str, env: Dict[str, str], model_override: str = "") -> str:
    api_key = env_first(env, "GEMINI_SUPPORT_API_KEY", "GEMINI_API_KEY")
    if not api_key:
        raise RuntimeError("Gemini API key missing.")
    model = model_override or env.get("GEMINI_SUPPORT_MODEL", "gemini-2.0-flash-lite")
    payload = {
        "contents": [
            {
                "parts": [
                    {"text": f"System Role:\n{SYSTEM_ROLE}\n\n{build_user_prompt(trade_json)}"},
                ],
            },
        ],
        "generationConfig": {
            "temperature": 0.15,
            "maxOutputTokens": 1800,
            "responseMimeType": "text/plain",
        },
    }
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    result = http_json(url=url, payload=payload, headers={})
    return (
        result.get("candidates", [{}])[0]
        .get("content", {})
        .get("parts", [{}])[0]
        .get("text", "")
        .strip()
    )


def call_openrouter(trade_json: str, env: Dict[str, str], model_override: str = "") -> str:
    api_key = env_first(env, "OPENROUTER_API_KEY", "OPENROUTER_KEY")
    if not api_key:
        raise RuntimeError("OpenRouter API key missing.")
    model = model_override or env.get("OPENROUTER_MODEL", "meta-llama/llama-3.1-8b-instruct")
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_ROLE},
            {"role": "user", "content": build_user_prompt(trade_json)},
        ],
        "temperature": 0.1,
    }
    result = http_json(
        url="https://openrouter.ai/api/v1/chat/completions",
        payload=payload,
        headers={"Authorization": f"Bearer {api_key}"},
    )
    return result.get("choices", [{}])[0].get("message", {}).get("content", "").strip()


def call_groq(trade_json: str, env: Dict[str, str], model_override: str = "") -> str:
    api_key = env_first(env, "GROQ_API_KEY", "GROQ_KEY")
    if not api_key:
        raise RuntimeError("Groq API key missing.")
    model = model_override or env.get("GROQ_MODEL", "llama-3.1-8b-instant")
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_ROLE},
            {"role": "user", "content": build_user_prompt(trade_json)},
        ],
        "temperature": 0.1,
    }
    result = http_json(
        url="https://api.groq.com/openai/v1/chat/completions",
        payload=payload,
        headers={"Authorization": f"Bearer {api_key}"},
    )
    return result.get("choices", [{}])[0].get("message", {}).get("content", "").strip()


def call_cohere(trade_json: str, env: Dict[str, str], model_override: str = "") -> str:
    api_key = env_first(env, "COHERE_API_KEY", "COHERE_KEY")
    if not api_key:
        raise RuntimeError("Cohere API key missing.")
    preferred_model = model_override or env.get("COHERE_MODEL", "command-r")
    model_candidates = [preferred_model, "command-r7b-12-2024", "command-r-plus-08-2024"]
    last_error: Optional[Exception] = None

    for model in list(dict.fromkeys(model_candidates)):
        try:
            payload = {
                "model": model,
                "messages": [
                    {"role": "system", "content": SYSTEM_ROLE},
                    {"role": "user", "content": build_user_prompt(trade_json)},
                ],
                "temperature": 0.1,
            }
            result = http_json(
                url="https://api.cohere.com/v2/chat",
                payload=payload,
                headers={"Authorization": f"Bearer {api_key}"},
            )
            return result.get("message", {}).get("content", [{}])[0].get("text", "").strip()
        except urllib.error.HTTPError as exc:
            details = exc.read().decode("utf-8", errors="replace")
            # Retry with fallback models if selected model is retired/invalid.
            if exc.code in (400, 404) and "model" in details.lower():
                last_error = RuntimeError(f"Model {model} unavailable: {details[:280]}")
                continue
            raise

    raise RuntimeError(str(last_error or RuntimeError("No working Cohere model found.")))


def call_blackbox(trade_json: str, env: Dict[str, str], model_override: str = "") -> str:
    api_key = env_first(env, "BLACKBOX_API_KEY", "BLACKBOX_KEY")
    if not api_key:
        raise RuntimeError("Blackbox API key missing.")
    model = model_override or env.get("BLACKBOX_MODEL", "blackboxai/openai/gpt-4o-mini")
    url = env.get("BLACKBOX_API_URL", "https://api.blackbox.ai/v1/chat/completions")
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_ROLE},
            {"role": "user", "content": build_user_prompt(trade_json)},
        ],
        "temperature": 0.1,
    }
    result = http_json(
        url=url,
        payload=payload,
        headers={"Authorization": f"Bearer {api_key}"},
    )
    return result.get("choices", [{}])[0].get("message", {}).get("content", "").strip()


def provider_chain(selected: str) -> List[str]:
    if selected != "auto":
        return [selected]
    return ["blackbox", "openrouter", "cohere", "gemini", "groq"]


def parse_provider_override(raw: str) -> List[str]:
    if not raw.strip():
        return []
    allowed = {"gemini", "openrouter", "groq", "cohere", "blackbox"}
    parsed = []
    for provider in [item.strip().lower() for item in raw.split(",")]:
        if provider and provider in allowed and provider not in parsed:
            parsed.append(provider)
    return parsed


def provider_has_credentials(provider: str, env: Dict[str, str]) -> bool:
    if provider == "gemini":
        return bool(env_first(env, "GEMINI_SUPPORT_API_KEY", "GEMINI_API_KEY"))
    if provider == "openrouter":
        return bool(env_first(env, "OPENROUTER_API_KEY", "OPENROUTER_KEY"))
    if provider == "groq":
        return bool(env_first(env, "GROQ_API_KEY", "GROQ_KEY"))
    if provider == "cohere":
        return bool(env_first(env, "COHERE_API_KEY", "COHERE_KEY"))
    if provider == "blackbox":
        return bool(env_first(env, "BLACKBOX_API_KEY", "BLACKBOX_KEY"))
    return False


def load_provider_state(path: Path) -> Dict[str, Dict]:
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return {}
    return data if isinstance(data, dict) else {}


def save_provider_state(path: Path, state: Dict[str, Dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def classify_provider_failure(message: str, default_cooldown_hours: float) -> Tuple[float, str]:
    lowered = message.lower()
    if "api key missing" in lowered or "unauthorized" in lowered or "invalid api key" in lowered:
        return 12.0, "missing_or_invalid_credentials"
    if "http 429" in lowered or "quota" in lowered or "resource_exhausted" in lowered:
        return max(default_cooldown_hours, 6.0), "rate_limited"
    if "http 403" in lowered or "error code: 1010" in lowered or "forbidden" in lowered:
        return 24.0, "provider_forbidden"
    if "timed out" in lowered or "temporary" in lowered or "connection reset" in lowered:
        return min(default_cooldown_hours, 2.0), "transient_network"
    return default_cooldown_hours, "generic_failure"


def provider_is_available(
    provider: str,
    provider_state: Dict[str, Dict],
    now_ts: float,
) -> Tuple[bool, str]:
    state = provider_state.get(provider, {})
    cooldown_until = float(state.get("cooldown_until_epoch", 0.0) or 0.0)
    if cooldown_until > now_ts:
        return False, state.get("reason", "cooldown_active")
    return True, ""


def try_provider(provider: str, trade_json: str, env: Dict[str, str], model_override: str) -> str:
    if provider == "gemini":
        return call_gemini(trade_json, env, model_override)
    if provider == "openrouter":
        return call_openrouter(trade_json, env, model_override)
    if provider == "groq":
        return call_groq(trade_json, env, model_override)
    if provider == "cohere":
        return call_cohere(trade_json, env, model_override)
    if provider == "blackbox":
        return call_blackbox(trade_json, env, model_override)
    raise RuntimeError(f"Unsupported provider: {provider}")


def validate_markdown_sections(markdown_text: str) -> bool:
    required = [
        "## Symptom",
        "## Root Cause Hypothesis",
        "## Actionable Refactor Directives",
    ]
    return all(section in markdown_text for section in required)


def write_outputs(
    output_dir: Path,
    results: Dict[str, str],
    errors: Dict[str, str],
    skipped: Dict[str, str],
    policy: Optional[Dict] = None,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    for provider, markdown_text in results.items():
        (output_dir / f"{provider}.md").write_text(markdown_text, encoding="utf-8")
    if policy is not None:
        (output_dir / "adaptive_policy.json").write_text(
            json.dumps(policy, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    summary = {
        "successful_providers": sorted(results.keys()),
        "failed_providers": sorted(errors.keys()),
        "skipped_providers": skipped,
        "errors": errors,
        "adaptive_policy_path": str((output_dir / "adaptive_policy.json").resolve()) if policy is not None else None,
        "adaptive_input": [
            {
                "provider": provider,
                "path": str((output_dir / f"{provider}.md").resolve()),
            }
            for provider in sorted(results.keys())
        ],
    }
    (output_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def extract_focus_pairs(markdown_text: str, candidate_pairs: List[str]) -> List[str]:
    lowered = markdown_text.lower()
    mentions = []
    for pair in candidate_pairs:
        count = lowered.count(pair.lower())
        if count > 0:
            mentions.append((pair, count))
    return [pair for pair, _ in sorted(mentions, key=lambda item: (-item[1], item[0]))]


def build_adaptive_policy(
    payload: Dict,
    results: Dict[str, str],
    errors: Dict[str, str],
) -> Dict:
    state = payload.get("state", {}) if isinstance(payload, dict) else {}
    holdings = state.get("holdingsDetailed", []) if isinstance(state, dict) else []
    health_summary = str(state.get("healthSummary", "") or "")
    target_label = str(state.get("targetPursuitLabel", "") or "")
    pnl_today_pct_label = str(state.get("pnlTodayPctLabel", "") or "")
    candidate_pairs = []
    top_candidate = str(state.get("topCandidate", "")).strip().lower()
    if top_candidate and top_candidate != "-":
        candidate_pairs.append(top_candidate)
    candidate_pairs.extend(
        pair.lower()
        for pair in state.get("radarPairs", [])
        if isinstance(pair, str) and pair.strip()
    )
    candidate_pairs = list(dict.fromkeys(candidate_pairs))[:8]
    held_pairs = []
    for item in holdings:
        if not isinstance(item, dict):
            continue
        asset_code = str(item.get("assetCode", "")).strip().lower()
        if not asset_code or asset_code == "idr":
            continue
        held_pairs.append(f"{asset_code}_idr")
    held_pairs = list(dict.fromkeys(held_pairs))[:6]

    successful = sorted(results.keys())
    consensus_strength = min(1.0, len(successful) / 3.0) if successful else 0.0
    merged_text = "\n".join(results.values()).lower()
    health_text = health_summary.lower()

    def parse_pct(label: str) -> float:
        cleaned = str(label or "").replace("%", "").replace("+", "").replace(",", ".").strip()
        try:
            return float(cleaned)
        except ValueError:
            return 0.0

    pnl_today_pct = parse_pct(pnl_today_pct_label)

    slow_rotation_detected = any(
        keyword in merged_text
        for keyword in [
            "slow rotation",
            "stagnan",
            "stuck",
            "funds are trapped",
            "sideways",
            "capital velocity",
        ]
    )
    late_exit_detected = any(
        keyword in merged_text
        for keyword in [
            "late exit",
            "turning into losses",
            "winning trades are turning into losses",
            "late exits",
            "giveback",
        ]
    )
    missed_momentum_detected = any(
        keyword in merged_text
        for keyword in [
            "missed momentum",
            "breakout momentum",
            "missing early breakout",
            "missed breakout",
            "breakout",
        ]
    )
    target_pressure_detected = any(
        keyword in health_text
        for keyword in [
            "forced replan",
            "miss hourly",
            "miss checkpoint",
            "full_chase",
            "emergency_pursuit",
        ]
    ) or target_label.upper() in {"CHASE", "FULL_CHASE", "OVERDRIVE"}

    focus_counter: Dict[str, int] = {}
    for provider_text in results.values():
        for pair in extract_focus_pairs(provider_text, candidate_pairs):
            focus_counter[pair] = focus_counter.get(pair, 0) + 1

    if not focus_counter and top_candidate and top_candidate != "-":
        focus_counter[top_candidate] = max(1, len(successful))

    focus_pairs = [
        pair for pair, _ in sorted(focus_counter.items(), key=lambda item: (-item[1], item[0]))
    ][:4]

    pair_biases = []
    for index, pair in enumerate(focus_pairs):
        mention_score = focus_counter.get(pair, 1)
        support_bias = min(0.08, 0.018 + (consensus_strength * 0.018) + (mention_score - 1) * 0.008 - (index * 0.002))
        caution_bias = 0.0
        if "micro-cap" in merged_text or "liquidity trap" in merged_text:
            caution_bias = min(0.05, 0.008 + (index * 0.004))
        pair_biases.append(
            {
                "pair_id": pair,
                "support_bias": round(max(0.0, support_bias), 4),
                "caution_bias": round(max(0.0, caution_bias), 4),
                "rationale": "Consensus AI audit boost.",
            }
        )
    pair_bias_map = {item["pair_id"]: item for item in pair_biases}

    rotate_now_pairs = focus_pairs[1:3] if slow_rotation_detected and len(focus_pairs) > 1 else focus_pairs[:1]
    hold_longer_pairs = []
    if missed_momentum_detected and focus_pairs:
        hold_longer_pairs.append(focus_pairs[0])
    concentration_pair = None
    if focus_pairs:
        best_focus_pair = max(
            focus_pairs,
            key=lambda pair: (
                pair_bias_map.get(pair, {}).get("support_bias", 0.0) -
                pair_bias_map.get(pair, {}).get("caution_bias", 0.0)
            ),
        )
        if consensus_strength >= 0.45:
            concentration_pair = best_focus_pair
    avoid_pair_families = []
    if "liquidity trap" in merged_text or "micro-cap" in merged_text or "sideways" in merged_text:
        safe_focus_families = {pair.split("_", 1)[0] for pair in focus_pairs}
        avoid_pair_families = [
            pair.split("_", 1)[0]
            for pair in candidate_pairs
            if pair.split("_", 1)[0] not in safe_focus_families
        ][:2]
    replacement_hints = []
    stale_holdings = []
    if focus_pairs:
        replacement_targets = [pair for pair in focus_pairs if pair not in held_pairs] or focus_pairs[:1]
        stale_holdings = [pair for pair in held_pairs if pair not in focus_pairs[:2]]
        if slow_rotation_detected and stale_holdings and replacement_targets:
            for index, cut_pair in enumerate(stale_holdings[:2]):
                replace_pair = replacement_targets[min(index, len(replacement_targets) - 1)]
                if cut_pair == replace_pair:
                    continue
                replacement_hints.append(
                    {
                        "cut_pair": cut_pair,
                        "replace_pair": replace_pair,
                        "rationale": "AI audit melihat modal lebih produktif jika holding stagnan digeser ke pair fokus.",
                    }
                )

    watchdog_root_causes: List[str] = []
    watchdog_actions: List[str] = []
    watchdog_status = "IDLE"
    watchdog_severity = "LOW"
    watchdog_reprimand = ""
    force_rotation = False
    force_concentration = False
    pressure_floor = 0.0
    budget_boost_floor = 1.0
    execution_boost_floor = 1.0
    reserve_relief_floor = 0.0

    if target_pressure_detected or pnl_today_pct < 1.0:
        watchdog_status = "MISS_TARGET"
        watchdog_root_causes.append("pace_target_tertinggal")
        watchdog_actions.append("naikkan tekanan target hourly")
        pressure_floor = max(pressure_floor, 0.72)
        budget_boost_floor = max(budget_boost_floor, 1.42)
        execution_boost_floor = max(execution_boost_floor, 1.22)
        reserve_relief_floor = max(reserve_relief_floor, 0.05)
    if slow_rotation_detected or stale_holdings:
        watchdog_status = "MISS_TARGET"
        watchdog_root_causes.append("holding_stagnan_belum_digeser")
        watchdog_actions.append("paksa rotasi holding stagnan ke kandidat produktif")
        force_rotation = True
        pressure_floor = max(pressure_floor, 0.78)
        budget_boost_floor = max(budget_boost_floor, 1.48)
        execution_boost_floor = max(execution_boost_floor, 1.28)
        reserve_relief_floor = max(reserve_relief_floor, 0.06)
    if missed_momentum_detected:
        watchdog_status = "MISS_TARGET"
        watchdog_root_causes.append("momentum_breakout_terlewat")
        watchdog_actions.append("tingkatkan konsentrasi ke breakout terkuat")
        force_concentration = True
        pressure_floor = max(pressure_floor, 0.82)
        budget_boost_floor = max(budget_boost_floor, 1.56)
        execution_boost_floor = max(execution_boost_floor, 1.34)
        reserve_relief_floor = max(reserve_relief_floor, 0.07)
    if late_exit_detected:
        watchdog_status = "MISS_TARGET"
        watchdog_root_causes.append("exit_terlalu_lambat")
        watchdog_actions.append("pendekkan toleransi winner yang mulai patah")

    if force_rotation and force_concentration:
        watchdog_severity = "CRITICAL"
    elif force_rotation or force_concentration:
        watchdog_severity = "HIGH"
    elif watchdog_status != "IDLE":
        watchdog_severity = "MEDIUM"

    if watchdog_status != "IDLE":
        root_text = ", ".join(watchdog_root_causes[:3]) or "target belum aman"
        action_text = ", ".join(watchdog_actions[:2]) or "paksa replan agresif"
        watchdog_reprimand = (
            f"Target belum aman. Akar masalah: {root_text}. "
            f"Aksi wajib: {action_text}."
        )

    policy = {
        "generated_at_utc": payload.get("generated_at_utc"),
        "policy_ttl_minutes": 150 if consensus_strength >= 0.66 else 110,
        "successful_providers": successful,
        "failed_providers": sorted(errors.keys()),
        "consensus_strength": round(consensus_strength, 4),
        "focus_pairs": focus_pairs,
        "pair_biases": pair_biases,
        "adjustments": {
            "ranking_bias_scale": round(1.0 + (consensus_strength * 0.40), 4),
            "rotation_age_hours_delta": round(-0.18 if slow_rotation_detected else -0.08 * consensus_strength, 4),
            "rotation_score_gap_delta": round(-0.03 if slow_rotation_detected else 0.0, 4),
            "partial_take_profit_pnl_delta": round(-0.32 if late_exit_detected else -0.10 * consensus_strength, 4),
            "winner_run_pnl_delta": round(0.18 if missed_momentum_detected else 0.0, 4),
            "meaningful_exit_profit_delta": round(-0.14 if late_exit_detected else -0.04 * consensus_strength, 4),
            "budget_boost_multiplier_delta": round(0.08 + (consensus_strength * 0.14), 4),
            "reserve_relief_pct_delta": round(0.010 + (consensus_strength * 0.018), 4),
            "allocation_focus_pct_delta": round(0.020 + (consensus_strength * 0.045), 4),
            "extra_slots_delta": 2 if consensus_strength >= 0.82 else (1 if consensus_strength >= 0.64 else 0),
        },
        "signals": {
            "slow_rotation_detected": slow_rotation_detected,
            "late_exit_detected": late_exit_detected,
            "missed_momentum_detected": missed_momentum_detected,
        },
        "execution": {
            "rotate_now_pairs": rotate_now_pairs,
            "hold_longer_pairs": hold_longer_pairs,
            "concentration_pair": concentration_pair,
            "avoid_pair_families": avoid_pair_families,
            "replacement_hints": replacement_hints,
        },
        "watchdog": {
            "status": watchdog_status,
            "severity": watchdog_severity,
            "reprimand": watchdog_reprimand,
            "root_causes": watchdog_root_causes,
            "required_actions": watchdog_actions,
            "force_rotation": force_rotation,
            "force_concentration": force_concentration,
            "pressure_floor": round(pressure_floor, 4),
            "budget_boost_floor": round(budget_boost_floor, 4),
            "execution_boost_floor": round(execution_boost_floor, 4),
            "reserve_relief_floor": round(reserve_relief_floor, 4),
        },
    }
    return policy


def main() -> int:
    args = parse_args()
    input_path = Path(args.input)
    if not input_path.exists():
        print(f"Input file not found: {input_path}", file=sys.stderr)
        return 2

    raw_json = input_path.read_text(encoding="utf-8")
    try:
        parsed = json.loads(raw_json)
    except json.JSONDecodeError as exc:
        print(f"Invalid JSON in input: {exc}", file=sys.stderr)
        return 2
    compact_json = json.dumps(parsed, ensure_ascii=False, separators=(",", ":"))

    env = load_env_files()
    providers = parse_provider_override(args.providers) or provider_chain(args.provider)
    provider_state_path = Path(args.provider_state_file) if args.provider_state_file else None
    provider_state = load_provider_state(provider_state_path) if provider_state_path else {}
    now_ts = time.time()
    results: Dict[str, str] = {}
    errors: Dict[str, str] = {}
    ordered_errors: List[Tuple[str, str]] = []
    skipped: Dict[str, str] = {}

    for provider in providers:
        if not provider_has_credentials(provider, env):
            skipped[provider] = "missing_credentials"
            continue
        available, reason = provider_is_available(provider, provider_state, now_ts)
        if not available:
            skipped[provider] = reason
            continue
        try:
            output = try_provider(provider, compact_json, env, args.model.strip())
            if not output:
                raise RuntimeError("Empty response.")
            if not validate_markdown_sections(output):
                raise RuntimeError("Response missing required markdown sections.")
            results[provider] = output
            if provider_state_path:
                provider_state[provider] = {
                    "cooldown_until_epoch": 0,
                    "reason": "",
                    "last_success_epoch": int(now_ts),
                }
            if not args.all_providers:
                if provider_state_path:
                    save_provider_state(provider_state_path, provider_state)
                print(output)
                return 0
        except urllib.error.HTTPError as exc:
            details = exc.read().decode("utf-8", errors="replace")[:1200]
            msg = f"HTTP {exc.code}: {details}"
            errors[provider] = msg
            ordered_errors.append((provider, msg))
            if provider_state_path:
                cooldown_hours, reason = classify_provider_failure(msg, args.cooldown_hours)
                provider_state[provider] = {
                    "cooldown_until_epoch": int(now_ts + cooldown_hours * 3600),
                    "reason": reason,
                    "last_error_epoch": int(now_ts),
                }
        except Exception as exc:  # noqa: BLE001
            msg = str(exc)
            errors[provider] = msg
            ordered_errors.append((provider, msg))
            if provider_state_path:
                cooldown_hours, reason = classify_provider_failure(msg, args.cooldown_hours)
                provider_state[provider] = {
                    "cooldown_until_epoch": int(now_ts + cooldown_hours * 3600),
                    "reason": reason,
                    "last_error_epoch": int(now_ts),
                }

    policy = build_adaptive_policy(parsed, results, errors) if results else None

    if provider_state_path:
        save_provider_state(provider_state_path, provider_state)

    if args.output_dir:
        write_outputs(Path(args.output_dir), results, errors, skipped, policy=policy)

    if args.all_providers:
        print(
            json.dumps(
                {
                    "successful_providers": sorted(results.keys()),
                    "failed_providers": sorted(errors.keys()),
                    "skipped_providers": skipped,
                    "output_dir": str(Path(args.output_dir).resolve()) if args.output_dir else None,
                    "adaptive_policy": policy,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0 if results else 1

    print("All providers failed.", file=sys.stderr)
    for provider, message in ordered_errors:
        print(f"- {provider}: {message}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
