#!/usr/bin/env bash
set -euo pipefail

cd "/Users/kiki/Documents/Web Develop/KiCryp"
./gradlew :apps:mac-engine:compileKotlin --no-daemon --console=plain --stacktrace
status=$?
echo "$status" > /tmp/mac_compile.exit
exit "$status"
