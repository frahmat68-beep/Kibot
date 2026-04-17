#!/usr/bin/env python3
"""
KiBot Trinity - Telegram Command Listener
=========================================
Listens for Telegram commands via webhook and routes to command handlers.
"""

import asyncio
import logging
import os
from aiohttp import web
from kibot_command_handler import CommandHandler

# ============================================================================
# CONFIGURATION
# ============================================================================

TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
TELEGRAM_API_URL = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}"

# Server config
WEBHOOK_PORT = 8889
WEBHOOK_PATH = f"/telegram/{TELEGRAM_BOT_TOKEN}" if TELEGRAM_BOT_TOKEN else "/telegram/unconfigured"

# ============================================================================
# LOGGING
# ============================================================================

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger("KiBot.CommandListener")

# ============================================================================
# GLOBALS
# ============================================================================

command_handler: CommandHandler = None


# ============================================================================
# WEBHOOK HANDLER
# ============================================================================

async def handle_webhook(request: web.Request) -> web.Response:
    """Handle incoming Telegram webhook messages."""
    global command_handler
    
    try:
        data = await request.json()
        
        # Extract message
        message = data.get("message", {})
        text = message.get("text", "").strip()
        user_id = message.get("from", {}).get("id")
        
        if not text or not user_id:
            return web.Response(status=200)
        
        # Check if it's a command
        if not text.startswith("/"):
            return web.Response(status=200)
        
        logger.info(f"📨 Received command: {text} from user {user_id}")
        
        # Handle command
        response_text = await command_handler.handle_command(text)
        
        # Send response
        success = await command_handler.send_message(response_text)
        if success:
            logger.info(f"✅ Response sent")
        
        return web.Response(status=200)
        
    except Exception as e:
        logger.error(f"❌ Error: {e}")
        return web.Response(status=500)


# ============================================================================
# SERVER SETUP
# ============================================================================

async def setup_app() -> web.Application:
    """Setup aiohttp application."""
    global command_handler
    
    # Create app
    app = web.Application()
    
    # Initialize command handler
    command_handler = CommandHandler()
    
    # Register webhook endpoint
    app.router.add_post(WEBHOOK_PATH, handle_webhook)
    
    # Health check endpoint
    async def health(request):
        return web.json_response({"status": "ok"})
    
    app.router.add_get("/health", health)
    
    # Cleanup on shutdown
    async def cleanup(app):
        await command_handler.close()
    
    app.cleanup_ctx.append(cleanup)
    
    return app


# ============================================================================
# MAIN
# ============================================================================

async def main():
    """Start the webhook server."""
    
    logger.info("=" * 60)
    logger.info("  KiBot Trinity - Telegram Command Listener")
    logger.info("=" * 60)
    
    app = await setup_app()
    
    runner = web.AppRunner(app)
    await runner.setup()
    
    site = web.TCPSite(runner, "0.0.0.0", WEBHOOK_PORT)
    await site.start()
    
    logger.info(f"🚀 Server started on port {WEBHOOK_PORT}")
    logger.info(f"📍 Webhook path: {WEBHOOK_PATH}")
    logger.info(f"✅ Listening for Telegram commands...")
    
    # Keep running
    try:
        await asyncio.Event().wait()
    except KeyboardInterrupt:
        logger.info("\n📴 Shutting down...")
        await runner.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
