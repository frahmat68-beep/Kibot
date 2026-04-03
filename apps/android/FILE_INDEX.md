# KiBot Android Dashboard - Complete File Index

## Directory Structure
```
apps/android/
├── build.gradle.kts                          (Root Gradle config)
├── settings.gradle.kts                       (Gradle settings)
├── README.md                                 (User guide - START HERE)
├── QUICK_START.md                            (5-minute setup)
├── ARCHITECTURE.md                           (Technical design)
├── BUILD_GUIDE.md                            (Build & deploy)
├── IMPLEMENTATION_SUMMARY.md                 (Project overview)
├── MOCK_DATA.json                            (Example API response)
│
└── app/
    ├── build.gradle.kts                      (App dependencies)
    │
    └── src/main/
        ├── AndroidManifest.xml               (App permissions & config)
        │
        ├── kotlin/com/kibot/android/
        │   ├── MainActivity.kt               (Activity entry point + ViewModel)
        │   │
        │   ├── websocket/
        │   │   └── KiBotWebSocketClient.kt  (WebSocket connection logic)
        │   │
        │   ├── data/
        │   │   └── BotStatus.kt             (Data models & server config)
        │   │
        │   ├── ui/
        │   │   ├── DashboardScreen.kt       (Main dashboard UI - Compose)
        │   │   └── SettingsScreen.kt        (Settings UI - Compose)
        │   │
        │   └── util/
        │       └── PreferencesManager.kt    (SharedPreferences wrapper)
        │
        └── res/
            ├── layout/
            │   └── activity_main.xml         (Layout placeholder for Compose)
            │
            └── values/
                ├── strings.xml               (UI text resources)
                ├── colors.xml                (Color definitions)
                └── themes.xml                (Theme configuration)
```

## Core Source Files (Kotlin)

### 1. MainActivity.kt (312 lines)
**Location**: `app/src/main/kotlin/com/kibot/android/MainActivity.kt`

**Purpose**: App entry point, lifecycle management, state management

**Key Classes**:
- `DashboardViewModel` - State management, WebSocket control
- `DashboardViewModelFactory` - ViewModel factory
- `MainActivity` - Activity lifecycle
- `MainApp` - Composable root UI

**Key Methods**:
- `MainActivity.onCreate()` - Initialize ViewModel, set Compose content
- `MainActivity.onDestroy()` - Clean up resources
- `DashboardViewModel.connect()` - Start WebSocket
- `DashboardViewModel.disconnect()` - Stop WebSocket
- `MainApp()` - Navigate between dashboard and settings

**Dependencies**: PreferencesManager, KiBotWebSocketClient, Compose

---

### 2. KiBotWebSocketClient.kt (195 lines)
**Location**: `app/src/main/kotlin/com/kibot/android/websocket/KiBotWebSocketClient.kt`

**Purpose**: WebSocket connection, data parsing, auto-reconnection

**Key Classes**:
- `KiBotWebSocketClient` - WebSocket lifecycle manager
- `WebSocketListener` - Connection event handler

**Key Methods**:
- `connect()` - Open WebSocket connection
- `disconnect()` - Close WebSocket connection
- `requestStatus()` - Send status request (rate-limited to 5 sec)
- `sendCommand(command)` - Send control commands
- `onOpen()` - Connection established, start ping loop
- `onMessage(text)` - Parse JSON response, emit data
- `onFailure()` - Handle connection errors, schedule reconnect

**Features**:
- Auto-reconnect after 5 seconds on disconnect
- Periodic ping every 5 seconds
- JSON parsing with Gson
- Thread-safe with Coroutines

**Dependencies**: OkHttp, Gson, Kotlin Coroutines

---

### 3. BotStatus.kt (60 lines)
**Location**: `app/src/main/kotlin/com/kibot/android/data/BotStatus.kt`

**Purpose**: Data models for bot status and configuration

**Key Data Classes**:
- `BotStatus` - Root data container
  - balance: Balance
  - pnl: PnL
  - capitalSplit: CapitalSplit
  - activeTrades: List<Trade>
  - status: String ("Trading" or "Stopped")
  - timestamp: Long

- `Balance` - Account balance
  - idr: Double
  - usdt: Double
  - total: Double

- `PnL` - Profit/Loss
  - daily: Double
  - percentage: Double
  - trend: List<Double> (24h hourly values)

- `CapitalSplit` - Capital allocation
  - highConviction: Double (70%)
  - aggressive: Double (30%)

- `Trade` - Individual trade
  - pair: String (e.g., "BTC/USDT")
  - entry: Double (entry price)
  - current: Double (current price)
  - profit: Double (profit in IDR)
  - profitPct: Double (profit percentage)

- `ServerConfig` - Connection settings
  - host: String (default: "localhost")
  - port: Int (default: 8787)

**Dependencies**: Gson (for JSON serialization)

---

### 4. DashboardScreen.kt (560 lines)
**Location**: `app/src/main/kotlin/com/kibot/android/ui/DashboardScreen.kt`

