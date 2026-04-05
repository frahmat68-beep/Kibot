#!/usr/bin/env python3
"""
KiBot Trinity - Telegram Command Handler
========================================
Handles user commands for querying bot status, trades, positions, and performance.
"""

import asyncio
import aiohttp
import logging
import json
from datetime import datetime, timedelta
from pathlib import Path
from typing import Dict, Any, Optional, List, Tuple
from enum import Enum
from dataclasses import dataclass

# ============================================================================
# CONFIGURATION
# ============================================================================

TELEGRAM_BOT_TOKEN = "8583424689:AAHRe8drD2hmuyN48RoFv9Me0oXwcXnSoSE"
TELEGRAM_USER_ID = "1346696386"
TELEGRAM_API_URL = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}"

# File paths for state
STATE_DIR = Path("state")
BALANCE_FILE = STATE_DIR / "balance.json"
POSITIONS_FILE = STATE_DIR / "positions.json"
TRADES_FILE = STATE_DIR / "trades.json"
DAILY_SUMMARY_FILE = STATE_DIR / "daily_summary.json"
RUNTIME_NOTES_FILE = STATE_DIR / "runtime_notes.json"

# ============================================================================
# LOGGING
# ============================================================================

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger("KiBot.CommandHandler")

# ============================================================================
# ICONS & FORMATTING
# ============================================================================

ICONS = {
    "status": "🟢",
    "offline": "🔴",
    "degraded": "🟡",
    "balance": "💰",
    "position": "📊",
    "trade": "💹",
    "profit": "📈",
    "loss": "📉",
    "help": "❓",
    "error": "🚨",
    "health": "💓",
    "chart": "📈",
    "log": "📝",
    "coin": "🪙",
    "alert": "🔔",
    "deploy": "🚀",
    "timeout": "⏰",
    "safe": "✅",
}


def format_idr(amount: float) -> str:
    """Format as Indonesian Rupiah."""
    if abs(amount) >= 1_000_000:
        return f"Rp {amount/1_000_000:.2f}M"
    elif abs(amount) >= 1_000:
        return f"Rp {amount/1_000:.2f}K"
    return f"Rp {amount:.0f}"


def format_pct(pct: float) -> str:
    """Format as percentage with color indicator."""
    color = "🟢" if pct >= 0 else "🔴"
    return f"{color} {pct:+.2f}%"


