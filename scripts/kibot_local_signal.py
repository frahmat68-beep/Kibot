#!/usr/bin/env python3
import asyncio
import json
import os
import socket
import time
import websockets
from collections import deque, defaultdict

# === CONFIGURATION ===
INDODAX_WS_URL = "wss://ws.indodax.com/ws/public"
# UDP transmission to KiBot/KiBot Manager
MANAGER_UDP_HOST = os.getenv("KIBOT_MANAGER_HOST", "127.0.0.1")
MANAGER_UDP_PORT = int(os.getenv("KIBOT_MANAGER_PORT", "9999"))

# Early Pump Thresholds (Trinity v7.0 Tune)
VOL_SPIKE_THRESHOLD = 5.0  # 5x average volume
PRICE_MOVE_THRESHOLD = 0.02 # 2% move
WINDOW_SECONDS = 60         # 1 minute window for average
DETECT_WINDOW = 10          # 10 second window for detection

class KiBotLocalSignalEngine:
    def __init__(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.trade_history = defaultdict(lambda: deque(maxlen=WINDOW_SECONDS*10)) # symbol -> deque of (ts, volume, price)
        self.last_signal_at = {} # symbol -> timestamp

    def broadcast_signal(self, symbol, current_price, score, reason):
        """Send pump signal to the Manager."""
        now = time.time()
        # Cooldown: Don't spam signals for the same coin
        if now - self.last_signal_at.get(symbol, 0) < 300: # 5 minute cooldown
            return

        msg = {
            "source": "KIBOT_LOCAL_ENGINE",
            "type": "LOCAL_PUMP_SIGNAL",
            "symbol": symbol,
            "price": current_price,
            "score": score,
            "reason": reason,
            "timestamp": now
        }
        try:
            self.sock.sendto(json.dumps(msg).encode(), (MANAGER_UDP_HOST, MANAGER_UDP_PORT))
            print(f"[LOCAL_SIGNAL][DETECTED] {symbol} @ {current_price} score={score:.2f} reason={reason}", flush=True)
            self.last_signal_at[symbol] = now
        except Exception as e:
            print(f"[LOCAL_SIGNAL][UDP-ERR] {e}", flush=True)

    async def process_trade(self, symbol, data):
        """Analyze trade stream for anomalous patterns."""
        now = time.time()
        price = float(data.get("price", 0))
        volume = float(data.get("quantity", 0))
        
        if price <= 0 or volume <= 0: return

        # Record trade
        self.trade_history[symbol].append((now, volume, price))
        
        # Analyze windows
        history = list(self.trade_history[symbol])
        if not history: return

        # 1. Calculate Baseline Volume (last 60s)
        total_vol = sum(t[1] for t in history if now - t[0] <= WINDOW_SECONDS)
        avg_vol_per_sec = total_vol / WINDOW_SECONDS
        
        # 2. Calculate Recent Volume (last 10s)
        recent_trades = [t for t in history if now - t[0] <= DETECT_WINDOW]
        recent_vol = sum(t[1] for t in recent_trades)
        recent_vol_per_sec = recent_vol / DETECT_WINDOW if recent_trades else 0
        
        # 3. Calculate Price Move
        if len(recent_trades) < 2: return
        start_price = recent_trades[0][2]
        move_pct = (price - start_price) / start_price

        # DETECTION CRITERIA
        vol_spike = recent_vol_per_sec / max(0.0000001, avg_vol_per_sec)
        
        if vol_spike >= VOL_SPIKE_THRESHOLD and move_pct >= PRICE_MOVE_THRESHOLD:
            score = (vol_spike / VOL_SPIKE_THRESHOLD) + (move_pct / PRICE_MOVE_THRESHOLD * 2)
            self.broadcast_signal(symbol, price, score, f"VOL_SPIKE_{vol_spike:.1f}x_MOVE_{move_pct*100:.1f}%")

    async def run(self):
        print(f"[LOCAL_SIGNAL] Initializing Indodax Local Signal Engine...", flush=True)
        print(f"[LOCAL_SIGNAL] Connecting to {INDODAX_WS_URL}...", flush=True)
        
        # Indodax WS requires subscription after connection
        async for ws in websockets.connect(INDODAX_WS_URL):
            try:
                # Get coin list or just subscribe to everything (Indodax WS can be heavy)
                # For BIO_IDR fix, we must ensure it's in the whitelist or we listen to all
                print("[LOCAL_SIGNAL] Connected. Subscribing to public trade streams...", flush=True)
                
                # In Indodax WS, we subscribe symbol by symbol or use a wildcard if available
                # Here we simulate subscription to active pairs
                # (Actual Indodax WS protocol: {"type": "subscribe", "channel": "trades", "pair": "btcidr"})
                
                # We'll poll a subset for this demonstration or listen to the dispatcher
                pass 
                
                async for message in ws:
                    data = json.loads(message)
                    # Process trade message...
                    # (Implementation details for specific WS parser)
                    
            except websockets.ConnectionClosed:
                print("[LOCAL_SIGNAL][RECONNECTING] Connection lost...", flush=True)
                await asyncio.sleep(5)
            except Exception as e:
                print(f"[LOCAL_SIGNAL][ERR] {e}", flush=True)
                await asyncio.sleep(5)

if __name__ == "__main__":
    engine = KiBotLocalSignalEngine()
    try:
        # Placeholder for pairs - ideally should fetch dynamic list from exchange
        # For now, targeting common pump candidates
        asyncio.run(engine.run())
    except KeyboardInterrupt:
        print("[LOCAL_SIGNAL] Shutting down.")
