# KiBot Trinity - User Brief: How to Ask Questions

## Quick Reference - 5 Essential Commands

Use these 5 commands for 95% of your needs:

### 1. `/status` - "Apa kondisi bot sekarang?"
Get quick overview of everything in <30 seconds
- All 3 bots online/offline?
- Current positions and top P&L
- Balance today

**When to use:** Every morning, during trading hours, anytime you want quick snapshot

---

### 2. `/balance` - "Berapa balance gw dan posisi apa aja yang dipegang?"
See detailed balance breakdown and all holdings
- Total balance
- Free cash available
- P&L today
- All positions held

**When to use:** Before entering new trades, end of day review

---

### 3. `/positions` - "Detail posisi gw yang open sekarang apa aja?"
Get detailed info on each open position
- Entry price vs current price
- P&L percentage and Rupiah
- Target price and stop-loss
- How long held (minutes)

**When to use:** Monitor open positions, check entry quality, verify SL

---

### 4. `/trades` - "Berapa profit hari ini? Trade-trade apa aja?"
See today's completed trades and win rate
- Win rate %
- Best and worst trades
- All trades executed today
- Duration each trade held

**When to use:** Track trading activity, verify profit target

---

### 5. `/health` - "Bot lagi ok gak? Network bagus gak?"
Check system health and network status
- All 3 bots online?
- Latency to Binance, Indodax, Manager
- CPU/Memory usage
- Connection status

**When to use:** If something feels wrong, before important trades

---

## Common Scenarios

### Morning: Start of Day
```
/status          <- Quick check all systems go
/health          <- Verify network is healthy
/balance         <- See yesterday's results
```

### During Trading
```
/positions       <- Monitor open positions
/trades          <- Track today's activity
/status          <- Quick pulse check
```

### Before New Trade
```
/balance         <- Check free cash available
/positions       <- How many coins already held?
/health          <- Network latency OK?
```

### After Trade Executed
```
/positions       <- Verify position entry
/trades          <- Confirm trade shows up
```

### End of Day
```
/performance     <- Review daily metrics
/trades          <- See all trades executed
/balance         <- Calculate profit/loss
```

### Troubleshooting
```
/health          <- Check if bots are alive
/status          <- Quick system overview
/alerts          <- Any alerts or warnings?
```

---

## Query Patterns

### Pattern 1: "Berapa saldo gw?"
**Use:** `/balance`

Response akan show:
- Total balance Rp
- Free cash (bisa entry berapa?)
- P&L today %
- Semua coin yang dipegang

### Pattern 2: "Posisi apa yang open?"
**Use:** `/positions`

Response akan show:
- Semua coin yang hold
- Entry price
- Current price
- P&L untuk setiap posisi
- Target dan SL

### Pattern 3: "Profit berapa hari ini?"
**Use:** `/trades`

Response akan show:
- Win rate % (berapa menang?)
- Total PnL hari ini
- Semua trade list
- Yang untung vs rugi

### Pattern 4: "Bot lagi jalan gak?"
**Use:** `/status` (cepat) atau `/health` (detail)

Response akan show:
- 3 bot online/offline
- Network latency
- System resources (CPU, memory)

### Pattern 5: "Performance gw bagus gak?"
**Use:** `/performance`

Response akan show:
- Daily stats (trades, win rate)
- Risk profile check
- Best dan worst trades
- P&L summary

---

## Understanding Responses

### Color System (Emoji)
- 🟢 = Good / Positive / Online
- 🔴 = Bad / Negative / Offline
- 🟡 = Warning / Degraded / Caution
- ✅ = Safe / Confirmed / OK

### Amount Format
All amounts shown in **Indonesian Rupiah (Rp)**

Examples:
- `Rp 50.5M` = 50.5 Million Rupiah
- `Rp 240K` = 240 Thousand Rupiah
- `Rp 1,200` = 1,200 Rupiah

### Percentage Format
Shows profit/loss with color:
- `🟢 +2.5%` = Profit (green)
- `🔴 -0.5%` = Loss (red)

