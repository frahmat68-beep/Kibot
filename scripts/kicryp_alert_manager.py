#!/usr/bin/env python3
"""
KiBot Trinity - Alert Manager
==============================
Real-time alert propagation to Telegram with intelligent throttling.

Features:
- Rate limiting (1 alert per 60s per type)
- Batching for non-critical alerts
- Immediate dispatch for critical alerts
- Thread-safe queue-based system
"""

import json
import asyncio
import threading
import time
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Optional, Dict, List, Callable, Any
import logging

logger = logging.getLogger(__name__)


class AlertSeverity(Enum):
    """Alert severity levels"""
    INFO = "ℹ️"           # Non-critical information
    WARNING = "⚠️"        # Warning, action may be needed
    CRITICAL = "🚨"       # Critical issue, immediate action needed
    SUCCESS = "✅"        # Success notification
    ERROR = "❌"          # Error occurred


class AlertType(Enum):
    """Types of alerts"""
    POSITION_TIMEOUT_WARNING = "position_timeout_warning"    # 11h warning
    POSITION_TIMEOUT_FORCED = "position_timeout_forced"      # 12h forced close
    HEARTBEAT_DELAYED = "heartbeat_delayed"                  # Latency >500ms
    API_TIMEOUT = "api_timeout"                              # Indodax API error
    ORDER_FAILED = "order_failed"                            # Order execution failed
    ORDER_PARTIAL_FILL = "order_partial_fill"                # Partial execution
    SLIPPAGE_ANOMALY = "slippage_anomaly"                    # Slippage >2%
    EMERGENCY_STOP = "emergency_stop"                        # /stop executed
    EMERGENCY_CLOSE = "emergency_close"                      # /emergency executed
    BOT_RESUMED = "bot_resumed"                              # /resume executed
    BOT_HALTED = "bot_halted"                                # Bot halted
    CAPITAL_LOW = "capital_low"                              # Free capital <10K
    POSITION_LIMIT = "position_limit"                        # 25% limit reached
    STATE_CORRUPTED = "state_corrupted"                      # State file corruption
    NETWORK_OUTAGE = "network_outage"                        # Indodax down
    NETWORK_RECOVERY = "network_recovery"                    # Indodax recovered
    ENTRY_BLOCKED = "entry_blocked"                          # Entry blocked by guardrail
    EXECUTION_SUCCESS = "execution_success"                  # Order executed
    HEALTH_CHECK = "health_check"                            # Periodic health report


