#!/usr/bin/env bash
# KiBot Trinity v7 Pipeline Environment Setup
# Configures JAVA_HOME and ANDROID_HOME for automated builds.

export JAVA_HOME="/usr/local/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="/Users/kiki/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

if [[ -d "$JAVA_HOME" ]]; then
    echo "[PIPELINE] JAVA_HOME set to $JAVA_HOME"
    java -version
else
    echo "[PIPELINE][ERROR] JAVA_HOME not found at $JAVA_HOME"
    exit 1
fi

if [[ -d "$ANDROID_HOME" ]]; then
    echo "[PIPELINE] ANDROID_HOME set to $ANDROID_HOME"
else
    echo "[PIPELINE][WARN] ANDROID_HOME not directory-accessible or missing: $ANDROID_HOME"
fi

# Ensure local.properties for Gradle
cat > "$(dirname "$0")/../local.properties" <<EOF
sdk.dir=$ANDROID_HOME
EOF

echo "[PIPELINE] Environment configured for build."
