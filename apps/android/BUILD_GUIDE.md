# KiCryp Android App - Build & Deployment Guide

## Prerequisites

### System Requirements
- macOS/Linux/Windows with Android development tools
- Android SDK 34 (API 34)
- Build tools version 34.0.0 or higher
- Kotlin 1.9.10
- Gradle 8.x

### Installation

#### 1. Install Android SDK
```bash
# Via Android Studio (recommended)
# Open Android Studio > Tools > SDK Manager
# Install SDK 34 and Build Tools 34.0.0

# Via command line
sdkmanager "platforms;android-34"
sdkmanager "build-tools;34.0.0"
```

#### 2. Set ANDROID_HOME
```bash
# Add to ~/.zshrc or ~/.bash_profile
export ANDROID_HOME=~/Library/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools/bin

# Verify
echo $ANDROID_HOME
adb --version
```

#### 3. Clone/Navigate to Project
```bash
cd ~/Documents/Web\ Develop/KiCryp/apps/android
```

## Build Variants

### Debug Build
```bash
./gradlew assembleDebug
```
- Output: `app/build/outputs/apk/debug/app-debug.apk`
- Features: Debuggable, includes logs
- Size: ~10-15MB
- Signing: Auto-signed with debug key

### Release Build
```bash
./gradlew assembleRelease
```
- Output: `app/build/outputs/apk/release/app-release-unsigned.apk`
- Features: Optimized, no logs, minified
- Size: ~4-6MB
- Signing: Requires keystore (see below)

## Debug Build & Testing

### 1. Build APK
```bash
./gradlew clean assembleDebug
```

### 2. Connect Device
```bash
# Enable Developer Mode:
# Settings > About > Build Number (tap 7 times)
# Settings > Developer Options > USB Debugging (enable)

# Connect via USB
adb devices

# Expected output:
# emulator-5554    device
# FA7AX1A1234      device
```

### 3. Install APK
```bash
# Install on connected device
./gradlew installDebug

# Or manually
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. Run App
```bash
# Launch via adb
adb shell am start -n com.kicryp.android/.MainActivity

# Or install and run in one command
./gradlew installDebugAndRun
```

### 5. View Logs
```bash
# All logs
adb logcat

# KiCryp logs only
adb logcat | grep "KiCryp"

# With timestamps
adb logcat -v time | grep "KiCryp"

# Save to file
adb logcat > ~/kicryp_debug.log
```

## Running on Emulator

### 1. Create Virtual Device
```bash
# Via Android Studio
# Tools > Device Manager > Create Virtual Device

# Recommended specs:
# - Device: Pixel 6 or Pixel 8
# - API Level: 34
# - RAM: 4GB
# - Storage: 2GB
```

### 2. Start Emulator
```bash
emulator -avd Pixel_8 -no-boot-anim

# Or use Android Studio Device Manager
```

### 3. Install & Test
```bash
# Check connection
adb devices

# Install app
./gradlew installDebug

# Run
adb shell am start -n com.kicryp.android/.MainActivity
```

### 4. Testing with Mock Server

#### Local WebSocket Server (Python)
```python
# test_server.py
from websockets.server import serve
import asyncio
import json
from datetime import datetime

async def handler(websocket, path):
    print(f"Client connected: {websocket}")
    try:
        while True:
            # Listen for requests
            msg = await asyncio.wait_for(websocket.recv(), timeout=10.0)
            print(f"Received: {msg}")
            
            # Send mock response
            response = {
                "balance": {
                    "idr": 150000000,
                    "usdt": 7500,
                    "total": 157500000
                },
                "pnl": {
                    "daily": 3750000,
                    "percentage": 2.58,
                    "trend": [1000000 * (i+1) for i in range(24)]
                },
                "capitalSplit": {
                    "highConviction": 105000000,
                    "aggressive": 45000000
                },
                "activeTrades": [
                    {
                        "pair": "BTC/USDT",
                        "entry": 42000,
                        "current": 43000,
                        "profit": 1000000,
                        "profitPct": 2.38
                    }
                ],
                "status": "Trading",
                "timestamp": int(datetime.now().timestamp() * 1000)
            }
            await websocket.send(json.dumps(response))
            await asyncio.sleep(0.1)
    except asyncio.TimeoutError:
        print("No message received within timeout")
    except Exception as e:
        print(f"Error: {e}")
    finally:
        await websocket.close()

async def main():
    async with serve(handler, "0.0.0.0", 8787):
        print("WebSocket server running on ws://0.0.0.0:8787/kicryp/status")
        await asyncio.Future()

if __name__ == "__main__":
    asyncio.run(main())
```

#### Run Test Server
```bash
# Install dependencies
pip install websockets

# Run server
python test_server.py

# In another terminal, start emulator and connect app to localhost:8787
```

## Release Build & Signing

### 1. Create Keystore
```bash
# Generate keystore (one time only)
keytool -genkey -v -keystore kicryp-release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias kicryp_key

# You'll be prompted for:
# First and last name: KiCryp App
# Organization: Trinity Trading
# City/State/Country: Your location
# Keystore password: (choose strong password)
# Key password: (same as keystore or different)
```

### 2. Build Signed Release APK
```bash
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=../../../kicryp-release.jks \
  -Pandroid.injected.signing.store.password=<PASSWORD> \
  -Pandroid.injected.signing.key.alias=kicryp_key \
  -Pandroid.injected.signing.key.password=<PASSWORD>
