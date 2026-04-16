#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env"
BUNDLE_PATH="${ROOT_DIR}/.dist/supabase/control-plane.sql"

env_value() {
  local key="$1"
  if [[ ! -f "${ENV_FILE}" ]]; then
    return 0
  fi
  sed -n "s/^${key}=//p" "${ENV_FILE}" | head -n 1
}

DIRECT_URL="${SUPABASE_DB_URL:-}"
if [[ -z "${DIRECT_URL}" ]]; then
  DIRECT_URL="$(env_value SUPABASE_DB_URL)"
fi
POOLER_URL="${SUPABASE_POOLER_URL:-}"
if [[ -z "${POOLER_URL}" ]]; then
  POOLER_URL="$(env_value SUPABASE_POOLER_URL)"
fi
FALLBACK_URL="${DATABASE_URL:-}"
if [[ -z "${FALLBACK_URL}" ]]; then
  FALLBACK_URL="$(env_value DATABASE_URL)"
fi
if [[ -z "${FALLBACK_URL}" ]]; then
  FALLBACK_URL="${POSTGRES_URL:-}"
fi
if [[ -z "${FALLBACK_URL}" ]]; then
  FALLBACK_URL="$(env_value POSTGRES_URL)"
fi

DB_URL="${DIRECT_URL:-${POOLER_URL:-${FALLBACK_URL:-}}}"

if [[ -z "${DB_URL}" ]]; then
  echo "SUPABASE_DB_URL belum ada di .env."
  echo "Atau isi SUPABASE_POOLER_URL jika koneksi direct tidak tersedia."
  echo "Isi dulu formatnya seperti:"
  echo "SUPABASE_DB_URL=postgresql://postgres:[PASSWORD-DB]@db.<project-ref>.supabase.co:5432/postgres"
  exit 1
fi

"${ROOT_DIR}/scripts/build_supabase_sql_bundle.sh" >/dev/null

if command -v psql >/dev/null 2>&1; then
  psql "${DB_URL}" -v ON_ERROR_STOP=1 -f "${BUNDLE_PATH}"
  echo "Supabase migrations applied successfully via psql."
  exit 0
fi

if python3 -c "import psycopg" >/dev/null 2>&1; then
  KIBOT_DIRECT_URL="${DIRECT_URL}" \
  KIBOT_POOLER_URL="${POOLER_URL}" \
  KIBOT_FALLBACK_URL="${FALLBACK_URL}" \
  KIBOT_SQL_BUNDLE="${BUNDLE_PATH}" \
  python3 - <<'PY'
import os
from pathlib import Path
import psycopg

bundle_path = Path(os.environ["KIBOT_SQL_BUNDLE"])
sql = bundle_path.read_text()
candidate_urls = [
    ("direct", os.environ.get("KIBOT_DIRECT_URL", "").strip()),
    ("pooler", os.environ.get("KIBOT_POOLER_URL", "").strip()),
    ("fallback", os.environ.get("KIBOT_FALLBACK_URL", "").strip()),
]

attempt_errors = []
for label, db_url in candidate_urls:
    if not db_url:
        continue
    try:
        with psycopg.connect(db_url, autocommit=True, connect_timeout=20) as conn:
            with conn.cursor() as cur:
                cur.execute(sql)
        print(f"Supabase migrations applied successfully via psycopg ({label}).")
        raise SystemExit(0)
    except Exception as exc:
        attempt_errors.append(f"{label}: {type(exc).__name__}: {exc}")

for item in attempt_errors:
    print(item)
raise SystemExit(1)
PY
  exit 0
fi

echo "psql tidak ada dan psycopg juga belum tersedia."
echo "Pasang salah satu dulu:"
echo "brew install libpq && brew link --force libpq"
echo "atau"
echo "python3 -m pip install --user 'psycopg[binary]'"
exit 1
