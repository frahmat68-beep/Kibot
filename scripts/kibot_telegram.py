#!/usr/bin/env python3
"""
KiBot Trinity - Telegram Notification Bot
=========================================
Premium notification system with elegant formatting.
Sends trade alerts, system status, and daily summaries to Telegram.
"""

import asyncio
import aiohttp
import logging
import html
import os
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional, Deque, List, Dict, Any
from enum import Enum

# ============================================================================
# CONFIGURATION
# ============================================================================

TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
TELEGRAM_USER_ID = os.getenv("TELEGRAM_USER_ID", os.getenv("TELEGRAM_CHAT_ID", "")).strip()
TELEGRAM_API_URL = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}"

# Rate limiting: Telegram allows ~30 messages/second
MAX_MESSAGES_PER_SECOND = 30
MESSAGE_BATCH_WINDOW_SECONDS = 60  # Group similar notifications within this window
MAX_RETRY_ATTEMPTS = 3
RETRY_DELAY_SECONDS = 1.0

# Premium Icons
ICONS = {
    "logo": "⚡",
    "buy": "📥",
    "sell_profit": "💰",
    "sell_loss": "📤",
    "system": "🤖",
    "error": "🚨",
    "warning": "⚠️",
    "success": "✅",
    "online": "🟢",
    "offline": "🔴",
    "degraded": "🟡",
    "clock": "⏰",
    "target": "🎯",
    "chart": "📊",
    "money": "💵",
    "trend_up": "📈",
    "trend_down": "📉",
    "fire": "🔥",
    "star": "⭐",
    "rocket": "🚀",
    "shield": "🛡️",
    "gear": "⚙️",
    "bell": "🔔",
    "link": "🔗",
    "pin": "📍",
}

# ============================================================================
# LOGGING SETUP
# ============================================================================

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger("KiBot.Telegram")

# ============================================================================
# DATA CLASSES
# ============================================================================

class NotificationType(Enum):
    BUY = "buy"
    SELL = "sell"
    SYSTEM = "system"
    ERROR = "error"
    DAILY = "daily"


@dataclass
class QueuedMessage:
    message: str
    parse_mode: str = "HTML"
    notification_type: NotificationType = NotificationType.SYSTEM
    timestamp: float = field(default_factory=time.time)
    retry_count: int = 0


# ============================================================================
# UTILITY FUNCTIONS
# ============================================================================

def format_idr(amount: float) -> str:
    """Format number as Indonesian Rupiah with thousand separators."""
    if amount >= 0:
        return f"Rp{amount:,.0f}".replace(",", ".")
    else:
        return f"-Rp{abs(amount):,.0f}".replace(",", ".")


def format_pct(pct: float) -> str:
    """Format percentage with sign."""
    if pct >= 0:
        return f"+{pct:.2f}%"
    else:
        return f"{pct:.2f}%"


def format_number_short(value: float) -> str:
    """Format number in short form (K, M, B)."""
    if abs(value) >= 1_000_000_000:
        return f"{value/1_000_000_000:.1f}B"
    elif abs(value) >= 1_000_000:
        return f"{value/1_000_000:.1f}M"
    elif abs(value) >= 1_000:
        return f"{value/1_000:.1f}K"
    else:
        return f"{value:.0f}"


def escape_html(text: str) -> str:
    """Escape HTML special characters for Telegram."""
    return html.escape(str(text))


def get_timestamp() -> str:
    """Get current timestamp formatted for display."""
    return datetime.now().strftime("%H:%M:%S WIB")


def get_date_label() -> str:
    """Get current date formatted for display."""
    return datetime.now().strftime("%d %b %Y")


def make_separator(char: str = "─", length: int = 24) -> str:
    """Create a visual separator line."""
    return char * length


def make_progress_bar(percent: float, length: int = 10) -> str:
    """Create a text-based progress bar."""
    filled = int(percent / 100 * length)
    empty = length - filled
    return "█" * filled + "░" * empty


