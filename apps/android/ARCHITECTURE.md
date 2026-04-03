# KiBot Android App - Architecture & Implementation Guide

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Android App (Compose)                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              UI Layer (Jetpack Compose)              │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │  • DashboardScreen.kt - Main dashboard UI            │  │
│  │  • SettingsScreen.kt - Configuration UI             │  │
│  │  • Color/Typography constants                        │  │
│  └──────────────────────────────────────────────────────┘  │
│                          ▲                                  │
│                          │ State (StateFlow)               │
│                          │                                  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │           ViewModel Layer (State Management)         │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │  • MainActivity.kt - Activity & ViewModel            │  │
│  │  • DashboardViewModel - State & Lifecycle           │  │
│  └──────────────────────────────────────────────────────┘  │
│                          ▲                                  │
│                          │ Commands                         │
│                          │                                  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │        Data Layer (WebSocket & Storage)              │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │  • KiBotWebSocketClient - WS connection              │  │
│  │  • PreferencesManager - SharedPreferences            │  │
│  │  • BotStatus - Data models                          │  │
│  └──────────────────────────────────────────────────────┘  │
│                          ▲                                  │
│                          │ Network                          │
│                          │                                  │
└─────────────────────────┼──────────────────────────────────┘
                          │
        ┌─────────────────┴─────────────────┐
        │                                   │
    ┌───▼────────┐                   ┌────▼──────┐
    │   WebSocket│                   │   Shared   │
    │  localhost:8787                │ Preferences│
    └────────────┘                   └───────────┘
```

## Data Flow

### 1. Initialization (onCreate)
```
MainActivity.onCreate()
  ↓
viewModel.connect()
  ↓
KiBotWebSocketClient.connect()
  ↓
WebSocket connection established
  ↓
requestStatus() - Initial data request
  ↓
Server responds with BotStatus JSON
  ↓
Parse JSON → emit to _botStatus StateFlow
  ↓
UI recomposes with new data
```

### 2. Periodic Updates (every 5 seconds)
```
WebSocket.onOpen() → start ping job
  ↓
delay(5000)
  ↓
requestStatus()
  ↓
Server sends data
  ↓
onMessage() → parse → _botStatus updated
  ↓
UI automatically recomposes (StateFlow.collectAsState)
```

### 3. User Actions (Start/Stop Trading)
```
User taps button
  ↓
viewModel.sendCommand("start" or "stop")
  ↓
KiBotWebSocketClient.sendCommand(command)
  ↓
WebSocket.send(JSON command)
  ↓
Server processes command
  ↓
Server sends updated status
  ↓
Data flows back through pipeline
```

### 4. Configuration Changes
```
User edits server IP/port in SettingsScreen
  ↓
onSave() callback
  ↓
PreferencesManager.saveServerConfig()
  ↓
viewModel.connect() (reconnects with new config)
  ↓
Old WebSocket disconnected
  ↓
New WebSocket connected to new server
  ↓
