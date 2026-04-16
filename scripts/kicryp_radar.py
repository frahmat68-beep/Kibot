#!/usr/bin/env python3
import asyncio
import json
import os
import socket
import time
import websockets
from datetime import datetime

# === CONFIGURATION ===
KICRYP_WS_URL = "wss://stream.crypto.com/v2/market"
# Target the same port as Kinance for shared signal processing
MANAGER_UDP_HOST = os.getenv("KICRYP_MANAGER_HOST", "127.0.0.1")
MANAGER_UDP_PORT = int(os.getenv("KICRYP_MANAGER_PORT", "9999"))

# Default top-50 volume pairs on Crypto.com (can be expanded)
DEFAULT_PAIRS = [
    "BTC_USDT", "ETH_USDT", "DOGE_USDT", "SOL_USDT", "BNB_USDT", 
    "XRP_USDT", "ADA_USDT", "AVAX_USDT", "DOT_USDT", "LINK_USDT"
]

class KiCrypRadar:
    def __init__(self, pairs=None):
        self.pairs = pairs or DEFAULT_PAIRS
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.last_heartbeat = 0

    def broadcast_to_whiteboard(self, symbol, price, vol_24h):
        """Send price update to the KiCryp Manager's whiteboard."""
        msg = {
            "source": "CRYPTOCOM",
            "type": "TICKER_UPDATE",
            "symbol": symbol,
            "price": float(price),
            "vol_24h": float(vol_24h),
            "timestamp": time.time()
        }
        try:
            self.sock.sendto(json.dumps(msg).encode(), (MANAGER_UDP_HOST, MANAGER_UDP_PORT))
        except Exception as e:
            print(f"[KICRYP][UDP-ERR] {e}", flush=True)

    async def handle_heartbeat(self, ws, msg_id):
        """Respond to Crypto.com heartbeat to keep connection alive."""
        response = {
            "id": msg_id,
            "method": "public/respond-heartbeat"
        }
        await ws.send(json.dumps(response))
        # print(f"[KICRYP][HEARTBEAT] Responded to {msg_id}", flush=True)

    async def run_regime_scanner(self):
        """Periodic scan of BTC/ETH to determine global market regime."""
        while True:
            try:
                # In a real scenario, this would use the whiteboard or a separate ticker stream
                # For now, we use the tickers received to evaluate
                # logic for regime detection:
                pass
            except Exception as e:
                print(f"[KICRYP][REGIME-ERR] {e}")
            await asyncio.sleep(60)

    async def run(self):
        print(f"[KICRYP] Connecting to {KICRYP_WS_URL}...", flush=True)
        
        # Broadcast initial regime
        self.broadcast_regime("SIDEWAYS")
        
        async for ws in websockets.connect(KICRYP_WS_URL):
            try:
                # 1. Subscribe to Tickers
                sub_msg = {
                    "id": 1,
                    "method": "subscribe",
                    "params": {
                        "channels": [f"ticker.{p}" for p in self.pairs]
                    }
                }
                await ws.send(json.dumps(sub_msg))
                print(f"[KICRYP] Subscribed to {len(self.pairs)} pairs", flush=True)

                # 2. Process Messages
                async for message in ws:
                    data = json.loads(message)
                    
                    if data.get("method") == "public/heartbeat":
                        await self.handle_heartbeat(ws, data["id"])
                        continue

                    if "result" in data: continue
                    
                    channel = data.get("channel", "")
                    if channel.startswith("ticker."):
                        instr_data = data.get("data", [])
                        if instr_data:
                            ticker = instr_data[0]
                            symbol = ticker["i"]
                            last_price = float(ticker["a"])
                            vol_24h = float(ticker["v"])
                            
                            self.broadcast_to_whiteboard(symbol, last_price, vol_24h)
                            
                            # Regime Detection Logic (Simplified for BTC)
                            if symbol == "BTC_USDT":
                                # Detect PANIC: price drop > 4% (This would need historical tracking)
                                # For simulation/POC, we just keep it simple
                                pass

            except websockets.ConnectionClosed:
                print("[KICRYP] Connection closed. Reconnecting...", flush=True)
                continue
            except Exception as e:
                print(f"[KICRYP][ERR] {e}", flush=True)
                await asyncio.sleep(5)

    def broadcast_regime(self, regime: str):
        """Broadcast global market regime to manager."""
        msg = {
            "source": "KICRYP_RADAR",
            "type": "REGIME_UPDATE",
            "regime": regime,
            "timestamp": time.time()
        }
        try:
            self.sock.sendto(json.dumps(msg).encode(), (MANAGER_UDP_HOST, MANAGER_UDP_PORT))
        except Exception:
            pass

if __name__ == "__main__":
    # Expand to all USDT pairs if possible, or a large subset
    radar = KiCrypRadar()
    try:
        asyncio.run(radar.run())
    except KeyboardInterrupt:
        print("[KICRYP] Stopped by user.", flush=True)
