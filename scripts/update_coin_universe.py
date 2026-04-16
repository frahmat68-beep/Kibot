#!/usr/न्वयन/env python3
"""
KiCryp Trinity - Coin Universe Auto-Discovery
Fetch Indodax API, cari koin baru, bandingkan dengan CoinUniverse.kt.
"""

import sys
import json
import urllib.request
from datetime import datetime

# URL APIs
INDODAX_PAIRS_URL = "https://indodax.com/api/tickers"
COIN_UNIVERSE_KT = "packages/core/src/commonMain/kotlin/com/kicryp/core/data/CoinUniverse.kt"

def send_telegram_alert(message: str):
    import os
    token = os.environ.get("TELEGRAM_TOKEN")
    chat_id = os.environ.get("TELEGRAM_CHAT_ID")
    if not token or not chat_id:
        print("[WARN] Telegram config missing, skipping alert.")
        return
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    payload = {"chat_id": chat_id, "text": message, "parse_mode": "Markdown"}
    try:
        req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
        urllib.request.urlopen(req, timeout=10)
    except Exception as e:
        print(f"[ERROR] Failed to send Telegram alert: {e}")

def get_indodax_pairs():
    try:
        req = urllib.request.Request(INDODAX_PAIRS_URL, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=15) as r:
            data = json.loads(r.read())
            return data.get("tickers", data)
    except Exception as e:
        print(f"[ERROR] Failed to fetch Indodax pairs: {e}")
        return {}

def extract_current_universe():
    pairs = set()
    try:
        with open(COIN_UNIVERSE_KT, "r") as f:
            lines = f.readlines()
        for line in lines:
            line = line.strip()
            # parse CoinEntry("btc_idr", ...
            if line.startswith('CoinEntry("') or line.startswith('CoinEntry("'):
                parts = line.split('"')
                if len(parts) >= 2:
                    pair_id = parts[1]
                    pairs.add(pair_id.lower())
    except Exception as e:
        print(f"[ERROR] failed to read CoinUniverse.kt: {e}")
    return pairs

def main():
    print("[DISCOVERY] Starting Coin Universe Auto-Discovery...")
    current_pairs = extract_current_universe()
    if not current_pairs:
        print("[ERROR] Failed to parse CoinUniverse.kt locally.")
        return

    indodax_tickers = get_indodax_pairs()
    if not indodax_tickers:
        return

    new_pair_candidates = []
    
    for pair_id, ticker in indodax_tickers.items():
        if not pair_id.endswith("_idr"):
            continue
        if pair_id not in current_pairs:
            # check volume 
            vol_idr = float(ticker.get("vol_idr", 0))
            if vol_idr >= 500_000_000:
                new_pair_candidates.append({
                    "pair_id": pair_id,
                    "volume_idr": vol_idr,
                    "price": ticker.get("last", "0")
                })

    if new_pair_candidates:
        new_pair_candidates.sort(key=lambda x: x["volume_idr"], reverse=True)
        msg_lines = ["🚨 *NEW PAIR CANDIDATE(S) DETECTED* 🚨\n"]
        for cand in new_pair_candidates:
            vol_bn = cand["volume_idr"] / 1_000_000_000
            msg = f"• *{cand['pair_id'].upper()}* - Price: {cand['price']} - Vol: {vol_bn:.2f} Bn IDR"
            msg_lines.append(msg)
            print(f"[NEW CANDIDATE] {msg}")
        msg_lines.append("\nHarap tambahkan profil liquidity-nya ke `CoinUniverse.kt` secara manual.")
        send_telegram_alert("\n".join(msg_lines))
    else:
        print("[DISCOVERY] No new eligible pairs found.")

if __name__ == "__main__":
    main()