@dataclass
class Alert:
    """Alert data structure"""
    type: AlertType
    severity: AlertSeverity
    title: str
    message: str
    metadata: Dict[str, Any] = field(default_factory=dict)
    timestamp: datetime = field(default_factory=datetime.utcnow)
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary"""
        return {
            "type": self.type.value,
            "severity": self.severity.value,
            "title": self.title,
            "message": self.message,
            "metadata": self.metadata,
            "timestamp": self.timestamp.isoformat(),
        }
    
    def format_telegram(self) -> str:
        """Format as Telegram message (HTML)"""
        header = f"{self.severity.value} <b>{self.title}</b>"
        body = self.message
        
        if self.metadata:
            details = "\n".join([f"  • {k}: {v}" for k, v in self.metadata.items()])
            return f"{header}\n\n{body}\n\n<code>{details}</code>"
        
        return f"{header}\n\n{body}"


class AlertManager:
    """
    Centralized alert management with rate limiting and batching.
    
    Usage:
        manager = AlertManager(send_telegram_callback)
        await manager.alert(
            type=AlertType.POSITION_TIMEOUT_WARNING,
            severity=AlertSeverity.WARNING,
            title="Position Timeout Warning",
            message="Position BTC/IDR held 11h, will force close in 1h",
            metadata={"pair": "BTC/IDR", "held_hours": 11.2}
        )
    """
    
    def __init__(
        self,
        send_telegram: Callable[[str], Any],
        rate_limit_seconds: int = 60,
        batch_window_seconds: int = 10,
        logger_obj: Optional[logging.Logger] = None,
    ):
        """
        Initialize AlertManager.
        
        Args:
            send_telegram: Async callable(message: str) to send Telegram message
            rate_limit_seconds: Cooldown between same alert type (default 60s)
            batch_window_seconds: Window for batching non-critical alerts (default 10s)
            logger_obj: Logger instance
        """
        self.send_telegram = send_telegram
        self.rate_limit_seconds = rate_limit_seconds
        self.batch_window_seconds = batch_window_seconds
        self.logger = logger_obj or logger
        
        # Rate limiting: {alert_type -> last_sent_time}
        self._last_sent: Dict[AlertType, float] = {}
        self._lock = threading.RLock()
        
        # Batching queue: {severity -> [alerts]}
        self._batch_queue: Dict[AlertSeverity, List[Alert]] = defaultdict(list)
        self._batch_task: Optional[asyncio.Task] = None
    
    async def alert(
        self,
        alert_type: AlertType,
        severity: AlertSeverity,
        title: str,
        message: str,
        metadata: Optional[Dict[str, Any]] = None,
        force_immediate: bool = False,
    ) -> bool:
        """
        Send an alert with intelligent throttling and batching.
        
        Args:
            alert_type: Type of alert (for rate limiting)
            severity: Alert severity level
            title: Alert title
            message: Alert message body
            metadata: Additional metadata (dict)
            force_immediate: Bypass throttling (for CRITICAL alerts)
        
        Returns:
            True if alert was queued/sent, False if throttled
        """
        with self._lock:
            # Check rate limit
            if not force_immediate and not self._can_send(alert_type):
                self.logger.debug(f"Alert throttled: {alert_type.value}")
                return False
            
            # Create alert
            alert_obj = Alert(
                type=alert_type,
                severity=severity,
                title=title,
                message=message,
                metadata=metadata or {},
            )
            
            # Record send time
            self._last_sent[alert_type] = time.time()
        
        # Critical alerts send immediately (async, non-blocking)
        if severity == AlertSeverity.CRITICAL or force_immediate:
            self.logger.info(f"Sending CRITICAL alert: {title}")
            try:
                await self.send_telegram(alert_obj.format_telegram())
            except Exception as e:
                self.logger.error(f"Failed to send critical alert: {e}")
            return True
        
        # Non-critical alerts are batched
        with self._lock:
            self._batch_queue[severity].append(alert_obj)
        
        # Schedule batch send
        await self._schedule_batch_send()
        return True
    
    async def _schedule_batch_send(self) -> None:
        """Schedule batch sending with debounce"""
        if self._batch_task is None or self._batch_task.done():
            self._batch_task = asyncio.create_task(self._batch_send_debounced())
    
    async def _batch_send_debounced(self) -> None:
        """Wait for batch window, then send accumulated alerts"""
        await asyncio.sleep(self.batch_window_seconds)
        
        with self._lock:
            alerts_to_send = dict(self._batch_queue)
            self._batch_queue.clear()
        
        if not alerts_to_send:
            return
        
        # Format batch message
        message = "📋 <b>Alert Batch</b>\n\n"
        for severity, alerts in sorted(alerts_to_send.items()):
            for alert in alerts:
                message += f"{alert.severity.value} {alert.title}\n"
        
        try:
            await self.send_telegram(message)
            self.logger.info(f"Sent batch with {sum(len(a) for a in alerts_to_send.values())} alerts")
        except Exception as e:
            self.logger.error(f"Failed to send batch alerts: {e}")
    
    def _can_send(self, alert_type: AlertType) -> bool:
        """Check if enough time has passed since last alert of this type"""
        if alert_type not in self._last_sent:
            return True
        
        elapsed = time.time() - self._last_sent[alert_type]
        return elapsed >= self.rate_limit_seconds
    
    async def flush(self) -> None:
        """Flush any pending batched alerts (call before shutdown)"""
        if self._batch_task and not self._batch_task.done():
            self._batch_task.cancel()
            try:
                await self._batch_task
            except asyncio.CancelledError:
                pass
        
        # Send any remaining alerts
        with self._lock:
            if self._batch_queue:
                await self._batch_send_debounced()


# Factory function to create and integrate AlertManager
async def create_alert_manager(
    telegram_bot_token: str,
    telegram_user_id: str,
    logger_obj: Optional[logging.Logger] = None,
) -> AlertManager:
    """
    Create AlertManager with Telegram integration.
    
    Args:
        telegram_bot_token: Telegram bot token
        telegram_user_id: Target Telegram user ID
        logger_obj: Logger instance
    
    Returns:
        Configured AlertManager instance
    """
    import aiohttp
    
    async def send_to_telegram(message: str) -> None:
        """Send message to Telegram"""
        url = f"https://api.telegram.org/bot{telegram_bot_token}/sendMessage"
        data = {
            "chat_id": telegram_user_id,
            "text": message,
            "parse_mode": "HTML",
        }
        
        async with aiohttp.ClientSession() as session:
            async with session.post(url, json=data, timeout=aiohttp.ClientTimeout(total=5)) as resp:
                if resp.status not in (200, 201):
                    error_text = await resp.text()
                    raise RuntimeError(f"Telegram API error: {resp.status} - {error_text}")
    
    return AlertManager(send_to_telegram, logger_obj=logger_obj)


if __name__ == "__main__":
    # Quick test
    logging.basicConfig(level=logging.DEBUG)
    
    async def test():
        manager = AlertManager(
            send_telegram=lambda msg: print(f"[TELEGRAM]\n{msg}"),
        )
        
        # Test critical alert
        await manager.alert(
            alert_type=AlertType.EMERGENCY_STOP,
            severity=AlertSeverity.CRITICAL,
            title="Emergency Stop",
            message="Bot paused - new entries blocked",
            force_immediate=True,
        )
        
        # Test rate-limited alert
        await manager.alert(
            alert_type=AlertType.HEARTBEAT_DELAYED,
            severity=AlertSeverity.WARNING,
            title="Heartbeat Delayed",
            message="KiNance heartbeat delayed by 523ms",
            metadata={"delay_ms": 523},
        )
        
        await asyncio.sleep(2)
        await manager.flush()
    
    asyncio.run(test())
