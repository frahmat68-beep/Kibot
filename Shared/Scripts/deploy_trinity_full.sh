#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[1/4] Bootstrap local runtime files"
bash "${ROOT_DIR}/scripts/bootstrap_local.sh"

echo "[2/4] Full shadow deploy to KiBot + KiBot"
bash "${ROOT_DIR}/infra/scripts/deploy_shadow_mode_oracle.sh"

echo "[3/4] Verify Trinity smoke state"
bash "${ROOT_DIR}/scripts/smoke_test_trinity.sh"

echo "[4/4] Done"
echo "If you want SSH service checks in smoke test, set SSH_HOST and SSH_KEY before rerunning scripts/smoke_test_trinity.sh."