Navigate back to dashboard
```

## Component Responsibilities

### MainActivity.kt
- **Responsibility**: Activity lifecycle, app entry point
- **Key Methods**:
  - `onCreate()` - Initialize ViewModel, set Compose content
  - `onDestroy()` - Clean up WebSocket connection
- **Dependencies**: DashboardViewModel, PreferencesManager
- **Lifecycle**: Lives for entire app session

### DashboardViewModel
- **Responsibility**: State management, command routing
- **Key Methods**:
  - `connect()` - Start WebSocket connection
  - `disconnect()` - Stop WebSocket connection
  - `requestStatus()` - Request periodic updates
  - `sendCommand()` - Send bot commands
- **State**:
  - `botStatus` - Current bot data
  - `isConnected` - Connection status
  - `isLoading` - Loading indicator
  - `errorMessage` - Error messages
- **Dependencies**: KiBotWebSocketClient, PreferencesManager
- **Lifecycle**: Survives configuration changes via ViewModel

### KiBotWebSocketClient
- **Responsibility**: WebSocket lifecycle, data parsing, reconnection
- **Key Methods**:
  - `connect()` - Open WebSocket
  - `disconnect()` - Close WebSocket
  - `requestStatus()` - Send status request
  - `sendCommand()` - Send control commands
- **Internal State**:
  - `webSocket` - OkHttp WebSocket reference
  - `pingJob` - Coroutine job for periodic pings
  - `lastPingTime` - Rate limiting
- **Callbacks**:
  - `onStatusUpdate` - New data received
  - `onConnectionChange` - Connected/disconnected
  - `onError` - Error occurred
- **Auto-Reconnect**: Yes, after 5 seconds on disconnect
- **Threading**: Runs on Dispatchers.IO background thread

### DashboardScreen (Compose)
- **Responsibility**: Main UI rendering
- **Composables**:
  - `DashboardScreen()` - Root container
  - `HeaderSection()` - Logo, status, settings
  - `BalanceSection()` - Balance display
  - `PnLSection()` - P&L display with trend
  - `CapitalAllocationSection()` - Capital bars
  - `ActiveTradesSection()` - Trades list
  - `ActionButtonsSection()` - Control buttons
  - `TradeCard()` - Individual trade
  - `MiniSparkline()` - Trend visualization
- **State Consumed**: botStatus, isConnected, isLoading
- **Events Emitted**: onRefresh, onStartTrading, onStopTrading, onSettingsClick

### SettingsScreen (Compose)
- **Responsibility**: Server configuration UI
- **Composables**:
  - `SettingsScreen()` - Settings root
  - Input fields for host and port
  - Save button with validation
- **State Consumed**: currentConfig
- **Events Emitted**: onSave, onBack

### BotStatus.kt (Data Models)
- **Classes**:
  - `BotStatus` - Root data model
  - `Balance` - IDR, USDT, total
  - `PnL` - Daily profit/loss with trend
  - `CapitalSplit` - 70/30 allocation
  - `Trade` - Individual trade info
  - `ServerConfig` - Server connection settings
- **Serialization**: Gson for JSON parsing
- **JSON Contract**: See MOCK_DATA.json for format

### PreferencesManager.kt
- **Responsibility**: Local data persistence
- **Methods**:
  - `getServerConfig()` - Load saved settings
  - `saveServerConfig()` - Save server settings
  - `getLastKnownStatus()` - Load cached status
  - `saveLastKnownStatus()` - Cache status
- **Storage**: Android SharedPreferences
- **Keys**: `server_host`, `server_port`, `last_status`

## Threading Model

```
Main Thread (UI):
├─ Activity lifecycle
├─ Compose recomposition
├─ StateFlow.collectAsState()
└─ User interactions

Background Thread (Dispatchers.IO):
├─ WebSocket connection
├─ JSON parsing (via callback to main)
├─ Network requests
├─ Periodic ping jobs
└─ Reconnection logic
```

## State Management

### StateFlow Pattern
```kotlin
// ViewModel
private val _botStatus = MutableStateFlow<BotStatus?>(null)
val botStatus: StateFlow<BotStatus?> = _botStatus

// Update (from WebSocket thread)
_botStatus.value = newStatus

// Consume (in Compose)
val botStatus by viewModel.botStatus.collectAsState()
```

### Key States
| State | Type | Owner | Purpose |
|-------|------|-------|---------|
| `botStatus` | BotStatus? | ViewModel | Current bot data |
| `isConnected` | Boolean | ViewModel | Connection status |
| `isLoading` | Boolean | ViewModel | Loading indicator |
| `errorMessage` | String | ViewModel | Error display |
| `currentScreen` | String | Compose | Navigation state |

## Error Handling Strategy

### Connection Errors
```
WebSocket.onFailure()
  ↓
onConnectionChange(false) - Update UI to "Offline"
  ↓
schedule reconnect after 5 seconds
  ↓
Retry connection
```

### Data Parse Errors
```
Gson.fromJson() throws exception
  ↓
catch exception in onMessage()
  ↓
Log error: Log.e(TAG, message)
  ↓
Call onError() - Show error banner
  ↓
Keep showing last known data
```

### Server Config Errors
```
User enters invalid config
  ↓
