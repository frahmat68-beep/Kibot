#!/usr/bin/env python3
"""
KiBot Trinity - Telegram Notification Bot
Sends trade alerts, system status, and daily summaries to Telegram.
"""

import asyncio
import aiohttp
import logging
import html
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional, Deque, List
from enum import Enum

# ============================================================================
# CONFIGURATION
# ============================================================================

TELEGRAM_BOT_TOKEN = "8583424689:AAHRe8drD2hmuyN48RoFv9Me0oXwcXnSoSE"
TELEGRAM_USER_ID = "1346696386"
TELEGRAM_API_URL = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}"

# Rate limiting: Telegram allows ~30 messages/second
MAX_MESSAGES_PER_SECOND = 30
MESSAGE_BATCH_WINDOW_SECONDS = 60  # Group similar notifications within this window
MAX_RETRY_ATTEMPTS = 3
RETRY_DELAY_SECONDS = 1.0

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
        return f"Rp {amount:,.0f}".replace(",", ".")
    else:
        return f"-Rp {abs(amount):,.0f}".replace(",", ".")


def format_pct(pct: float) -> str:
    """Format percentage with sign."""
    if pct >= 0:
        return f"+{pct:.2f}%"
    else:
        return f"{pct:.2f}%"


def escape_html(text: str) -> str:
    """Escape HTML special characters for Telegram."""
    return html.escape(str(text))


def get_timestamp() -> str:
    """Get current timestamp formatted for display."""
    return datetime.now().strftime("%H:%M:%S")


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
        """Flush batched notifications."""
        if batch_type == "buy" and self._pending_buys:
            trades = self._pending_buys.copy()
            self._pending_buys.clear()
            
            if len(trades) == 1:
                t = trades[0]
                msg = (f"📈 <b>BUY</b> {escape_html(t['pair'])} @ {format_idr(t['price'])}\n"
                       f"├ Size: {format_idr(t['amount'])}\n"
                       f"└ Strategy: {escape_html(t['strategy'])}")
            else:
                msg = f"📈 <b>BATCH BUY</b> ({len(trades)} trades)\n"
                total_amount = 0
                for t in trades:
                    msg += f"├ {escape_html(t['pair'])} @ {format_idr(t['price'])}\n"
                    total_amount += t['amount']
                msg += f"└ Total Size: {format_idr(total_amount)}"
            
            await self.send_notification(msg, notification_type=NotificationType.BUY)
            
        elif batch_type == "sell" and self._pending_sells:
            trades = self._pending_sells.copy()
            self._pending_sells.clear()
            
            if len(trades) == 1:
                t = trades[0]
                if t['pnl_pct'] >= 0:
                    emoji = "💰"
                    label = "PROFIT"
                    pnl_label = "Profit"
                else:
                    emoji = "📉"
                    label = "LOSS"
                    pnl_label = "Loss"
                
                msg = (f"{emoji} <b>SELL {label}</b> {escape_html(t['pair'])} {format_pct(t['pnl_pct'])}\n"
                       f"├ Entry: {format_idr(t['entry_price'])}\n"
                       f"├ Exit: {format_idr(t['exit_price'])}\n"
                       f"└ {pnl_label}: {format_idr(t['pnl_idr'])}")
            else:
                total_pnl = sum(t['pnl_idr'] for t in trades)
                wins = sum(1 for t in trades if t['pnl_pct'] >= 0)
                
                if total_pnl >= 0:
                    emoji = "💰"
                else:
                    emoji = "📉"
                
                msg = f"{emoji} <b>BATCH SELL</b> ({len(trades)} trades)\n"
                for t in trades:
                    icon = "🟢" if t['pnl_pct'] >= 0 else "🔴"
                    msg += f"├ {icon} {escape_html(t['pair'])} {format_pct(t['pnl_pct'])}\n"
                msg += f"├ Wins: {wins}/{len(trades)}\n"
                msg += f"└ Net PnL: {format_idr(total_pnl)}"
            
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
        """Notify about system status changes."""
        status_lower = status.lower()
        
        if status_lower == "online" or status_lower == "started":
            emoji = "🚀"
            msg = f"{emoji} <b>{escape_html(component)}</b> Online"
        elif status_lower == "warning" or status_lower == "degraded":
            emoji = "⚠️"
            msg = f"{emoji} <b>{escape_html(component)}</b> Degraded"
        elif status_lower == "error" or status_lower == "offline":
            emoji = "🔴"
            msg = f"{emoji} <b>{escape_html(component)}</b> Offline"
        elif status_lower == "recovered":
            emoji = "✅"
            msg = f"{emoji} <b>{escape_html(component)}</b> Recovered"
        else:
            emoji = "🤖"
            msg = f"{emoji} <b>{escape_html(component)}</b>: {escape_html(status)}"
        
        if details:
            msg += f"\n└ {escape_html(details)}"
        
        await self.send_notification(msg, notification_type=NotificationType.SYSTEM)
    
    async def notify_daily_summary(self, trades: int, win_rate: float, pnl: float,
                                   additional_stats: Optional[dict] = None):
        """Send daily trading summary."""
        pnl_emoji = "📈" if pnl >= 0 else "📉"
        
        msg = (f"📊 <b>Daily Report</b> | {datetime.now().strftime('%d %b %Y')}\n"
               f"━━━━━━━━━━━━━━━━━━━\n"
               f"├ 📊 Trades: <b>{trades}</b>\n"
               f"├ 🎯 Win Rate: <b>{win_rate:.1f}%</b>\n"
               f"└ {pnl_emoji} PnL: <b>{format_idr(pnl)}</b>")
        
        if additional_stats:
            msg += "\n\n<b>Details:</b>"
            for key, value in additional_stats.items():
                if isinstance(value, float):
                    if 'pct' in key.lower() or 'rate' in key.lower():
                        msg += f"\n├ {key}: {value:.1f}%"
                    else:
                        msg += f"\n├ {key}: {format_idr(value)}"
                else:
                    msg += f"\n├ {key}: {value}"
        
        await self.send_notification(msg, notification_type=NotificationType.DAILY)
    
    async def notify_error(self, error: str, severity: str = "warning"):
        """Notify about errors and warnings."""
        severity_lower = severity.lower()
        
        if severity_lower == "critical" or severity_lower == "error":
            emoji = "🚨"
            label = "CRITICAL"
        elif severity_lower == "warning":
            emoji = "⚠️"
            label = "WARNING"
        else:
            emoji = "ℹ️"
            label = "INFO"
        
        msg = f"{emoji} <b>{label}</b>\n└ {escape_html(error)}"
        
        await self.send_notification(msg, notification_type=NotificationType.ERROR)
    
    async def notify_startup(self, balance: float):
        """Send bot startup notification."""
        msg = (f"🚀 <b>KiBot Trinity Online</b>\n"
               f"━━━━━━━━━━━━━━━━━━━\n"
               f"├ ⏰ Time: {get_timestamp()}\n"
               f"├ 💰 Balance: <b>{format_idr(balance)}</b>\n"
               f"├ 🔗 Kinance: Connecting...\n"
               f"├ 🔗 KiDax: Connecting...\n"
               f"└ 📡 Status: Initializing")
        
        await self.send_notification(msg, notification_type=NotificationType.SYSTEM)
    
    async def notify_heartbeat(self, components: dict):
        """Send periodic heartbeat status."""
        msg = "💓 <b>Heartbeat</b>\n"
        
        for component, status in components.items():
            if status.get('healthy', False):
                emoji = "🟢"
            else:
                emoji = "🔴"
            msg += f"├ {emoji} {escape_html(component)}\n"
        
        msg = msg.rstrip('\n')  # Remove trailing newline
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