# ============================================================================
# TELEGRAM BOT CLASS
# ============================================================================

class TelegramNotifier:
    """Async Telegram notification handler with rate limiting and batching."""
    
    def __init__(self):
        self._message_queue: Deque[QueuedMessage] = deque()
        self._send_times: Deque[float] = deque()
        self._pending_buys: List[dict] = []
        self._pending_sells: List[dict] = []
        self._batch_timer: Optional[asyncio.Task] = None
        self._is_processing = False
        self._session: Optional[aiohttp.ClientSession] = None
        self._lock = asyncio.Lock()
    
    async def _get_session(self) -> aiohttp.ClientSession:
        """Get or create aiohttp session."""
        if self._session is None or self._session.closed:
            timeout = aiohttp.ClientTimeout(total=30)
            self._session = aiohttp.ClientSession(timeout=timeout)
        return self._session
    
    async def close(self):
        """Close the aiohttp session."""
        if self._session and not self._session.closed:
            await self._session.close()
    
    def _check_rate_limit(self) -> bool:
        """Check if we can send a message without exceeding rate limit."""
        now = time.time()
        # Remove timestamps older than 1 second
        while self._send_times and self._send_times[0] < now - 1:
            self._send_times.popleft()
        return len(self._send_times) < MAX_MESSAGES_PER_SECOND
    
    async def _send_telegram_message(self, message: str, parse_mode: str = "HTML") -> bool:
        """Send a message to Telegram. Returns True on success."""
        try:
            session = await self._get_session()
            
            # Wait for rate limit
            while not self._check_rate_limit():
                await asyncio.sleep(0.05)
            
            payload = {
                "chat_id": TELEGRAM_USER_ID,
                "text": message,
                "parse_mode": parse_mode,
                "disable_web_page_preview": True
            }
            
            async with session.post(
                f"{TELEGRAM_API_URL}/sendMessage",
                json=payload
            ) as response:
                self._send_times.append(time.time())
                
                if response.status == 200:
                    logger.debug(f"Message sent successfully")
                    return True
                else:
                    error_text = await response.text()
                    logger.warning(f"Telegram API error {response.status}: {error_text}")
                    return False
                    
        except asyncio.TimeoutError:
            logger.error("Telegram request timed out")
            return False
        except aiohttp.ClientError as e:
            logger.error(f"Network error sending to Telegram: {e}")
            return False
        except Exception as e:
            logger.error(f"Unexpected error sending to Telegram: {e}")
            return False
    
    async def _process_queue(self):
        """Process messages from the queue with retry logic."""
        async with self._lock:
            if self._is_processing:
                return
            self._is_processing = True
        
        try:
            while self._message_queue:
                msg = self._message_queue.popleft()
                
                success = await self._send_telegram_message(msg.message, msg.parse_mode)
                
                if not success and msg.retry_count < MAX_RETRY_ATTEMPTS:
                    msg.retry_count += 1
                    logger.info(f"Retrying message (attempt {msg.retry_count}/{MAX_RETRY_ATTEMPTS})")
                    await asyncio.sleep(RETRY_DELAY_SECONDS * msg.retry_count)
                    self._message_queue.appendleft(msg)
                elif not success:
                    logger.error(f"Failed to send message after {MAX_RETRY_ATTEMPTS} attempts")
                    
        finally:
            self._is_processing = False
    
    async def send_notification(self, message: str, parse_mode: str = "HTML", 
                                notification_type: NotificationType = NotificationType.SYSTEM):
        """Queue and send a notification message."""
        queued = QueuedMessage(
            message=message,
            parse_mode=parse_mode,
            notification_type=notification_type
        )
        self._message_queue.append(queued)
        
        # Process queue in background
        asyncio.create_task(self._process_queue())
    
    async def _flush_batch(self, batch_type: str):
        """Flush batched notifications with premium formatting."""
        if batch_type == "buy" and self._pending_buys:
            trades = self._pending_buys.copy()
            self._pending_buys.clear()
            
            if len(trades) == 1:
                t = trades[0]
                pair_clean = t['pair'].replace('_idr', '').upper()
                msg = (
                    f"{ICONS['buy']} <b>ENTRY EXECUTED</b>\n"
                    f"{make_separator()}\n"
                    f"\n"
                    f"<b>{pair_clean}</b> @ {format_idr(t['price'])}\n"
                    f"├ Size: <b>{format_idr(t['amount'])}</b>\n"
                    f"├ Strategy: <code>{escape_html(t['strategy'])}</code>\n"
                    f"└ Time: {get_timestamp()}"
                )
            else:
                total_amount = sum(t['amount'] for t in trades)
                msg = (
                    f"{ICONS['buy']} <b>BATCH ENTRY</b> ({len(trades)} positions)\n"
                    f"{make_separator()}\n\n"
                )
                for i, t in enumerate(trades):
                    pair_clean = t['pair'].replace('_idr', '').upper()
                    prefix = "└" if i == len(trades) - 1 else "├"
                    msg += f"{prefix} {pair_clean} @ {format_idr(t['price'])}\n"
                msg += f"\n{ICONS['money']} Total: <b>{format_idr(total_amount)}</b>"
            
            await self.send_notification(msg, notification_type=NotificationType.BUY)
            
        elif batch_type == "sell" and self._pending_sells:
            trades = self._pending_sells.copy()
            self._pending_sells.clear()
            
            if len(trades) == 1:
                t = trades[0]
                pair_clean = t['pair'].replace('_idr', '').upper()
                
                if t['pnl_pct'] >= 0:
                    icon = ICONS['sell_profit']
                    label = "PROFIT"
                    result_icon = "🟢"
                else:
                    icon = ICONS['sell_loss']
                    label = "LOSS"
                    result_icon = "🔴"
                
                msg = (
                    f"{icon} <b>EXIT {label}</b> {result_icon}\n"
                    f"{make_separator()}\n"
                    f"\n"
                    f"<b>{pair_clean}</b>\n"
                    f"├ Entry: {format_idr(t['entry_price'])}\n"
                    f"├ Exit: {format_idr(t['exit_price'])}\n"
                    f"├ Return: <b>{format_pct(t['pnl_pct'])}</b>\n"
                    f"└ PnL: <b>{format_idr(t['pnl_idr'])}</b>\n"
                    f"\n"
                    f"<i>{get_timestamp()}</i>"
                )
            else:
                total_pnl = sum(t['pnl_idr'] for t in trades)
                wins = sum(1 for t in trades if t['pnl_pct'] >= 0)
                win_rate = (wins / len(trades)) * 100
                
                if total_pnl >= 0:
                    icon = ICONS['sell_profit']
                    result_label = "NET PROFIT"
                else:
                    icon = ICONS['sell_loss']
                    result_label = "NET LOSS"
                
                msg = (
                    f"{icon} <b>BATCH EXIT</b> ({len(trades)} positions)\n"
                    f"{make_separator()}\n\n"
                )
                
                for t in trades:
                    pair_clean = t['pair'].replace('_idr', '').upper()
                    result_icon = "🟢" if t['pnl_pct'] >= 0 else "🔴"
                    msg += f"{result_icon} {pair_clean} {format_pct(t['pnl_pct'])}\n"
                
                msg += (
                    f"\n"
                    f"├ Win Rate: <b>{win_rate:.0f}%</b> ({wins}/{len(trades)})\n"
                    f"└ {result_label}: <b>{format_idr(total_pnl)}</b>"
                )
            
            await self.send_notification(msg, notification_type=NotificationType.SELL)
    
    async def notify_buy(self, pair: str, price: float, amount: float, strategy: str):
        """Notify about a BUY trade. Batches multiple buys within time window."""
        self._pending_buys.append({
            'pair': pair,
            'price': price,
            'amount': amount,
            'strategy': strategy,
            'timestamp': time.time()
        })
        
        # Schedule batch flush
        await asyncio.sleep(0.5)  # Small delay to allow batching
        if self._pending_buys:
            # Check if oldest message is past batch window
            oldest = min(t['timestamp'] for t in self._pending_buys)
            if time.time() - oldest >= MESSAGE_BATCH_WINDOW_SECONDS or len(self._pending_buys) >= 5:
                await self._flush_batch("buy")
            else:
                # Immediate send for single trades
                await self._flush_batch("buy")
    
    async def notify_sell(self, pair: str, entry_price: float, exit_price: float, 
                          pnl_pct: float, pnl_idr: float):
        """Notify about a SELL trade. Batches multiple sells within time window."""
        self._pending_sells.append({
            'pair': pair,
            'entry_price': entry_price,
            'exit_price': exit_price,
            'pnl_pct': pnl_pct,
            'pnl_idr': pnl_idr,
            'timestamp': time.time()
        })
        
        # Schedule batch flush
        await asyncio.sleep(0.5)
        if self._pending_sells:
            await self._flush_batch("sell")
    
    async def notify_system_status(self, component: str, status: str, details: str = ""):
        """Notify about system status changes with premium formatting."""
        status_lower = status.lower()
        
        if status_lower == "online" or status_lower == "started":
            icon = ICONS['online']
            status_text = "ONLINE"
        elif status_lower == "warning" or status_lower == "degraded":
            icon = ICONS['degraded']
            status_text = "DEGRADED"
        elif status_lower == "error" or status_lower == "offline":
            icon = ICONS['offline']
            status_text = "OFFLINE"
        elif status_lower == "recovered":
            icon = ICONS['success']
            status_text = "RECOVERED"
        else:
            icon = ICONS['gear']
            status_text = status.upper()
        
        msg = (
            f"{icon} <b>{escape_html(component)}</b>\n"
            f"└ Status: <code>{status_text}</code>"
        )
        
        if details:
            msg += f"\n\n<i>{escape_html(details)}</i>"
        
        await self.send_notification(msg, notification_type=NotificationType.SYSTEM)
    
    async def notify_daily_summary(self, trades: int, win_rate: float, pnl: float,
                                   additional_stats: Optional[dict] = None):
        """Send daily trading summary with premium formatting."""
        pnl_icon = ICONS['trend_up'] if pnl >= 0 else ICONS['trend_down']
        result_emoji = "🟢" if pnl >= 0 else "🔴"
        
        # Win rate progress bar
        win_bar = make_progress_bar(win_rate)
        
        msg = (
            f"{ICONS['chart']} <b>DAILY REPORT</b>\n"
            f"{make_separator()}\n"
            f"<i>{get_date_label()}</i>\n"
            f"\n"
            f"├ {ICONS['target']} Trades: <b>{trades}</b>\n"
            f"├ {ICONS['star']} Win Rate: <b>{win_rate:.1f}%</b>\n"
            f"│   {win_bar}\n"
            f"└ {pnl_icon} PnL: {result_emoji} <b>{format_idr(pnl)}</b>\n"
        )
        
        if additional_stats:
            msg += f"\n<b>Details:</b>\n"
            items = list(additional_stats.items())
            for i, (key, value) in enumerate(items):
                prefix = "└" if i == len(items) - 1 else "├"
                if isinstance(value, float):
                    if 'pct' in key.lower() or 'rate' in key.lower():
                        msg += f"{prefix} {key}: {value:.1f}%\n"
                    else:
                        msg += f"{prefix} {key}: {format_idr(value)}\n"
                else:
                    msg += f"{prefix} {key}: {value}\n"
        
        await self.send_notification(msg, notification_type=NotificationType.DAILY)
    
    async def notify_error(self, error: str, severity: str = "warning"):
        """Notify about errors and warnings with premium formatting."""
        severity_lower = severity.lower()
        
        if severity_lower == "critical" or severity_lower == "error":
            icon = ICONS['error']
            label = "CRITICAL ERROR"
            border = "🚨🚨🚨"
        elif severity_lower == "warning":
            icon = ICONS['warning']
            label = "WARNING"
            border = ""
        else:
            icon = ICONS['bell']
            label = "NOTICE"
            border = ""
        
        if border:
            msg = (
                f"{border}\n"
                f"{icon} <b>{label}</b>\n"
                f"{make_separator()}\n"
                f"\n"
                f"{escape_html(error)}\n"
                f"\n"
                f"<i>{get_timestamp()}</i>"
            )
        else:
            msg = (
                f"{icon} <b>{label}</b>\n"
                f"└ {escape_html(error)}"
            )
        
        await self.send_notification(msg, notification_type=NotificationType.ERROR)
    
    async def notify_startup(self, balance: float):
        """Send bot startup notification with premium formatting."""
        msg = (
            f"{ICONS['rocket']} <b>KIBOT TRINITY ONLINE</b>\n"
            f"{make_separator()}\n"
            f"\n"
            f"├ {ICONS['clock']} Time: <code>{get_timestamp()}</code>\n"
            f"├ {ICONS['money']} Balance: <b>{format_idr(balance)}</b>\n"
            f"├ {ICONS['link']} KiNance: Connecting...\n"
            f"├ {ICONS['link']} KiDax: Connecting...\n"
            f"└ {ICONS['gear']} Status: Initializing\n"
            f"\n"
            f"<i>All systems starting up...</i>"
        )
        
        await self.send_notification(msg, notification_type=NotificationType.SYSTEM)
    
    async def notify_heartbeat(self, components: dict):
        """Send periodic heartbeat status with premium formatting."""
        all_healthy = all(c.get('healthy', False) for c in components.values())
        
        if all_healthy:
            header_icon = ICONS['success']
            header_text = "ALL SYSTEMS OPERATIONAL"
        else:
            header_icon = ICONS['warning']
            header_text = "SYSTEM STATUS"
        
        msg = f"{header_icon} <b>{header_text}</b>\n"
        
        items = list(components.items())
        for i, (component, status) in enumerate(items):
            prefix = "└" if i == len(items) - 1 else "├"
            if status.get('healthy', False):
                icon = ICONS['online']
                state = "OK"
            else:
                icon = ICONS['offline']
                state = "DOWN"
            
            ping = status.get('ping_ms', 0)
            if ping > 0:
                msg += f"{prefix} {icon} {escape_html(component)}: {state} ({ping}ms)\n"
            else:
                msg += f"{prefix} {icon} {escape_html(component)}: {state}\n"
        
        await self.send_notification(msg, notification_type=NotificationType.SYSTEM)
    
    async def notify_position_update(self, pair: str, entry_price: float, 
                                     current_price: float, pnl_pct: float, 
                                     pnl_idr: float, trailing_stop: Optional[float] = None):
        """Notify about position updates (trailing stop movements, etc)."""
        pair_clean = pair.replace('_idr', '').upper()
        
        if pnl_pct >= 0:
            pnl_icon = ICONS['online']
        else:
            pnl_icon = ICONS['offline']
        
        msg = (
            f"{ICONS['pin']} <b>POSITION UPDATE</b>\n"
            f"{make_separator()}\n"
            f"\n"
            f"<b>{pair_clean}</b>\n"
            f"├ Entry: {format_idr(entry_price)}\n"
            f"├ Current: {format_idr(current_price)}\n"
            f"├ PnL: {pnl_icon} <b>{format_pct(pnl_pct)}</b> ({format_idr(pnl_idr)})\n"
        )
        
        if trailing_stop:
            msg += f"└ Trailing Stop: {format_idr(trailing_stop)}"
        else:
            msg = msg.rstrip('\n').replace('├ PnL:', '└ PnL:')
        
        await self.send_notification(msg, notification_type=NotificationType.SYSTEM)
    
    async def notify_balance_update(self, balance: float, pnl_today: float, 
                                    pnl_pct_today: float, positions: int = 0):
        """Send balance/portfolio update notification."""
        if pnl_today >= 0:
            pnl_icon = ICONS['trend_up']
            result_emoji = "🟢"
        else:
            pnl_icon = ICONS['trend_down']
            result_emoji = "🔴"
        
        msg = (
            f"{ICONS['money']} <b>PORTFOLIO UPDATE</b>\n"
            f"{make_separator()}\n"
            f"\n"
            f"├ Balance: <b>{format_idr(balance)}</b>\n"
            f"├ PnL Today: {result_emoji} <b>{format_pct(pnl_pct_today)}</b>\n"
            f"├ Profit: {format_idr(pnl_today)}\n"
            f"└ Positions: {positions} active\n"
            f"\n"
            f"<i>{get_timestamp()}</i>"
        )
        
        await self.send_notification(msg, notification_type=NotificationType.SYSTEM)
    
    async def notify_market_regime(self, regime: str, confidence: str, 
                                   recommendation: str = ""):
        """Notify about market regime changes."""
        regime_icons = {
            "bullish": "🟢",
            "bearish": "🔴",
            "sideways": "🟡",
            "volatile": "⚡",
        }
        
        regime_lower = regime.lower()
        regime_icon = regime_icons.get(regime_lower, "📊")
        
        msg = (
            f"{ICONS['chart']} <b>MARKET REGIME</b>\n"
            f"{make_separator()}\n"
            f"\n"
            f"├ Regime: {regime_icon} <b>{regime.upper()}</b>\n"
            f"├ Confidence: <code>{confidence}</code>\n"
        )
        
        if recommendation:
            msg += f"└ Strategy: <i>{escape_html(recommendation)}</i>"
        else:
            msg = msg.rstrip('\n').replace('├ Confidence:', '└ Confidence:')
        
        await self.send_notification(msg, notification_type=NotificationType.SYSTEM)


