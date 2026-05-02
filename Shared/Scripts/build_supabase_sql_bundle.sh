#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATIONS_DIR="${ROOT_DIR}/infra/supabase/migrations"
DIST_DIR="${ROOT_DIR}/.dist/supabase"
BUNDLE_PATH="${DIST_DIR}/control-plane.sql"

mkdir -p "${DIST_DIR}"

{
  echo "-- KiBot Supabase control-plane bundle"
  echo "-- Generated at $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo
  for file in "${MIGRATIONS_DIR}"/*.sql; do
    echo "-- >>> $(basename "${file}")"
    cat "${file}"
    echo
    echo "-- <<< $(basename "${file}")"
    echo
  done
} > "${BUNDLE_PATH}"

echo "SQL bundle ready: ${BUNDLE_PATH}"
