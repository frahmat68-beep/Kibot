# KiBot Trinity - Telegram Command Guide

## Overview
Real-time command interface to query KiBot Trinity system status, trades, positions, and performance metrics via Telegram.

## Available Commands

### 1️⃣ `/status` - Overall System Status
Show quick overview of all 3 bots and current positions.

**Shows:**
- Bot online status (KiBot Manager, KiDax, Kinance)
- Balance snapshot (Total, P&L Today, Total Return)
- Active positions count and top 3 positions

**Example Response:**
```
🟢 SYSTEM STATUS
─────────────────

🤖 Bot Status:
  🟢 KiBot Manager: ONLINE
  🟢 KiDax: ONLINE
  🟢 Kinance: ONLINE

💰 Balance Snapshot:
  Total: Rp 50.5M
  Today: 🟢 +2.5%
  Return: 🟢 +12.3%

📊 Active Positions: 3
  🟢 TRX: +1.2% (Rp 240K)
  🟢 XLM: +0.8% (Rp 150K)
  🔴 DOGE: -0.3% (Rp -45K)
```

---

### 2️⃣ `/balance` - Balance Breakdown
Detailed balance information with capital allocation and holdings.

**Shows:**
- Total balance
- P&L Today percentage
- Total Return percentage
- Capital allocation breakdown (Free Cash vs Invested)
- All holdings with entry price and P&L

**Example Response:**
```
💰 BALANCE DETAILS
──────────────────

Current Balance:
  Total: Rp 50.5M
  P&L Today: 🟢 +2.5%
  Total Return: 🟢 +12.3%

Capital Allocation:
  💵 Free Cash: Rp 20.2M (40%)
  📈 Invested: Rp 30.3M (60%)

Current Holdings:
  🟢 TRX: 5000.00 @ Rp 1,200
     P&L: +1.2% (Rp 240K)
  🟢 XLM: 3000.00 @ Rp 750
     P&L: +0.8% (Rp 150K)
  🔴 DOGE: 50000.00 @ Rp 90
     P&L: -0.3% (Rp -45K)
```

---

### 3️⃣ `/positions` - Active Positions
Detailed view of all open positions with entry, current, target, and stop-loss.

**Shows:**
- Each position number and pair
- Entry price and current price
- Quantity held
- P&L percentage and rupiah amount
- Target price and stop-loss level
- Time held in minutes

**Example Response:**
```
📊 ACTIVE POSITIONS (3)
──────────────────────

#1 TRX 🟢
  Entry: Rp 1,200 | Current: Rp 1,214
  Qty: 5000.0000
  P&L: +1.2% (Rp 240K)
  Target: Rp 1,350 | SL: Rp 1,176
  ⏱️ Held: 45 mins

#2 XLM 🟢
  Entry: Rp 750 | Current: Rp 756
  Qty: 3000.0000
  P&L: +0.8% (Rp 150K)
  Target: Rp 850 | SL: Rp 728
  ⏱️ Held: 28 mins

#3 DOGE 🔴
  Entry: Rp 90 | Current: Rp 89.7
  Qty: 50000.0000
  P&L: -0.3% (Rp -45K)
  Target: Rp 105 | SL: Rp 87
  ⏱️ Held: 12 mins
```

---

### 4️⃣ `/trades` - Recent Trades
Show recent completed trades with win/loss statistics.

**Shows:**
- Win rate and win/loss count
- P&L today
- Last 10 trades with:
  - Pair name
  - P&L percentage
  - P&L amount
  - Duration in minutes

**Example Response:**
```
💹 RECENT TRADES (10)
─────────────────────
Win Rate: 75.0% (6W-2L)
Today P&L: Rp 1.2M

🟢 TRX: +2.5% (Rp 250K) | 32min
🟢 XLM: +1.8% (Rp 180K) | 28min
🟢 ADA: +1.2% (Rp 150K) | 45min
🔴 ETH: -0.5% (Rp -80K) | 52min
🟢 SOL: +3.2% (Rp 320K) | 18min
🟢 BNB: +0.8% (Rp 100K) | 22min
🟢 LINK: +2.1% (Rp 210K) | 35min
🔴 MATIC: -0.2% (Rp -25K) | 41min
🟢 ATOM: +1.5% (Rp 150K) | 29min
🟢 DOT: +0.9% (Rp 90K) | 38min
```

---

### 5️⃣ `/performance` - Performance Metrics
Overview of trading performance and risk profile.

**Shows:**
- Today's trading stats (trades, win rate, best/worst)
- P&L breakdown
- Risk profile confirmation
  - Max position: 25%
  - Stop loss: 2-3%
  - Trailing stop: Active
  - Timeout: 12h max

**Example Response:**
```
📈 PERFORMANCE METRICS
──────────────────────

Today's Trading:
  Trades: 10
  Win Rate: 75.0%
  Best: TRX +2.5%
  Worst: MATIC -0.2%

P&L:
  Today: Rp 1.2M
  Return: 🟢 +12.3%

Risk Profile:
  Max Position: 25% per coin ✅
  Stop Loss: 2-3% ✅
  Trailing Stop: Active ✅
  Timeout: 12h max ✅
```

---

### 6️⃣ `/health` - System Health
Real-time system health metrics and network latency.

**Shows:**
- Bot online status
- Network latency to:
  - Binance (informant radar)
  - Indodax (execution engine)
  - Manager (local control)
- System resources:
  - Memory usage
  - CPU usage
  - Active connections