Validate on save:
  - host.isNotBlank()
  - port is valid number
  ↓
If invalid, show message
  ↓
Don't close settings dialog
```

## WebSocket Protocol

### Request
```json
{
  "action": "getStatus"
}
```

### Response
```json
{
  "balance": {"idr": 100000000, "usdt": 5000, "total": 105000000},
  "pnl": {"daily": 2500000, "percentage": 2.5, "trend": [...]},
  "capitalSplit": {"highConviction": 70000000, "aggressive": 30000000},
  "activeTrades": [...],
  "status": "Trading",
  "timestamp": 1699564800000
}
```

### Command
```json
{
  "action": "command",
  "value": "start"  // or "stop"
}
```

## Testing Strategy

### Unit Tests
- Data model serialization/deserialization
- PreferencesManager CRUD operations
- ViewModel state transitions

### Integration Tests
- WebSocket connection/disconnection
- Data flow from WS to UI
- Command execution

### UI Tests
- Button clicks trigger callbacks
- Data displays correctly formatted
- Error messages appear/disappear
- Settings navigation works

### Manual Testing Checklist
- [ ] App launches without crash
- [ ] Settings can be configured
- [ ] Connects to real server
- [ ] Data updates every 5 seconds
- [ ] Manual refresh works
- [ ] Start/Stop buttons work
- [ ] Numbers format correctly
- [ ] Colors are accurate
- [ ] Offline handling works
- [ ] Auto-reconnect works

## Performance Optimization

### Memory
- Use `remember { }` to preserve objects across recompositions
- StateFlow only updates consumers when value changes
- WebSocket runs on background thread

### Network
- 5-second update interval (not true realtime)
- Minimal JSON payload
- Single WebSocket connection (not polling HTTP)

### UI
- Compose only recomposes affected branches
- No unnecessary state updates
- Images lazy-loaded if needed (future)

## Security Considerations

### Network
- Allow cleartext traffic to localhost (development only)
- Production should use WSS (WebSocket Secure)
- Validate server certificate in prod

### Local Storage
- Server credentials stored in SharedPreferences (encrypted in production)
- No sensitive data cached locally
- Consider using EncryptedSharedPreferences for prod

### Permissions
- Only requests necessary permissions (INTERNET, ACCESS_NETWORK_STATE)
- No dangerous permissions needed

## Future Enhancement Points

### Features
- [ ] Historical data charting
- [ ] Trade history with P&L details
- [ ] Price alerts
- [ ] Multiple account support
- [ ] Biometric authentication
- [ ] Widget for home screen

### Technical
- [ ] Migrate to Hilt for DI
- [ ] Add Room database for offline caching
- [ ] Implement proper error recovery
- [ ] Add crash reporting (Firebase)
- [ ] Performance monitoring
- [ ] A/B testing framework

### UI/UX
- [ ] Dark/light theme toggle
- [ ] Customizable dashboard layouts
- [ ] Pull-to-refresh gestures
- [ ] Swipe animations
- [ ] Accessibility improvements

## Debugging Tips

### Enable Logging
```kotlin
// In KiBotWebSocketClient
Log.d(TAG, "Connecting to $wsUrl")
Log.d(TAG, "Message received: $text")
```

### View Logs
```bash
adb logcat -s "KiBot"
adb logcat | grep "KiBotWebSocket"
```

### Inspect Network
```bash
# List open connections
adb shell netstat | grep 8787

# Monitor network traffic
adb shell tcpdump -i any 'tcp port 8787'
```

### Debug Compose
```kotlin
// Add temporary logs in @Composable
LaunchedEffect(botStatus) {
    println("Status updated: $botStatus")
}
```

## Maintenance

### Dependencies
- Update quarterly
- Test after each update
- Monitor security advisories

### Code Quality
- Run lint regularly
- Keep kotlin-version current
- Use Android Studio inspections

### Testing
- Add tests for new features
- Maintain >80% coverage for critical paths
- Test on multiple Android versions (9-14)

---

**This architecture is designed to be simple, maintainable, and performant for a non-technical user audience.**