# ============================================================================
# GLOBAL INSTANCE & CONVENIENCE FUNCTIONS
# ============================================================================

_notifier: Optional[TelegramNotifier] = None


def get_notifier() -> TelegramNotifier:
    """Get or create the global notifier instance."""
    global _notifier
    if _notifier is None:
        _notifier = TelegramNotifier()
    return _notifier


async def send_notification(message: str, parse_mode: str = "HTML"):
    """Send a raw notification message."""
    await get_notifier().send_notification(message, parse_mode)


async def notify_buy(pair: str, price: float, amount: float, strategy: str):
    """Notify about a BUY trade."""
    await get_notifier().notify_buy(pair, price, amount, strategy)


async def notify_sell(pair: str, entry_price: float, exit_price: float, 
                      pnl_pct: float, pnl_idr: float):
    """Notify about a SELL trade."""
    await get_notifier().notify_sell(pair, entry_price, exit_price, pnl_pct, pnl_idr)


async def notify_system_status(component: str, status: str, details: str = ""):
    """Notify about system status."""
    await get_notifier().notify_system_status(component, status, details)


async def notify_daily_summary(trades: int, win_rate: float, pnl: float,
                               additional_stats: Optional[Dict[str, Any]] = None):
    """Send daily trading summary."""
    await get_notifier().notify_daily_summary(trades, win_rate, pnl, additional_stats)


