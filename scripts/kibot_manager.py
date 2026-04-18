import os
import sys
import time
import json
import socket
import threading
import urllib.request

# Configuration
UDP_BIND_HOST = "0.0.0.0"
UDP_BIND_PORT = 8789
SUPABASE_PUSH_INTERVAL_SEC = 30
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN")
TELEGRAM_CHAT_ID = os.getenv("TELEGRAM_CHAT_ID")

class KiBotManager:
    def __init__(self):
        self.last_supabase_push = 0
        self.metrics = {"balance": 0, "pnl": 0, "status": "BOOTING"}

    def run(self):
        print(f"[*] KiBot Manager starting (UDP Port {UDP_BIND_PORT})...")
        threading.Thread(target=self.udp_listener, daemon=True).start()
        
        while True:
            self.tick()
            time.sleep(1)

    def udp_listener(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind((UDP_BIND_HOST, UDP_BIND_PORT))
        while True:
            data, addr = sock.recvfrom(1024)
            try:
                msg = json.loads(data.decode())
                if msg.get("type") == "TELEMETRY":
                    self.metrics.update(msg.get("payload", {}))
            except:
                pass

    def tick(self):
        now = time.time()
        if now - self.last_supabase_push > SUPABASE_PUSH_INTERVAL_SEC:
            self.push_telemetry()
            self.last_supabase_push = now

    def push_telemetry(self):
        # Implementation of Supabase egress
        print(f"[METRIC] Real PnL: {self.metrics.get('pnl')}% | Balance: {self.metrics.get('balance')}")
        self.send_telegram_update()

    def send_telegram_update(self):
        if not TELEGRAM_BOT_TOKEN: return
        msg = f"🛡 *KiBot Trinity Stats (Live)*\n" \
              f"Eq: Rp{self.metrics.get('balance'):,.0f}\n" \
              f"PnL: {self.metrics.get('pnl'):+.2f}%\n" \
              f"Status: {self.metrics.get('status')}"
        
        url = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendMessage"
        payload = {"chat_id": TELEGRAM_CHAT_ID, "text": msg, "parse_mode": "Markdown"}
        try:
            req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json'})
            urllib.request.urlopen(req)
        except Exception as e:
            print(f"[ERROR] Telegram failed: {e}")

if __name__ == "__main__":
    KiBotManager().run()