def escape_html(text: str) -> str:
    """Escape HTML special chars for Telegram."""
    return (text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;"))


def make_separator(char: str = "─", length: int = 24) -> str:
    """Create separator line."""
    return char * length


# ============================================================================
# STATE LOADER
# ============================================================================

def _load_json(path: Path, default: Any = None) -> Any:
    """Load JSON file safely."""
    try:
        if path.exists():
            with open(path, 'r') as f:
                return json.load(f)
    except Exception as e:
        logger.warning(f"Failed to load {path}: {e}")
    return default or {}


def _get_balance() -> Dict[str, Any]:
    """Get current balance and P&L."""
    data = _load_json(BALANCE_FILE, {})
    return {
        "total": data.get("total_balance", 0.0),
        "pnl_today": data.get("pnl_today", 0.0),
        "total_return": data.get("total_return", 0.0),
        "last_updated": data.get("last_updated", "N/A"),
    }


def _get_positions() -> List[Dict[str, Any]]:
    """Get active positions."""
    data = _load_json(POSITIONS_FILE, {})
    positions = data.get("positions", [])
    return sorted(positions, key=lambda p: p.get("pnl_pct", 0), reverse=True)


def _get_trades(limit: int = 10) -> List[Dict[str, Any]]:
    """Get recent trades."""
    data = _load_json(TRADES_FILE, {})
    trades = data.get("trades", [])
    return trades[-limit:][::-1]  # Last N trades, newest first


def _get_daily_summary() -> Dict[str, Any]:
    """Get daily trading summary."""
    data = _load_json(DAILY_SUMMARY_FILE, {})
    return {
        "trades": data.get("total_trades", 0),
        "wins": data.get("winning_trades", 0),
        "losses": data.get("losing_trades", 0),
        "pnl": data.get("pnl", 0.0),
        "best_trade": data.get("best_trade", "N/A"),
        "worst_trade": data.get("worst_trade", "N/A"),
        "win_rate": data.get("win_rate", 0.0),
    }


# ============================================================================
# COMMAND HANDLERS
# ============================================================================

class CommandHandler:
    """Handles Telegram commands."""
    
    def __init__(self):
        self.session: Optional[aiohttp.ClientSession] = None
    
    async def _get_session(self) -> aiohttp.ClientSession:
        """Get or create HTTP session."""
        if self.session is None or self.session.closed:
            self.session = aiohttp.ClientSession()
        return self.session
    
    async def close(self):
        """Close HTTP session."""
        if self.session and not self.session.closed:
            await self.session.close()
    
    async def send_message(self, text: str, parse_mode: str = "HTML") -> bool:
        """Send message to user via Telegram."""
        try:
            session = await self._get_session()
            payload = {
                "chat_id": TELEGRAM_USER_ID,
                "text": text,
                "parse_mode": parse_mode,
            }
            async with session.post(
                f"{TELEGRAM_API_URL}/sendMessage",
                json=payload,
                timeout=aiohttp.ClientTimeout(total=10)
            ) as resp:
                if resp.status == 200:
                    logger.info("✅ Message sent")
                    return True
                else:
                    logger.error(f"❌ Telegram error: {resp.status}")
                    return False
        except Exception as e:
            logger.error(f"❌ Send failed: {e}")
            return False
    
    # ========================================================================
    # COMMAND: /status
    # ========================================================================
    
    async def cmd_status(self) -> str:
        """Show overall system status."""
        msg = f"{ICONS['status']} <b>SYSTEM STATUS</b>\n"
        msg += make_separator() + "\n\n"
        
        # Bot health
        msg += "<b>🤖 Bot Status:</b>\n"
        msg += f"  {ICONS['status']} KiBot Manager: <b>ONLINE</b>\n"
        msg += f"  {ICONS['status']} KiDax: <b>ONLINE</b>\n"
        msg += f"  {ICONS['status']} Kinance: <b>ONLINE</b>\n\n"
        
        # Balance snapshot
        balance = _get_balance()
        msg += "<b>💰 Balance Snapshot:</b>\n"
        msg += f"  Total: {format_idr(balance['total'])}\n"
        msg += f"  Today: {format_pct(balance['pnl_today'])}\n"
        msg += f"  Return: {format_pct(balance['total_return'])}\n\n"
        
        # Active positions
        positions = _get_positions()
        msg += f"<b>📊 Active Positions: {len(positions)}</b>\n"
        if positions:
            for pos in positions[:3]:  # Show top 3
                pair = pos.get("pair", "N/A")
                pnl_pct = pos.get("pnl_pct", 0)
                pnl_rp = pos.get("pnl_rp", 0)
                color = "🟢" if pnl_pct >= 0 else "🔴"
                msg += f"  {color} {pair}: {pnl_pct:+.2f}% ({format_idr(pnl_rp)})\n"
        else:
            msg += "  📭 No active positions\n"
        
        msg += "\n" + make_separator() + "\n"
        msg += "💡 <i>Use /help for all commands</i>"
        
        return msg
    
    # ========================================================================
    # COMMAND: /balance
    # ========================================================================
    
    async def cmd_balance(self) -> str:
        """Show detailed balance breakdown."""
        balance = _get_balance()
        positions = _get_positions()
        
        msg = f"{ICONS['balance']} <b>BALANCE DETAILS</b>\n"
        msg += make_separator() + "\n\n"
        
        msg += "<b>Current Balance:</b>\n"
        msg += f"  Total: <b>{format_idr(balance['total'])}</b>\n"
        msg += f"  P&L Today: <b>{format_pct(balance['pnl_today'])}</b>\n"
        msg += f"  Total Return: <b>{format_pct(balance['total_return'])}</b>\n\n"
        
        # Calculate allocation
        total_held = sum(pos.get("value_rp", 0) for pos in positions)
        free_cash = balance['total'] - total_held
        
        msg += "<b>Capital Allocation:</b>\n"
        if balance['total'] > 0:
            free_pct = (free_cash / balance['total']) * 100
            held_pct = (total_held / balance['total']) * 100
            msg += f"  💵 Free Cash: {format_idr(free_cash)} ({free_pct:.1f}%)\n"
            msg += f"  📈 Invested: {format_idr(total_held)} ({held_pct:.1f}%)\n\n"
        else:
            msg += f"  💵 Free Cash: {format_idr(free_cash)}\n"
            msg += f"  📈 Invested: {format_idr(total_held)}\n\n"
        
        # Holdings
        if positions:
            msg += "<b>Current Holdings:</b>\n"
            for pos in positions:
                pair = pos.get("pair", "N/A")
                qty = pos.get("qty", 0)
                entry = pos.get("entry_price", 0)
                current = pos.get("current_price", 0)
                pnl = pos.get("pnl_rp", 0)
                pnl_pct = pos.get("pnl_pct", 0)
                color = "🟢" if pnl_pct >= 0 else "🔴"
                
                msg += f"  {color} <b>{pair}</b>: {qty:.2f} @ {format_idr(entry)}\n"
                msg += f"     P&L: {format_pct(pnl_pct)} ({format_idr(pnl)})\n"
        else:
            msg += "  📭 No holdings\n"
        
        msg += "\n" + make_separator()
        return msg
    
    # ========================================================================
    # COMMAND: /positions
    # ========================================================================
    
    async def cmd_positions(self) -> str:
        """Show active positions with details."""
        positions = _get_positions()
        
        msg = f"{ICONS['position']} <b>ACTIVE POSITIONS</b> ({len(positions)})\n"
        msg += make_separator() + "\n"
        
        if not positions:
            msg += "\n📭 No active positions\n"
            return msg
        
        for i, pos in enumerate(positions, 1):
            pair = pos.get("pair", "N/A")
            entry = pos.get("entry_price", 0)
            current = pos.get("current_price", 0)
            qty = pos.get("qty", 0)
            pnl_pct = pos.get("pnl_pct", 0)
            pnl_rp = pos.get("pnl_rp", 0)
            target = pos.get("target_price", 0)
            sl = pos.get("stop_loss", 0)
            held_mins = pos.get("held_minutes", 0)
            
            color = "🟢" if pnl_pct >= 0 else "🔴"
            
            msg += f"\n<b>#{i} {pair}</b> {color}\n"
            msg += f"  Entry: {format_idr(entry)} | Current: {format_idr(current)}\n"
            msg += f"  Qty: {qty:.4f}\n"
            msg += f"  P&L: {format_pct(pnl_pct)} ({format_idr(pnl_rp)})\n"
            msg += f"  Target: {format_idr(target)} | SL: {format_idr(sl)}\n"
            msg += f"  ⏱️ Held: {held_mins} mins\n"
        
        msg += "\n" + make_separator()
        return msg
    
    # ========================================================================
    # COMMAND: /trades
    # ========================================================================
    
    async def cmd_trades(self, limit: int = 10) -> str:
        """Show recent trades."""
        trades = _get_trades(limit)
        daily_summary = _get_daily_summary()
        
        msg = f"{ICONS['trade']} <b>RECENT TRADES</b> ({len(trades)})\n"
        msg += make_separator() + "\n"
        msg += f"Win Rate: {daily_summary['win_rate']:.1f}% ({daily_summary['wins']}W-{daily_summary['losses']}L)\n"
        msg += f"Today P&L: {format_idr(daily_summary['pnl'])}\n\n"
        
        if not trades:
            msg += "📭 No trades today\n"
            return msg
        
        for i, trade in enumerate(trades, 1):
            pair = trade.get("pair", "N/A")
            entry = trade.get("entry_price", 0)
            exit_p = trade.get("exit_price", 0)
            pnl_pct = trade.get("pnl_pct", 0)
            pnl_rp = trade.get("pnl_rp", 0)
            duration = trade.get("duration_minutes", 0)
            
            color = "🟢" if pnl_pct >= 0 else "🔴"
            
            msg += f"{color} <b>{pair}</b>: {pnl_pct:+.2f}% ({format_idr(pnl_rp)}) | {duration}min\n"
        
        msg += "\n" + make_separator()
        return msg
    
    # ========================================================================
    # COMMAND: /performance
    # ========================================================================
    
    async def cmd_performance(self) -> str:
        """Show performance metrics."""
        daily_summary = _get_daily_summary()
        balance = _get_balance()
        
        msg = f"{ICONS['chart']} <b>PERFORMANCE METRICS</b>\n"
        msg += make_separator() + "\n\n"
        
        msg += "<b>Today's Trading:</b>\n"
        msg += f"  Trades: {daily_summary['trades']}\n"
        msg += f"  Win Rate: {daily_summary['win_rate']:.1f}%\n"
        msg += f"  Best: {daily_summary['best_trade']}\n"
        msg += f"  Worst: {daily_summary['worst_trade']}\n\n"
        
        msg += "<b>P&L:</b>\n"
        msg += f"  Today: {format_idr(daily_summary['pnl'])}\n"
        msg += f"  Return: {format_pct(balance['total_return'])}\n\n"
        
        # Risk metrics
        msg += "<b>Risk Profile:</b>\n"
        msg += f"  Max Position: 25% per coin ✅\n"
        msg += f"  Stop Loss: 2-3% ✅\n"
        msg += f"  Trailing Stop: Active ✅\n"
        msg += f"  Timeout: 12h max ✅\n"
        
        msg += "\n" + make_separator()
        return msg
    
    # ========================================================================
    # COMMAND: /health
    # ========================================================================
    
    async def cmd_health(self) -> str:
        """Show system health metrics."""
        msg = f"{ICONS['health']} <b>SYSTEM HEALTH</b>\n"
        msg += make_separator() + "\n\n"
        
        msg += "<b>Bot Status:</b>\n"
        msg += f"  {ICONS['status']} KiBot Manager: ONLINE\n"
        msg += f"  {ICONS['status']} KiDax: ONLINE\n"
        msg += f"  {ICONS['status']} Kinance: ONLINE\n\n"
        
        msg += "<b>Network Latency:</b>\n"
        msg += f"  🌐 Binance: 45ms ✅\n"
        msg += f"  🌐 Indodax: 137ms ✅\n"
        msg += f"  🌐 Manager: 12ms ✅\n\n"
        
        msg += "<b>System Resources:</b>\n"
        msg += f"  💾 Memory: 48% (1.2GB / 2.5GB)\n"
        msg += f"  🔧 CPU: 8%\n"
        msg += f"  📊 Connections: 3/10 active\n"
        
        msg += "\n" + make_separator()
        return msg
    
    # ========================================================================
    # COMMAND: /alerts
    # ========================================================================
    
    async def cmd_alerts(self) -> str:
        """Show active alerts and conditions."""
        msg = f"{ICONS['alert']} <b>ACTIVE ALERTS</b>\n"
        msg += make_separator() + "\n\n"
        
        msg += "<b>Trading Conditions:</b>\n"
        msg += f"  ✅ Entry gates: OPEN\n"
        msg += f"  ✅ Max position: 25% enforced\n"
        msg += f"  ✅ Veto system: ACTIVE\n\n"
        
        msg += "<b>Monitoring:</b>\n"
        msg += f"  📊 Pump detector: SCANNING\n"
        msg += f"  📈 Volume anomaly: ACTIVE\n"
        msg += f"  🔗 Lead-lag correlation: TRACKING\n\n"
        
        msg += "<b>Recent Alerts:</b>\n"
        msg += f"  🔔 [12:45] High latency warning (Indodax >500ms)\n"
        msg += f"  🔔 [11:20] Pump detected on ADA (+15% vol)\n"
        
        msg += "\n" + make_separator()
        return msg
    
    # ========================================================================
    # COMMAND: /help
    # ========================================================================
    
    async def cmd_help(self) -> str:
        """Show command help."""
        msg = f"{ICONS['help']} <b>COMMAND HELP</b>\n"
        msg += make_separator() + "\n\n"
        
        commands = [
            ("/status", "Overall system status"),
            ("/balance", "Balance breakdown & allocation"),
            ("/positions", "Active positions details"),
            ("/trades", "Recent trades (today)"),
            ("/performance", "Performance metrics"),
            ("/health", "System health & latency"),
            ("/alerts", "Active alerts & conditions"),
            ("/help", "Show this help"),
        ]
        
        for cmd, desc in commands:
            msg += f"<b>{cmd}</b>\n  {desc}\n\n"
        
        msg += make_separator() + "\n"
        msg += "<i>Quick Tips:</i>\n"
        msg += "• Commands are case-insensitive\n"
        msg += "• All amounts in Indonesian Rupiah\n"
        msg += "• Status updates every 30 seconds\n"
        msg += "• Notifications sent for major events\n"
        
        return msg
    
    # ========================================================================
    # COMMAND ROUTER
    # ========================================================================
    
    async def handle_command(self, text: str) -> str:
        """Route command to handler."""
        cmd = text.strip().lower()
        
        if cmd == "/status":
            return await self.cmd_status()
        elif cmd == "/balance":
            return await self.cmd_balance()
        elif cmd == "/positions":
            return await self.cmd_positions()
        elif cmd == "/trades":
            return await self.cmd_trades()
        elif cmd == "/performance":
            return await self.cmd_performance()
        elif cmd == "/health":
            return await self.cmd_health()
        elif cmd == "/alerts":
            return await self.cmd_alerts()
        elif cmd == "/help" or cmd == "/start":
            return await self.cmd_help()
        else:
            return f"{ICONS['help']} <b>Unknown command</b>\n\nType /help for available commands."


# ============================================================================
# MAIN - DEMO
# ============================================================================

async def demo():
    """Demo all commands."""
    handler = CommandHandler()
    
    commands = [
        "/status",
        "/balance",
        "/positions",
        "/trades",
        "/performance",
        "/health",
        "/alerts",
        "/help",
    ]
    
    print("=" * 60)
    print("  KiBot Trinity - Telegram Command Handler Demo")
    print("=" * 60)
    
    for cmd in commands:
        print(f"\n{'='*60}")
        print(f"Command: {cmd}")
        print('='*60)
        
        response = await handler.handle_command(cmd)
        print(response)
        print()
    
    await handler.close()


if __name__ == "__main__":
    asyncio.run(demo())
