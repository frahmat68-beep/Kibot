#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <rolling_6h_json_path> [output_base_dir]"
  exit 1
fi

INPUT_JSON="$1"
OUTPUT_BASE="${2:-.tmp/ai-audits}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

mkdir -p "${OUTPUT_BASE}"

echo "Starting hourly AI audit loop..."
echo "Input: ${INPUT_JSON}"
echo "Output base: ${OUTPUT_BASE}"

while true; do
  TS="$(date +%Y%m%d-%H%M%S)"
  OUT_DIR="${OUTPUT_BASE}/${TS}"
  mkdir -p "${OUT_DIR}"

  echo "[${TS}] Running all providers..."
  python3 "${PROJECT_ROOT}/scripts/audit_trading_6h_ai.py" \
    --input "${INPUT_JSON}" \
    --all-providers \
    --output-dir "${OUT_DIR}" | tee "${OUT_DIR}/runner_result.json"

  ln -sfn "${OUT_DIR}" "${OUTPUT_BASE}/latest"
  echo "[${TS}] Done. Sleeping 3600s..."
  sleep 3600
done