**Example Response:**
```
💓 SYSTEM HEALTH
────────────────

Bot Status:
  🟢 KiBot Manager: ONLINE
  🟢 KiDax: ONLINE
  🟢 Kinance: ONLINE

Network Latency:
  🌐 Binance: 45ms ✅
  🌐 Indodax: 137ms ✅
  🌐 Manager: 12ms ✅

System Resources:
  💾 Memory: 48% (1.2GB / 2.5GB)
  🔧 CPU: 8%
  📊 Connections: 3/10 active
```

---

### 7️⃣ `/alerts` - Active Alerts
Current trading conditions and active monitoring alerts.

**Shows:**
- Entry gates status
- Position management settings
- Active monitoring systems
- Recent alert history

**Example Response:**
```
🔔 ACTIVE ALERTS
────────────────

Trading Conditions:
  ✅ Entry gates: OPEN
  ✅ Max position: 25% enforced
  ✅ Veto system: ACTIVE

Monitoring:
  📊 Pump detector: SCANNING
  📈 Volume anomaly: ACTIVE
  🔗 Lead-lag correlation: TRACKING

Recent Alerts:
  🔔 [12:45] High latency warning (Indodax >500ms)
  🔔 [11:20] Pump detected on ADA (+15% vol)
```

---

### 8️⃣ `/help` - Command Help
Show all available commands with descriptions.

**Example Response:**
```
❓ COMMAND HELP
───────────────

/status
  Overall system status

/balance
  Balance breakdown & allocation

/positions
  Active positions details

/trades
  Recent trades (today)

/performance
  Performance metrics

/health
  System health & latency

/alerts
  Active alerts & conditions

/help
  Show this help

Quick Tips:
• Commands are case-insensitive
• All amounts in Indonesian Rupiah
• Status updates every 30 seconds
• Notifications sent for major events
```

---

## How to Use

### Getting Started
1. Open Telegram and find the KiBot Trinity bot
2. Type `/help` to see all commands
3. Type any command to get information

### Best Practices

**Quick Status Check:**
```
/status          (30 seconds for overview)
```

**Before Entering New Position:**
```
/balance         (check free cash available)
/health          (verify network is healthy)
```

**After Executing Trade:**
```
/positions       (verify entry price and SL)
/trades          (check if trade shows up)
```

**End of Day Review:**
```
/performance     (review daily metrics)
/trades          (check all trades from today)
```

### Response Format

All commands return formatted responses with:
- **Emojis** for quick visual scanning
- **HTML formatting** for bold/italic text
- **Separators** for readability
- **Rupiah (Rp)** for all amounts
- **Percentages** with color indicators (🟢 green, 🔴 red)

### Timing

- **Real-time**: Status, positions, health
- **Updated**: Every 30 seconds for balance and performance
- **Logged**: All trades recorded immediately on execution
- **Alert**: Notifications sent on events (trade, error, high latency)

---

## System Design

### Architecture
```
User (Telegram)
    ↓
Telegram API
    ↓
kibot_telegram_listener.py (Webhook)
    ↓
kibot_command_handler.py (Command Router)
    ↓
State Files:
  - state/balance.json
  - state/positions.json
  - state/trades.json
  - state/daily_summary.json
    ↓
Telegram API
    ↓
User (Response)
```

### Data Flow

1. **User sends command**: `/balance`
2. **Listener receives**: Webhook POST from Telegram
3. **Handler processes**: Route to `/balance` handler
4. **State loaded**: Read from `state/balance.json`
5. **Response formatted**: HTML with emojis and separators
6. **Message sent**: Back to user via Telegram API

### State Files

All data comes from JSON files in `state/` directory:

```
state/
├── balance.json          # Current balance, P&L, returns
├── positions.json        # Active positions with entry/target/SL
├── trades.json           # Completed trades history
├── daily_summary.json    # Daily stats (trades, win rate, etc)
└── runtime_notes.json    # Runtime events and alerts
```

These files are updated by KiBot Manager and Kotlin engines in real-time.

---

## Command Cheat Sheet

```
Quick Status:      /status
Check Balance:     /balance
Open Positions:    /positions
Recent Trades:     /trades
Daily Performance: /performance
System Health:     /health
Active Alerts:     /alerts
Help & Guide:      /help
```

---

## Troubleshooting

### Command Not Responding
**Check:** `/health` to verify system is online

**Verify:**
- All 3 bots show 🟢 ONLINE
- Latency to all exchanges is < 500ms
- Memory usage is < 80%

### Stale Data
**Check:** Is system still trading?
- Run `/status` - shows active positions
- Run `/trades` - shows recent trades

**If no activity:** System might be in cooldown mode

### Missing Positions
**Check:** Are positions still held?
- Run `/balance` - shows all holdings
- Run `/positions` - shows details

**If empty:** All positions were closed (possibly exited)

---

## FAQ

**Q: How often is data updated?**
A: Real-time for positions/status, every 30s for balance/performance, immediate for new trades.

**Q: Can I control the bot via these commands?**
A: No, these are read-only. Control is via the web dashboard or direct API (coming soon).

**Q: What if I get an error response?**
A: Usually means system is offline. Run `/health` to check bot status.

**Q: Are all amounts in Rupiah?**
A: Yes, all displayed amounts are in IDR (Indonesian Rupiah).

**Q: Can multiple people use these commands?**
A: Currently configured for single user (your Telegram ID). Can be extended to group chats.

---

## Next Steps

Planned enhancements:
- [ ] Action commands: `/buy PAIR`, `/sell PAIR`, `/close`
- [ ] Advanced queries: `/pair PAIR`, `/logs N`, `/deploy STATUS`
- [ ] Analytics: `/week`, `/month`, `/stats`
- [ ] Alerts setup: `/set_alert PARAM VALUE`
- [ ] Group chat support: Multi-user access

---

**Version:** 1.0
**Last Updated:** 2026-04-05
**Status:** ✅ PRODUCTION READY
