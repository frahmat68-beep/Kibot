# KiCryp Android App - Quick Start Guide

## 5-Minute Setup

### 1. Prerequisites
- Android Studio installed
- Android SDK 34 installed
- KiCryp Mac Engine running (on same network)

### 2. Build the App
```bash
cd apps/android
./gradlew assembleDebug
```

### 3. Install on Device
```bash
./gradlew installDebug
```

### 4. Configure Server
1. Open the app
2. Tap **⚙️ Settings** (top-right)
3. Enter your Mac Engine IP: `192.168.x.x` or `localhost` for local testing
4. Enter port: `8787` (default)
5. Tap **Save Settings**

### 5. Verify Connection
- Look for green **Connected** indicator at top
- Data should refresh every 5 seconds
- Tap **Manual Refresh** if needed

## Testing Without Real Server

### Option A: Mock Data (Recommended)

1. **Generate mock WebSocket response**:
   ```bash
   # See example response in apps/android/MOCK_DATA.json
   ```

2. **Modify MainActivity.kt** (temporarily):
   ```kotlin
   // Comment out: viewModel.connect()
   // Add mock data instead for testing UI
   ```

### Option B: Local Test Server

Create a simple WebSocket server for testing:

```python
# test_server.py
from websockets.server import serve
import asyncio
import json

async def handler(websocket, path):
    while True:
        await asyncio.sleep(5)
        data = {
            "balance": {"idr": 100000000, "usdt": 5000, "total": 105000000},
            "pnl": {"daily": 2500000, "percentage": 2.5, "trend": [1, 2, 3, 4, 5]},
            "capitalSplit": {"highConviction": 70000000, "aggressive": 30000000},
            "activeTrades": [
                {"pair": "BTC/USDT", "entry": 42000, "current": 43000, "profit": 2500000, "profitPct": 2.38}
            ],
            "status": "Trading",
            "timestamp": int(asyncio.get_event_loop().time() * 1000)
        }
        await websocket.send(json.dumps(data))

async def main():
    async with serve(handler, "0.0.0.0", 8787):
        print("Test server running on ws://0.0.0.0:8787/kicryp/status")
        await asyncio.Future()

asyncio.run(main())
```

Run it:
```bash
pip install websockets
python test_server.py
```

## Common Issues

### "Connection Lost" Error
- ✅ Check Mac Engine is running
- ✅ Verify IP/port in Settings
- ✅ Check network connectivity: `ping 192.168.x.x`
- ✅ Check firewall: `sudo lsof -i :8787`

### No Data Appearing
- ✅ Tap **Manual Refresh** button
- ✅ Check server logs
- ✅ Verify data format matches BotStatus.kt

### App Crashes on Launch
- ✅ Uninstall: `./gradlew uninstallDebug`
- ✅ Clean rebuild: `./gradlew clean assembleDebug`
- ✅ Check Android version: min SDK 28 (Android 9)

## File Structure Quick Reference

```
apps/android/
├── README.md                 # Full documentation
├── QUICK_START.md           # This file
├── MOCK_DATA.json           # Example server response
├── app/src/main/
│   ├── kotlin/com/kicryp/android/
│   │   ├── MainActivity.kt   # Entry point & ViewModel
│   │   ├── websocket/        # Connection logic
│   │   ├── data/             # Data models
│   │   ├── ui/               # UI screens
│   │   └── util/             # Utilities
│   └── AndroidManifest.xml   # App configuration
└── build.gradle.kts          # Dependencies
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `MainActivity` | App entry point, manages lifecycle |
| `DashboardViewModel` | State management, WebSocket control |
| `KiCrypWebSocketClient` | WebSocket connection & data parsing |
| `BotStatus` | Data models (balance, P&L, trades, etc.) |
| `DashboardScreen` | Main UI (Compose) |
| `SettingsScreen` | Configuration UI (Compose) |
| `PreferencesManager` | Local storage (SharedPreferences) |

## Testing Checklist

- [ ] App launches without crashing
- [ ] Settings screen opens
- [ ] Can configure server IP/port
- [ ] Displays "Connecting..." when offline
- [ ] Shows "Connected" when server is up
- [ ] Data updates every 5 seconds
- [ ] Manual refresh works
- [ ] Start/Stop buttons send commands
- [ ] Back to dashboard from settings
- [ ] Numbers are formatted correctly (IDR, USDT, %)
- [ ] Colors match design (green for profit, red for loss)

## Device Requirements

| Metric | Requirement |
|--------|-------------|
| Min Android | 9 (API 28) |
| Target Android | 14 (API 34) |
| Min RAM | 512MB (recommended 2GB) |
| Min Storage | 50MB |
| Network | WiFi or LTE with WebSocket support |

## Pro Tips

1. **Use WiFi**: Local network connection is faster
2. **Enable Developer Logs**:
   ```bash
   adb logcat -s "KiCryp"
   ```
3. **Test Offline First**: Verify UI renders without connection
4. **Monitor Network**: Use Chrome DevTools to inspect WebSocket traffic
   ```bash
   adb forward tcp:9222 localabstract:chrome_devtools_remote
   ```

## Next Steps

1. ✅ Build the app
2. ✅ Test with mock data
3. ✅ Connect to real server
4. ✅ Configure for production IP
5. 🚀 Deploy to devices

For full documentation, see **README.md**

---

**Need help?** Check logs with `adb logcat | grep -i kicryp`
