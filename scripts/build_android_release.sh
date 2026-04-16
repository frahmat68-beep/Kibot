#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="${ROOT_DIR}/.dist/android/stable"
KEYSTORE_ENV="${ROOT_DIR}/.secrets/android-keystore.env"
ANDROID_BUILD_DIR="${KIBOT_ANDROID_BUILD_DIR:-$HOME/.kibot-build/apps-android}"

if [[ ! -f "${ROOT_DIR}/local.properties" ]]; then
  echo "local.properties is missing. Run scripts/setup_android_sdk.sh first."
  exit 1
fi

if [[ ! -f "${KEYSTORE_ENV}" ]]; then
  echo ".secrets/android-keystore.env is missing. Run scripts/generate_release_keystore.sh first."
  exit 1
fi

env_value() {
  local key="$1"
  sed -n "s/^${key}=//p" "${KEYSTORE_ENV}" | head -n 1
}

export ANDROID_RELEASE_KEYSTORE_PATH="$(env_value ANDROID_RELEASE_KEYSTORE_PATH)"
export ANDROID_RELEASE_STORE_PASSWORD="$(env_value ANDROID_RELEASE_STORE_PASSWORD)"
export ANDROID_RELEASE_KEY_ALIAS="$(env_value ANDROID_RELEASE_KEY_ALIAS)"
export ANDROID_RELEASE_KEY_PASSWORD="$(env_value ANDROID_RELEASE_KEY_PASSWORD)"

mkdir -p "${DIST_DIR}"

if [[ -d "${ANDROID_BUILD_DIR}" ]]; then
  rm -rf "${ANDROID_BUILD_DIR}"
fi

PREV_CODE=0
if [[ -f "${DIST_DIR}/latest.json" ]]; then
  PREV_CODE="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("versionCode",0))' "${DIST_DIR}/latest.json" 2>/dev/null || echo 0)"
fi
NEXT_CODE=$((PREV_CODE + 1))
export KIBOT_ANDROID_VERSION_CODE="${NEXT_CODE}"
export KIBOT_ANDROID_VERSION_NAME="1.0.${NEXT_CODE}"

"${ROOT_DIR}/gradlew" :apps:android:assembleRelease

APK_PATH="${ANDROID_BUILD_DIR}/outputs/apk/release/android-release.apk"
METADATA_PATH="${ANDROID_BUILD_DIR}/outputs/apk/release/output-metadata.json"
if [[ ! -f "${APK_PATH}" ]]; then
  echo "Release APK not found at ${APK_PATH}"
  exit 1
fi

if [[ ! -f "${METADATA_PATH}" ]]; then
  echo "Release metadata not found at ${METADATA_PATH}"
  exit 1
fi

VERSION_NAME="$(python3 -c 'import json, sys; data = json.load(open(sys.argv[1])); print(data["elements"][0]["versionName"])' "${METADATA_PATH}")"
VERSION_CODE="$(python3 -c 'import json, sys; data = json.load(open(sys.argv[1])); print(data["elements"][0]["versionCode"])' "${METADATA_PATH}")"
SHA256="$(shasum -a 256 "${APK_PATH}" | awk '{print $1}')"
MANIFEST_PATH="${DIST_DIR}/latest.json"
TARGET_APK="${DIST_DIR}/kibot-android-latest.apk"

find "${DIST_DIR}" -maxdepth 1 -type f -name "*.apk" -delete
cp "${APK_PATH}" "${TARGET_APK}"

cat > "${MANIFEST_PATH}" <<EOF
{
  "channel": "stable-private",
  "versionName": "${VERSION_NAME}",
  "versionCode": ${VERSION_CODE},
  "artifact": "kibot-android-latest.apk",
  "sha256": "${SHA256}",
  "generatedAt": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
}
EOF

echo "Release artifact ready in ${DIST_DIR}"
