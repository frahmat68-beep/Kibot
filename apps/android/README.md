# KiCryp Android Mobile Dashboard

A simple, non-technical Android dashboard app for monitoring and controlling the KiCryp trading bot. Built with Kotlin and Jetpack Compose, it connects to the Mac Engine server via WebSocket to display real-time trading data.

## Features

- **WebSocket Connection**: Direct connection to KiCryp Mac Engine server (default: localhost:8787)
- **Simple Dashboard**: 
  - Current balance (IDR + USDT)
  - Daily P&L with visual trend
  - Capital allocation visualization
  - Active trades list
- **Control Panel**: Start/Stop trading buttons
- **Configuration**: Configurable server host and port
- **Auto-Reconnect**: Handles disconnections gracefully
- **Dark Theme**: Easy on the eyes for extended use
- **Minimal Data Transfer**: Efficient periodic updates every 5 seconds

## Requirements

- Android 9 (API 28) or higher
- Android Studio 2023.1 or higher
- Kotlin 1.9.10
- Android SDK 34

## Building

### Prerequisites
```bash
# Install Android SDK 34 and Build Tools
# Configure ANDROID_HOME in your environment
export ANDROID_HOME=~/Library/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools
```

### Build Steps

1. **Navigate to the Android project**:
   ```bash
   cd apps/android
   ```

2. **Build the APK**:
   ```bash
   ./gradlew assembleDebug
   ```
   
   Or for release:
   ```bash
   ./gradlew assembleRelease
   ```

3. **Install on connected device**:
   ```bash
   ./gradlew installDebug
   ```

4. **Run tests**:
   ```bash
   ./gradlew test
   ```

## Running

### Emulator
```bash
# Start Android emulator
emulator -avd <emulator-name>

# Install and run
./gradlew installDebug
```

### Physical Device
```bash
# Enable Developer Mode and USB Debugging on device
# Connect device via USB
./gradlew installDebug
```

## Configuration

The app connects to a KiCryp server via WebSocket. Configure the server address:

1. Open the app
2. Tap the **⚙️ Settings** button (top-right)
3. Enter server host/IP and port
4. Tap **Save Settings**

Default: `localhost:8787`

## Project Structure

```
apps/android/
├── app/
│   ├── src/main/
│   │   ├── kotlin/com/kicryp/android/
│   │   │   ├── MainActivity.kt          # Main activity & view model
│   │   │   ├── websocket/
│   │   │   │   └── KiCrypWebSocketClient.kt  # WebSocket connection logic
│   │   │   ├── data/
│   │   │   │   └── BotStatus.kt         # Data models
│   │   │   ├── ui/
│   │   │   │   ├── DashboardScreen.kt   # Dashboard UI (Compose)
│   │   │   │   └── SettingsScreen.kt    # Settings UI (Compose)
│   │   │   └── util/
│   │   │       └── PreferencesManager.kt # SharedPreferences wrapper
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── values/
│   │   │   │   ├── strings.xml
│   │   │   │   ├── colors.xml
│   │   │   │   └── themes.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

## API Specification

### WebSocket Connection

**URL Format**: `ws://[host]:[port]/kicryp/status`

**Example**: `ws://192.168.1.100:8787/kicryp/status`

### Request Format

```json
{
  "action": "getStatus"
}
```

### Response Format

```json
{
  "balance": {
    "idr": 100000000,
    "usdt": 5000,
    "total": 105000000
  },
  "pnl": {
    "daily": 2500000,
    "percentage": 2.5,
    "trend": [1000000, 1200000, 1500000, ...]
  },
  "capitalSplit": {
    "highConviction": 70000000,
    "aggressive": 30000000
  },
  "activeTrades": [
    {
      "pair": "BTC/USDT",
      "entry": 42000,
      "current": 43000,
      "profit": 2500000,
      "profitPct": 2.38
    }
  ],
  "status": "Trading",
  "timestamp": 1699564800000
}
```

### Commands

Send commands to control the bot:

```json
{
  "action": "command",
  "value": "start"
}
```

or

```json
{
  "action": "command",
  "value": "stop"
}
```