### Duration Format
Time held in trades:
- `32min` = 32 minutes held
- `1h 45m` = 1 hour 45 minutes held

---

## Pro Tips

### Tip 1: Use `/status` First
Every time you open Telegram, start with `/status`. It's like a dashboard - shows everything in one view.

### Tip 2: Check `/health` Before New Entries
High latency can cause slippage. Verify network is healthy before entering trades.

### Tip 3: Monitor `/positions` While Holding
Check position P&L every 15-30 minutes while trade is running. Verify SL is active.

### Tip 4: Review `/trades` End of Day
Track what worked and what didn't. Helps you understand bot's trading style.

### Tip 5: Use `/performance` for Analytics
Weekly/daily review of win rate, best/worst trades, and P&L trends.

---

## What Each Bot Does (Context)

**KiBot Manager (Python) - The Brain**
- Makes entry decisions
- Manages capital allocation (70/30 split)
- Sends VETO commands if conditions bad
- Monitors overall risk

**KiDax (Kotlin) - The Executioner**
- Places buy/sell orders on Indodax
- Manages position sizing (max 25% per coin)
- Handles stop-loss and trailing stop
- Executes exits

**Kinance (Kotlin) - The Informant**
- Watches Binance global market
- Detects pump signals (volume anomalies)
- Tracks lead-lag between Binance and Indodax
- Sends signals to Manager and KiDax

---

## Command Response Times

| Command | Response Time | Data Freshness |
|---------|---------------|----------------|
| `/status` | <1 second | Real-time |
| `/balance` | <1 second | 30s update |
| `/positions` | <1 second | Real-time |
| `/trades` | <1 second | Immediate on execute |
| `/performance` | <1 second | 30s update |
| `/health` | <1 second | Real-time |
| `/alerts` | <1 second | Real-time |

---

## Error Messages & What They Mean

**"Command not found"**
- Bot might be offline
- Try `/help` to verify
- Check `/health`

**"No active positions"**
- All trades have closed
- No open holdings right now
- Check `/trades` to see what closed

**"Bot offline"**
- One of the 3 bots crashed
- Check `/health` for which one
- System will auto-restart (self-healing enabled)

**"High latency warning"**
- Network is slow (>500ms)
- Don't enter new trades
- Existing positions still protected
- Wait for latency to improve

---

## Integration with Notifications

Alongside these commands, you also receive **automatic notifications** for:
- 📥 Buy orders executed
- 💰 Profits taken
- 📤 Losses on exits
- 🚨 System errors
- ⚠️ High latency warnings
- 🔔 Position milestones (+1%, +5%, etc)

Commands complement notifications - notifications alert you, commands let you dig deeper.

---

## Practice: Try Now

Test the system right now:

```
Send: /status
Get: System overview (should show 🟢 ONLINE for all 3 bots)

Send: /health  
Get: Network latency metrics

Send: /help
Get: All available commands

Send: /balance
Get: Your balance and holdings
```

---

## Key Principle

**Ask specific questions, get specific answers**

Rather than "gimana bot gw?", be specific:
- ❌ "Gimana bot gw?" → Too vague
- ✅ `/status` → Get system overview
- ❌ "Berapa profit?" → Need to know what timeframe
- ✅ `/trades` → See today's trades and P&L

---

## One-Minute Briefing

Think of the Telegram command system as **KiBot's Chatbot**:

- 5 main commands cover 95% of use cases
- All data is **real-time** from the 3 running bots
- Responses are **formatted for quick scanning** (emojis, colors)
- Data is **fresh** - updated every 30 seconds
- No action commands yet - these are **read-only queries**

Use `/status` to start. That's your main dashboard.

---

**Status:** ✅ TELEGRAM COMMAND SYSTEM READY
**All 8 Commands:** Implemented and tested
**Data Sources:** Live from bot state files
**User Guide:** See TELEGRAM_COMMANDS.md for full details

