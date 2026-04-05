#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <normal|emergency|status> <dropin-file>"
  exit 1
fi

MODE="$1"
DROPIN="$2"

upsert_env() {
  local key="$1"
  local value="$2"
  if grep -q "^Environment=${key}=" "$DROPIN"; then
    sed -i "s|^Environment=${key}=.*|Environment=${key}=${value}|" "$DROPIN"
  else
    printf 'Environment=%s=%s\n' "$key" "$value" >> "$DROPIN"
  fi
}

ensure_header() {
  if ! grep -q '^\[Service\]$' "$DROPIN"; then
    tmp="$(mktemp)"
    {
      echo "[Service]"
      cat "$DROPIN"
    } > "$tmp"
    mv "$tmp" "$DROPIN"
  fi
}

case "$MODE" in
  status)
    grep -E '^Environment=BOT_(SUPABASE|POLL|COMMANDS|RECENT_ORDERS|RECENT_FILLS|ANALYSIS|STRATEGY_METRICS)' "$DROPIN" || true
    exit 0
    ;;
  emergency)
    upsert_env BOT_SUPABASE_LOG_UPLOAD_ENABLED true
    upsert_env BOT_SUPABASE_LOG_MIN_LEVEL WARN
    upsert_env BOT_SUPABASE_NONCRITICAL_WRITE_ENABLED false
    upsert_env BOT_POLL_INTERVAL_MS 2500
    upsert_env BOT_COMMANDS_REFRESH_INTERVAL_MS 4000
    upsert_env BOT_RECENT_ORDERS_REFRESH_INTERVAL_MS 12000
    upsert_env BOT_RECENT_FILLS_REFRESH_INTERVAL_MS 12000
    upsert_env BOT_ANALYSIS_PUBLISH_INTERVAL_MS 180000
    upsert_env BOT_STRATEGY_METRICS_PUBLISH_INTERVAL_MS 900000
    ;;
  normal)
    upsert_env BOT_SUPABASE_LOG_UPLOAD_ENABLED true
    upsert_env BOT_SUPABASE_LOG_MIN_LEVEL INFO
    upsert_env BOT_SUPABASE_NONCRITICAL_WRITE_ENABLED true
    upsert_env BOT_POLL_INTERVAL_MS 1200
    upsert_env BOT_COMMANDS_REFRESH_INTERVAL_MS 1200
    upsert_env BOT_RECENT_ORDERS_REFRESH_INTERVAL_MS 2200
    upsert_env BOT_RECENT_FILLS_REFRESH_INTERVAL_MS 2200
    upsert_env BOT_ANALYSIS_PUBLISH_INTERVAL_MS 12000
    upsert_env BOT_STRATEGY_METRICS_PUBLISH_INTERVAL_MS 120000
    ;;
  *)
    echo "Unknown mode: $MODE"
    exit 1
    ;;
esac

mkdir -p "$(dirname "$DROPIN")"
touch "$DROPIN"
ensure_header

grep -E '^Environment=BOT_(SUPABASE|POLL|COMMANDS|RECENT_ORDERS|RECENT_FILLS|ANALYSIS|STRATEGY_METRICS)' "$DROPIN" || true