```

### 3. Locate Signed APK
```bash
# Find output
ls app/build/outputs/apk/release/

# Extract
open app/build/outputs/apk/release/

# File: app-release.apk (ready to distribute)
```

### 4. Verify Signature
```bash
jarsigner -verify -verbose app/build/outputs/apk/release/app-release.apk
```

## Google Play Deployment

### Prerequisites
- Google Play Developer Account ($25 one-time)
- Google Play Console access
- Signed APK (from above)

### 1. Create App in Console
```
https://play.google.com/console
→ New App
→ Name: "KiCryp Trading Dashboard"
→ Category: Finance
→ Create
```

### 2. Prepare Store Listing
```
→ App details
  - Short description: (50 chars max)
  - Full description: (4000 chars max)
  - Screenshots (5): (1080×1920px each)
  - Icon (512×512px): PNG, 32-bit
  - Featured graphic (1024×500px)
  
→ Content rating
  - Financial instrument trading
  - Real money betting/trading

→ Pricing & distribution
  - Free or paid
  - Countries/regions
```

### 3. Upload APK
```
→ Release > Production
→ Create new release
→ Upload signed APK
→ Add release notes
→ Review and deploy
```

## Continuous Integration (GitHub Actions)

### Setup CI/CD
Create `.github/workflows/android-build.yml`:

```yaml
name: Android Build

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main, develop ]

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK
      uses: actions/setup-java@v3
      with:
        java-version: '11'
        distribution: 'temurin'
    
    - name: Build debug APK
      working-directory: ./apps/android
      run: ./gradlew assembleDebug
    
    - name: Run tests
      working-directory: ./apps/android
      run: ./gradlew test
    
    - name: Upload APK
      uses: actions/upload-artifact@v3
      with:
        name: app-debug.apk
        path: apps/android/app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

### Build Errors

#### "Failed to resolve: androidx.compose.ui:ui"
```bash
# Solution: Update gradle wrapper
./gradlew wrapper --gradle-version 8.4
./gradlew --version
```

#### "Android SDK not found"
```bash
# Solution: Set ANDROID_HOME
export ANDROID_HOME=~/Library/Android/Sdk
echo $ANDROID_HOME
./gradlew --version
```

#### "Gradle build failed"
```bash
# Solution: Clean and rebuild
./gradlew clean
./gradlew assembleDebug --info
```

### Installation Errors

#### "INSTALL_FAILED_INVALID_APK"
```bash
# Solution: Check file integrity
zipalign -v 4 app/build/outputs/apk/debug/app-debug.apk app-aligned.apk
adb install app-aligned.apk
```

#### "INSTALL_FAILED_TEST_ONLY"
```bash
# Solution: Device doesn't allow test APKs
# - Uninstall existing app first
adb uninstall com.kicryp.android
./gradlew installDebug
```

#### "INSTALL_FAILED_INSUFFICIENT_STORAGE"
```bash
# Solution: Clear app data on device
adb shell pm clear com.kicryp.android
./gradlew installDebug
```

### Runtime Errors

#### "WebSocket connection refused"
```bash
# Solution: Check server is running
curl http://localhost:8787
nc -zv localhost 8787

# Check app has INTERNET permission
adb shell dumpsys package com.kicryp.android | grep permission
```

#### "App crashes on startup"
```bash
# Solution: Check logcat
adb logcat -s "KiCryp" *:E

# Check minimum SDK
# Update if needed in app/build.gradle.kts minSdk = 28
```

## Performance Profiling

### CPU Profiling
```bash
# Record trace
./gradlew appBundle --profile

# Analyze with Android Studio
# Build > Analyze APK > app/build/outputs/bundle/release/app-release.aab
```

### Memory Profiling
```bash
# Start app with profiler
adb shell am start -n com.kicryp.android/.MainActivity

# Open Android Profiler in Android Studio
# Run > Profiler
```

### Network Monitoring
```bash
# Enable Network Inspector in Logcat
adb logcat -s "Network"

# Check WebSocket frames
adb tcpdump -i any 'tcp port 8787' -w /tmp/capture.pcap
```

## Testing Checklist

- [ ] App launches without crashes
- [ ] Settings screen accessible
- [ ] Can input server config
- [ ] Connects to mock server
- [ ] Data displays correctly
- [ ] All buttons are clickable
- [ ] Text is readable (18sp minimum)
- [ ] Colors match design spec
- [ ] Numbers formatted correctly
- [ ] Back button works
- [ ] No UI glitches or jank
- [ ] Battery drain is minimal
- [ ] Works on Android 9+
- [ ] Works on tablets (landscape/portrait)

## Version Management

### Update Version
Edit `app/build.gradle.kts`:
```kotlin
android {
    defaultConfig {
        versionCode = 2  // Increment for each release
        versionName = "1.0.1"  // Semantic versioning
    }
}
```

### Tag Release
```bash
git tag -a v1.0.1 -m "Release 1.0.1"
git push origin v1.0.1
```

## Distribution

### Direct Installation
```bash
# Share APK via email/cloud
adb pull app/build/outputs/apk/debug/app-debug.apk
# Send file to user
# User: adb install kicryp.apk
```

### Google Play
See "Google Play Deployment" section above

### Enterprise Distribution
- Enterprise Google Play console
- MDM/EMM solution
- Internal app distribution service

---

**For questions, check ARCHITECTURE.md for technical details or README.md for usage guide.**