**Purpose**: Main dashboard UI (Jetpack Compose)

**Key Composables**:
- `DashboardScreen()` - Root container
- `HeaderSection()` - Logo, status indicator, settings button
- `StatusIndicator()` - Green/red connected status
- `BalanceSection()` - Display balance (IDR, USDT, total)
- `PnLSection()` - Display daily P&L with sparkline
- `MiniSparkline()` - Trend visualization
- `CapitalAllocationSection()` - Capital bars (70/30)
- `ActiveTradesSection()` - List of top 5 trades
- `TradeCard()` - Individual trade card
- `ActionButtonsSection()` - Control buttons
- `LoadingScreen()` - Loading/connecting state

**Color Scheme**:
```kotlin
ProfitGreen = #00C851
LossRed = #ff4444
PrimaryBlue = #0066CC
AccentOrange = #FF9900
DarkBackground = #121212
DarkSurface = #1E1E1E
DarkSurfaceVariant = #2C2C2C
LightText = #FFFFFF
SecondaryText = #B0B0B0
```

**Layout**:
1. Header (12dp padding, dark variant background)
2. Balance section (card, 12dp padding)
3. P&L section (card, large text 48sp)
4. Capital allocation (card with bars)
5. Active trades (card with list of 5)
6. Action buttons (52dp height)

**Dependencies**: Compose Material3, Material Icons

---

### 5. SettingsScreen.kt (140 lines)
**Location**: `app/src/main/kotlin/com/kibot/android/ui/SettingsScreen.kt`

**Purpose**: Server configuration UI

**Key Composables**:
- `SettingsScreen()` - Settings container
- Text fields for host and port
- Save button with validation
- Error/success message display

**Validation**:
- Host: must not be blank
- Port: must be valid number > 0

**Features**:
- Input validation before save
- Error message display
- Save confirmation message
- Back button to return to dashboard

**Dependencies**: Compose Material3, TextField

---

### 6. PreferencesManager.kt (45 lines)
**Location**: `app/src/main/kotlin/com/kibot/android/util/PreferencesManager.kt`

**Purpose**: Android SharedPreferences wrapper for local storage

**Key Methods**:
- `getServerConfig()` - Load saved server settings
- `saveServerConfig(config)` - Save server settings
- `getLastKnownStatus()` - Load cached status
- `saveLastKnownStatus(status)` - Cache status

**Storage Keys**:
- `server_host` - Server hostname/IP
- `server_port` - Server port number
- `last_status` - Last known bot status (JSON)

**Defaults**:
- Host: "localhost"
- Port: 8787

**Dependencies**: Android Context, SharedPreferences

---

## Configuration Files

### 7. build.gradle.kts (Root)
**Location**: `apps/android/build.gradle.kts`

**Purpose**: Root project build configuration

**Content**:
- Plugin versions
- Clean task definition

---

### 8. app/build.gradle.kts
**Location**: `apps/android/app/build.gradle.kts`

**Purpose**: App-level dependencies and build configuration

**Key Configurations**:
```kotlin
compileSdk = 34
targetSdk = 34
minSdk = 28
versionCode = 1
versionName = "1.0.0"
```

**Dependencies**:
- Android Core (core-ktx, appcompat, activity-compose)
- Jetpack Compose (ui, material3, icons)
- Networking (OkHttp 4.11.0)
- Data (Gson 2.10.1)
- Coroutines (1.7.2)
- ViewModel & Lifecycle

**Build Features**:
- Compose enabled
- Kotlin compiler extension 1.5.3

---

### 9. settings.gradle.kts
**Location**: `apps/android/settings.gradle.kts`

**Purpose**: Gradle build settings and repository configuration

**Content**:
- Plugin management
- Repository configuration (Google, Maven Central)
- Root project name and module includes

---

### 10. AndroidManifest.xml
**Location**: `apps/android/app/src/main/AndroidManifest.xml`

**Purpose**: App configuration, permissions, activities

**Permissions**:
- INTERNET (required for WebSocket)
- ACCESS_NETWORK_STATE (for network monitoring)

**Configuration**:
- Application name: "KiBot Trading Dashboard"
- Theme: Theme.KiBot
- Cleartext traffic enabled (localhost development)
- MainActivity as launcher

---

## Resource Files

### 11. activity_main.xml
**Location**: `app/src/main/res/layout/activity_main.xml`

**Purpose**: Layout placeholder for Compose integration

**Content**: Empty LinearLayout with Compose container FrameLayout

---

### 12. strings.xml
**Location**: `app/src/main/res/values/strings.xml`

**Content** (15 strings):
- app_name: "KiBot Trading Dashboard"
- UI button labels
- Section headers
- Status messages
- Placeholder text

---

### 13. colors.xml
**Location**: `app/src/main/res/values/colors.xml`

**Content** (9 colors):
- primary_blue: #0066CC
- accent_orange: #FF9900
- profit_green: #00C851
- loss_red: #ff4444
- dark_background: #121212
- dark_surface: #1E1E1E
- dark_surface_variant: #2C2C2C
- light_text: #FFFFFF
- secondary_text: #B0B0B0