async def notify_daily_summary(trades: int, win_rate: float, pnl: float):
    """Send daily trading summary."""
    await get_notifier().notify_daily_summary(trades, win_rate, pnl)


async def notify_error(error: str, severity: str = "warning"):
    """Notify about errors."""
    await get_notifier().notify_error(error, severity)


# ============================================================================
# TEST / MAIN
# ============================================================================

async def test_notifications():
    """Test the notification system with sample messages."""
    notifier = get_notifier()
    
    try:
        logger.info("Testing Telegram notifications...")
        
        # Test 1: Startup message
        await notifier.notify_startup(balance=15_000_000)
        await asyncio.sleep(1)
        
        # Test 2: Buy notification
        await notifier.notify_buy(
            pair="BTC_IDR",
            price=1_050_000_000,
            amount=500_000,
            strategy="LeadLag-Binance"
        )
        await asyncio.sleep(1)
        
        # Test 3: Sell with profit
        await notifier.notify_sell(
            pair="BTC_IDR",
            entry_price=1_050_000_000,
            exit_price=1_065_750_000,
            pnl_pct=1.5,
            pnl_idr=7_500
        )
        await asyncio.sleep(1)
        
        # Test 4: System status
        await notifier.notify_system_status(
            component="KiBot Manager",
            status="online",
            details="All systems operational"
        )
        await asyncio.sleep(1)
        
        # Test 5: Daily summary
        await notifier.notify_daily_summary(
            trades=24,
            win_rate=66.7,
            pnl=125_000
        )
        
        logger.info("✅ All test notifications sent!")
        
        # Give time for queue to process
        await asyncio.sleep(2)
        
    finally:
        await notifier.close()


if __name__ == "__main__":
    print("=" * 50)
    print("KiBot Trinity - Telegram Notification Bot")
    print("=" * 50)
    asyncio.run(test_notifications())
