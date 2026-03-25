#!/usr/bin/env python3
"""
AI auditor for rolling 6-hour trading performance.

Usage:
  python3 scripts/audit_trading_6h_ai.py --input /path/to/last_6h.json
  python3 scripts/audit_trading_6h_ai.py --input /path/to/last_6h.json --provider gemini
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import textwrap
import urllib.error
import urllib.request
from pathlib import Path
from typing import Dict, List, Tuple


SYSTEM_ROLE = (
    "You are a Ruthless Algorithmic Trading Auditor focusing on High Capital Velocity. "
    "Your job is to critically analyze the rolling 6-hour trading performance data of a "
    "Kotlin-based crypto bot. You must aggressively hunt for capital inefficiencies, slow "
    "rotations, and missed momentum. Produce a highly technical, developer-facing bug report "
    "and optimization guide. Your output will be executed by an AI Coding Assistant "
    "(Codex/Copilot) to continuously rewrite and tighten the bot's logic."
)

AUDIT_INSTRUCTIONS = textwrap.dedent(
    """
    Instructions for Output:
    Analyze the provided JSON data of the last 6 hours of trades. Format your response in strict Markdown.
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
    parser = argparse.ArgumentParser(description="Audit 6-hour trading JSON using LLM providers.")
    parser.add_argument("--input", required=True, help="Path to rolling 6-hour JSON data.")
    parser.add_argument(
        "--provider",
        choices=["auto", "gemini", "openrouter", "groq", "cohere"],
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
        "Below is the rolling 6-hour trading performance JSON to audit:\n"
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
    last_error: Exception | None = None

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


def provider_chain(selected: str) -> List[str]:
    if selected != "auto":
        return [selected]
    return ["gemini", "openrouter", "groq", "cohere"]


def try_provider(provider: str, trade_json: str, env: Dict[str, str], model_override: str) -> str:
    if provider == "gemini":
        return call_gemini(trade_json, env, model_override)
    if provider == "openrouter":
        return call_openrouter(trade_json, env, model_override)
    if provider == "groq":
        return call_groq(trade_json, env, model_override)
    if provider == "cohere":
        return call_cohere(trade_json, env, model_override)
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
    policy: Dict | None = None,
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

    successful = sorted(results.keys())
    consensus_strength = min(1.0, len(successful) / 3.0) if successful else 0.0
    merged_text = "\n".join(results.values()).lower()

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
        support_bias = min(0.05, 0.014 + (consensus_strength * 0.012) + (mention_score - 1) * 0.006 - (index * 0.002))
        caution_bias = 0.0
        if "micro-cap" in merged_text or "liquidity trap" in merged_text:
            caution_bias = min(0.03, 0.006 + (index * 0.003))
        pair_biases.append(
            {
                "pair_id": pair,
                "support_bias": round(max(0.0, support_bias), 4),
                "caution_bias": round(max(0.0, caution_bias), 4),
                "rationale": "Consensus AI audit boost.",
            }
        )

    policy = {
        "generated_at_utc": payload.get("generated_at_utc"),
        "successful_providers": successful,
        "failed_providers": sorted(errors.keys()),
        "consensus_strength": round(consensus_strength, 4),
        "focus_pairs": focus_pairs,
        "pair_biases": pair_biases,
        "adjustments": {
            "ranking_bias_scale": round(1.0 + (consensus_strength * 0.28), 4),
            "rotation_age_hours_delta": round(-0.18 if slow_rotation_detected else -0.08 * consensus_strength, 4),
            "rotation_score_gap_delta": round(-0.03 if slow_rotation_detected else 0.0, 4),
            "partial_take_profit_pnl_delta": round(-0.32 if late_exit_detected else -0.10 * consensus_strength, 4),
            "winner_run_pnl_delta": round(0.18 if missed_momentum_detected else 0.0, 4),
            "meaningful_exit_profit_delta": round(-0.10 if late_exit_detected else 0.0, 4),
        },
        "signals": {
            "slow_rotation_detected": slow_rotation_detected,
            "late_exit_detected": late_exit_detected,
            "missed_momentum_detected": missed_momentum_detected,
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
    providers = provider_chain(args.provider)
    results: Dict[str, str] = {}
    errors: Dict[str, str] = {}
    ordered_errors: List[Tuple[str, str]] = []

    for provider in providers:
        try:
            output = try_provider(provider, compact_json, env, args.model.strip())
            if not output:
                raise RuntimeError("Empty response.")
            if not validate_markdown_sections(output):
                raise RuntimeError("Response missing required markdown sections.")
            results[provider] = output
            if not args.all_providers:
                print(output)
                return 0
        except urllib.error.HTTPError as exc:
            details = exc.read().decode("utf-8", errors="replace")[:1200]
            msg = f"HTTP {exc.code}: {details}"
            errors[provider] = msg
            ordered_errors.append((provider, msg))
        except Exception as exc:  # noqa: BLE001
            msg = str(exc)
            errors[provider] = msg
            ordered_errors.append((provider, msg))

    policy = build_adaptive_policy(parsed, results, errors) if results else None

    if args.output_dir:
        write_outputs(Path(args.output_dir), results, errors, policy=policy)

    if args.all_providers:
        print(
            json.dumps(
                {
                    "successful_providers": sorted(results.keys()),
                    "failed_providers": sorted(errors.keys()),
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