## UI/UX Design

### Color Scheme
- **Primary**: #0066CC (Blue) - Main actions
- **Accent**: #FF9900 (Orange) - Secondary info
- **Profit**: #00C851 (Green) - Positive values
- **Loss**: #ff4444 (Red) - Negative values
- **Background**: #121212 (Dark) - Main background
- **Surface**: #1E1E1E (Dark) - Cards
- **Surface Variant**: #2C2C2C (Darker) - Emphasis areas
- **Text**: #FFFFFF (White) - Primary text
- **Secondary Text**: #B0B0B0 (Gray) - Secondary text

### Sections
1. **Header**: Logo, connection status, settings button
2. **Balance**: Large balance display with IDR/USDT
3. **P&L**: Huge profit/loss number with percentage and trend
4. **Capital Split**: Visual bars showing 70%/30% allocation
5. **Active Trades**: List of top 5 trades with entry/current/profit
6. **Action Panel**: Start/Stop/Refresh buttons (minimum 48dp height)

## Permissions

- `INTERNET` - WebSocket connection
- `ACCESS_NETWORK_STATE` - Network monitoring

## Dependencies

### Core Android
- androidx.core:core-ktx:1.12.0
- androidx.appcompat:appcompat:1.6.1
- androidx.activity:activity-compose:1.8.0

### Jetpack Compose
- androidx.compose.ui:ui:1.6.0
- androidx.compose.material3:material3:1.1.1

### Networking
- com.squareup.okhttp3:okhttp:4.11.0

### Data
- com.google.code.gson:gson:2.10.1

### Coroutines
- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.2
- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.2

### Lifecycle
- androidx.lifecycle:lifecycle-runtime-ktx:2.6.2
- androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2

## Threading

- **Main Thread**: All UI operations
- **Background Thread**: WebSocket connection and periodic updates
- **Coroutines**: Used for async operations (no callbacks)

## Error Handling

The app gracefully handles:
- Connection failures - shows "Offline" indicator
- Disconnections - auto-reconnects every 5 seconds
- Invalid data - logs errors and shows last known state
- Invalid server config - validates on save

## Troubleshooting

### App won't connect
1. Check server is running: `curl http://[server]:8787`
2. Verify firewall allows WebSocket traffic
3. Check server config in Settings (⚙️)
4. Try manual refresh button

### Data not updating
1. Check network connectivity
2. Verify server is sending data
3. Try manual refresh
4. Check app logs: `adb logcat | grep KiCryp`

### App crashes
1. Check logs: `adb logcat`
2. Ensure Android SDK 28+ is installed
3. Try clean rebuild: `./gradlew clean assembleDebug`

## Development

### Adding New Features

1. **New data fields**: Update `BotStatus.kt` data classes
2. **New UI components**: Add to `DashboardScreen.kt` or create new file in `ui/`
3. **New API endpoints**: Update `KiCrypWebSocketClient.kt`

### Debugging

Enable verbose logging:
```kotlin
// In KiCrypWebSocketClient.kt
Log.d(TAG, "Message: $message")
```

View logs:
```bash
adb logcat -s "KiCryp"
```

## Build Variants

### Debug Build
- Includes logging
- Unoptimized
- Debuggable

### Release Build
- Proguard minification enabled
- Optimized
- Not debuggable

## Performance Considerations

- WebSocket updates: Every 5 seconds (configurable in code)
- UI recomposition: Only when state changes
- Memory: ~50MB average
- Battery: Minimal impact (idle socket connection)

## Future Enhancements

- [ ] Push notifications for large P&L changes
- [ ] Historical P&L charts
- [ ] Trade analysis & detailed statistics
- [ ] Price alerts
- [ ] Biometric authentication
- [ ] Multiple account support
- [ ] Widget for quick balance view
- [ ] Offline data caching

## License

This project is part of the KiCryp Trading System.

## Support

For issues or questions:
1. Check logs: `adb logcat | grep KiCryp`
2. Verify server connection
3. Review the configuration in Settings
4. Check that the Mac Engine server is running

---

**Built with ❤️ for KiCryp traders**