---

### 14. themes.xml
**Location**: `app/src/main/res/values/themes.xml`

**Content**:
- Theme.KiBot (Material.NoActionBar)
- Dark background
- Light text color
- Status/navigation bar colors

---

## Documentation Files

### 15. README.md (350+ lines)
**Location**: `apps/android/README.md`

**Sections**:
- Features overview
- Requirements
- Building instructions
- Running on emulator/device
- Configuration guide
- Project structure
- API specification (WebSocket)
- UI/UX design guide
- Permissions
- Dependencies list
- Troubleshooting
- Development tips
- Performance considerations
- Future enhancements

**Audience**: Users, developers, deployment engineers

---

### 16. QUICK_START.md (200+ lines)
**Location**: `apps/android/QUICK_START.md`

**Sections**:
- 5-minute setup
- Prerequisites
- Build/install steps
- Configuration
- Testing (with/without real server)
- Common issues
- File structure reference
- Key classes table
- Testing checklist
- Device requirements
- Pro tips

**Audience**: First-time users, quick setup

---

### 17. ARCHITECTURE.md (400+ lines)
**Location**: `apps/android/ARCHITECTURE.md`

**Sections**:
- System architecture diagram
- Data flow diagrams
- Component responsibilities (detailed)
- Threading model
- State management patterns
- Error handling strategy
- WebSocket protocol
- Testing strategy
- Performance optimization
- Security considerations
- Future enhancements
- Debugging tips
- Maintenance guide

**Audience**: Developers, architects, maintainers

---

### 18. BUILD_GUIDE.md (350+ lines)
**Location**: `apps/android/BUILD_GUIDE.md`

**Sections**:
- Prerequisites installation
- Build variants (debug/release)
- Debug build & testing
- Emulator setup
- Physical device testing
- Mock server setup (Python)
- Release signing
- Google Play deployment
- CI/CD setup (GitHub Actions)
- Troubleshooting (build & runtime errors)
- Performance profiling
- Testing checklist
- Version management
- Distribution options

**Audience**: Developers, DevOps, QA

---

### 19. IMPLEMENTATION_SUMMARY.md (350+ lines)
**Location**: `apps/android/IMPLEMENTATION_SUMMARY.md`

**Sections**:
- Project summary
- What's included (file checklist)
- Key features implemented
- Technology stack table
- Project structure
- Quick start
- API specification
- Production build
- Testing
- Documentation files guide
- Design decisions
- Performance profile
- Security & privacy
- Known limitations
- Testing coverage
- Deployment readiness
- Support & maintenance
- Next steps
- Complete files checklist

**Audience**: Project stakeholders, implementers

---

## Example/Test Files

### 20. MOCK_DATA.json
**Location**: `apps/android/MOCK_DATA.json`

**Purpose**: Example WebSocket response for testing

**Content**:
```json
{
  "balance": {"idr": 150000000, "usdt": 7500, "total": 157500000},
  "pnl": {"daily": 3750000, "percentage": 2.58, "trend": [...]},
  "capitalSplit": {"highConviction": 105000000, "aggressive": 45000000},
  "activeTrades": [5 example trades],
  "status": "Trading",
  "timestamp": 1699564800000
}
```

**Usage**: Test WebSocket server, validate app UI with real data

---

## File Statistics

| Category | Count | Lines of Code |
|----------|-------|----------------|
| **Kotlin Source** | 6 | ~1,400 |
| **XML Config** | 4 | ~150 |
| **Gradle Config** | 3 | ~600 |
| **Documentation** | 5 | ~2,000 |
| **Examples** | 1 | ~50 |
| **TOTAL** | **19** | **~4,200** |

## How to Use This Index

1. **First time?** Start with `QUICK_START.md`
2. **Want to understand?** Read `ARCHITECTURE.md`
3. **Need to build?** Follow `BUILD_GUIDE.md`
4. **Need details?** Check this file and specific source files
5. **User questions?** Point to `README.md`

## File Dependencies

```
MainActivity.kt
├── KiBotWebSocketClient.kt
├── PreferencesManager.kt
├── DashboardScreen.kt
├── SettingsScreen.kt
└── BotStatus.kt

KiBotWebSocketClient.kt
├── BotStatus.kt
├── Gson (external)
└── OkHttp (external)

DashboardScreen.kt
├── BotStatus.kt
├── DarkBackground (color constant)
└── Compose Material3 (external)

SettingsScreen.kt
├── ServerConfig (from BotStatus.kt)
└── Compose Material3 (external)

PreferencesManager.kt
├── ServerConfig (from BotStatus.kt)
└── Android SharedPreferences (framework)
```

---

**Last Updated**: [BUILD_COMPLETE]

**Total Files**: 19 (6 Kotlin + 4 XML + 3 Gradle + 5 Docs + 1 Example)

**Total Code**: ~4,200 lines (including documentation)

**Status**: ✅ Production Ready
