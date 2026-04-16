#!/usr/bin/env python3
import time
import json
import socket
import requests
import math
import os
from datetime import datetime

# CONFIGURATION
INDODAX_TICKER_API = "https://indodax.com/api/tickers"
MANAGER_UDP_IP = "127.0.0.1"
MANAGER_UDP_PORT = 9998
SCAN_INTERVAL = 30
CONVICTION_THRESHOLD = 0.85

def get_tickers():
    try:
        response = requests.get(INDODAX_TICKER_API, timeout=10)
        return response.json().get('tickers', {})
    except Exception as e:
        print(f"[{datetime.now()}] Error fetching tickers: {e}")
        return {}

def calculate_conviction(symbol, ticker, history):
    """
    Gaussian-based ConvictionScore calculation.
    Factors: 24h Volatility, 1h Momentum, Volume Spike, and Spread.
    """
    last = float(ticker.get('last', 0))
    high = float(ticker.get('high', 0))
    low = float(ticker.get('low', 0))
    vol_idr = float(ticker.get('vol_idr', 0))
    
    if last == 0 or vol_idr < 50_000_000: # Min 50jt volume for local bucket
        return 0.0

    # 1. Momentum (Distance from 24h Low)
    range_24h = high - low
    if range_24h == 0: return 0.0
    dist_from_low = (last - low) / range_24h
    
    # 2. Volume Spike (Relative to history if available)
    # Simplified for v7.0: purely based on 24h intensity
    vol_score = min(vol_idr / 500_000_000, 1.0) 

    # 3. Spread Factor
    buy = float(ticker.get('buy', 0))
    sell = float(ticker.get('sell', 0))
    spread = (sell - buy) / last if last > 0 else 1.0
    spread_score = max(0, 1.0 - (spread / 0.02)) # Penalty if spread > 2%

    # Final Gaussian-like smoothing
    raw_score = (dist_from_low * 0.4) + (vol_score * 0.4) + (spread_score * 0.2)
    conviction = 1 / (1 + math.exp(-10 * (raw_score - 0.5))) # Sigmoid centered at 0.5
    
    return round(conviction, 4)

def send_signal(pair, conviction):
    payload = {
        "kind": "local_signal",
        "pair": pair,
        "conviction": conviction,
        "timestamp": int(time.time()),
        "source": "kibot_local_scanner"
    }
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.sendto(json.dumps(payload).encode(), (MANAGER_UDP_IP, MANAGER_UDP_PORT))
        print(f"[{datetime.now()}] SIGNAL SENT: {pair} (Conviction: {conviction})")
    except Exception as e:
        print(f"[{datetime.now()}] UDP Send failed: {e}")

def main():
    print(f"[{datetime.now()}] KiBot Local Signal Engine v7.0 Started")
    history = {}
    
    while True:
        tickers = get_tickers()
        for symbol, data in tickers.items():
            if not symbol.endswith('_idr'): continue
            
            conviction = calculate_conviction(symbol, data, history)
            
            if conviction >= CONVICTION_THRESHOLD:
                send_signal(symbol, conviction)
                
        # Simple history tracking for next cycle volume delta
        time.sleep(SCAN_INTERVAL)

if __name__ == "__main__":
    main()
