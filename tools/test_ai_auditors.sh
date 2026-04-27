#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TMP_DIR="${PROJECT_ROOT}/.tmp/ai-auditor-test"
INPUT_JSON="${TMP_DIR}/sample_6h.json"
OUT_DIR="${TMP_DIR}/out"

mkdir -p "${TMP_DIR}" "${OUT_DIR}"

cat > "${INPUT_JSON}" <<'JSON'
{
  "window": "last_6h",
  "portfolio_start_idr": 1000000,
  "portfolio_end_idr": 1004200,
  "trades": [
    {"pair":"xrp_idr","side":"BUY","pnl_pct":0.6,"hold_minutes":45},
    {"pair":"pepe_idr","side":"BUY","pnl_pct":-2.4,"hold_minutes":210},
    {"pair":"doge_idr","side":"SELL","pnl_pct":1.1,"hold_minutes":60}
  ],
  "open_positions": [
    {"pair":"shib_idr","unrealized_pnl_pct":-1.8,"age_minutes":170}
  ]
}
JSON

python3 "${PROJECT_ROOT}/tools/audit_trading_30m_ai.py" \
  --input "${INPUT_JSON}" \
  --all-providers \
  --output-dir "${OUT_DIR}"

echo "AI auditor test output:"
cat "${OUT_DIR}/summary.json"
