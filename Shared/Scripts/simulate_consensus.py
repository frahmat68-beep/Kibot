#!/usr/bin/env python3
import socket
import json
import time

TARGET_HOST = "127.0.0.1"
TARGET_PORT = 9999

def send_udp(msg):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.sendto(json.dumps(msg).encode(), (TARGET_HOST, TARGET_PORT))
    print(f"Sent: {msg['source']} | {msg['type']} | {msg.get('symbol') or msg.get('pairId')}")

def simulate_consensus():
    print("=== Consensuns Verification Script ===")
    
    # 1. Update Binance price on Whiteboard
    send_udp({
        "source": "BINANCE",
        "type": "TICKER_UPDATE",
        "symbol": "DOGE_USDT",
        "price": 0.1500
    })
    
    # 2. Update Crypto.com price on Whiteboard (IN SYNC)
    send_udp({
        "source": "CRYPTOCOM",
        "type": "TICKER_UPDATE",
        "symbol": "DOGE_USDT",
        "price": 0.1505
    })
    
    time.sleep(1)
    
    # 3. Trigger SIGNAL from Binance
    send_udp({
        "source": "BINANCE",
        "type": "SIGNAL",
        "pairId": "doge_idr",
        "pumpScore": 85,
        "price": 2250,
        "msgType": "DETECTOR_HIT"
    })
    
    print("\n--- Testing REJECTION (Spread too high) ---")
    
    # 4. Update Crypto.com with a STALE price (Manipulation attempt)
    send_udp({
        "source": "CRYPTOCOM",
        "type": "TICKER_UPDATE",
        "symbol": "BTC_USDT",
        "price": 50000.0  # Lagging behind
    })
    
    send_udp({
        "source": "BINANCE",
        "type": "TICKER_UPDATE",
        "symbol": "BTC_USDT",
        "price": 60000.0  # Pumped
    })
    
    time.sleep(1)
    
    # 5. Trigger SIGNAL for BTC
    send_udp({
        "source": "BINANCE",
        "type": "SIGNAL",
        "pairId": "btc_idr",
        "pumpScore": 90,
        "price": 900000000,
        "msgType": "DETECTOR_HIT"
    })

if __name__ == "__main__":
    simulate_consensus()