async def notify_error(error: str, severity: str = "warning"):
    """Notify about errors."""
    await get_notifier().notify_error(error, severity)


async def notify_startup(balance: float):
    """Send startup notification."""
    await get_notifier().notify_startup(balance)


async def notify_heartbeat(components: dict):
    """Send heartbeat status."""
    await get_notifier().notify_heartbeat(components)


async def notify_position_update(pair: str, entry_price: float, current_price: float,
                                  pnl_pct: float, pnl_idr: float, 
                                  trailing_stop: Optional[float] = None):
    """Notify about position updates."""
    await get_notifier().notify_position_update(
        pair, entry_price, current_price, pnl_pct, pnl_idr, trailing_stop
    )


async def notify_balance_update(balance: float, pnl_today: float, 
                                 pnl_pct_today: float, positions: int = 0):
    """Send balance update notification."""
    await get_notifier().notify_balance_update(balance, pnl_today, pnl_pct_today, positions)


async def notify_market_regime(regime: str, confidence: str, recommendation: str = ""):
    """Notify about market regime changes."""
    await get_notifier().notify_market_regime(regime, confidence, recommendation)


# ============================================================================
# TEST / MAIN
# ============================================================================

async def test_notifications():
    """Test the notification system with premium formatted messages."""
    notifier = get_notifier()
    
    try:
        logger.info("Testing Telegram notifications with premium formatting...")
        
        # Test 1: Startup message
        print("\n📤 Sending startup notification...")
        await notifier.notify_startup(balance=110_408)
        await asyncio.sleep(2)
        
        # Test 2: Buy notification
        print("📤 Sending buy notification...")
        await notifier.notify_buy(
            pair="trx_idr",
            price=2_531,
            amount=25_000,
            strategy="Lead-Lag Binance"
        )
        await asyncio.sleep(2)
        
        # Test 3: Position update with trailing
        print("📤 Sending position update...")
        await notifier.notify_position_update(
            pair="trx_idr",
            entry_price=2_531,
            current_price=2_583,
            pnl_pct=2.05,
            pnl_idr=513,
            trailing_stop=2_557
        )
        await asyncio.sleep(2)
        
        # Test 4: Sell with profit
        print("📤 Sending sell notification (profit)...")
        await notifier.notify_sell(
            pair="trx_idr",
            entry_price=2_531,
            exit_price=2_608,
            pnl_pct=3.04,
            pnl_idr=760
        )
        await asyncio.sleep(2)
        
        # Test 5: Balance update
        print("📤 Sending balance update...")
        await notifier.notify_balance_update(
            balance=111_168,
            pnl_today=760,
            pnl_pct_today=0.69,
            positions=1
        )
        await asyncio.sleep(2)
        
        # Test 6: System status
        print("📤 Sending system status...")
        await notifier.notify_system_status(
            component="KiDax Executor",
            status="online",
            details="Connected to Indodax WebSocket"
        )
        await asyncio.sleep(2)
        
        # Test 7: Market regime
        print("📤 Sending market regime...")
        await notifier.notify_market_regime(
            regime="BULLISH",
            confidence="HIGH",
            recommendation="Aggressive entry on pullbacks"
        )
        await asyncio.sleep(2)
        
        # Test 8: Heartbeat
        print("📤 Sending heartbeat...")
        await notifier.notify_heartbeat({
            "KiBot Manager": {"healthy": True, "ping_ms": 12},
            "KiDax": {"healthy": True, "ping_ms": 137},
            "KiNance": {"healthy": True, "ping_ms": 45}
        })
        await asyncio.sleep(2)
        
        # Test 9: Warning
        print("📤 Sending warning...")
        await notifier.notify_error(
            "High latency detected on Indodax API (>500ms)",
            severity="warning"
        )
        await asyncio.sleep(2)
        
        # Test 10: Daily summary
        print("📤 Sending daily summary...")
        await notifier.notify_daily_summary(
            trades=12,
            win_rate=75.0,
            pnl=15_230,
            additional_stats={
                "Best Trade": "TRX +4.2%",
                "Worst Trade": "XLM -0.8%",
                "Avg Hold Time": "47 mins",
                "Volume": 450_000
            }
        )
        
        logger.info("✅ All test notifications sent!")
        print("\n✅ All premium notifications sent! Check your Telegram.")
        
        # Give time for queue to process
        await asyncio.sleep(3)
        
    finally:
        await notifier.close()


if __name__ == "__main__":
    print("=" * 50)
    print("  KiBot Trinity - Telegram Notification Bot")
    print("  Premium Notification System v2.0")
    print("=" * 50)
    asyncio.run(test_notifications())
