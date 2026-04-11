#!/usr/bin/env python3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ssl
import urllib.request
import urllib.error
import sys


HOST = "127.0.0.1"
PORT = 18080
UPSTREAM = "https://indodax.com"
TIMEOUT = 12

DEFAULT_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept": "application/json,text/plain,*/*",
    "Accept-Language": "en-US,en;q=0.9",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Cache-Control": "max-age=0",
    "Origin": "https://indodax.com",
    "Referer": "https://indodax.com/",
}


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stdout.write("%s - - [%s] %s\n" % (self.client_address[0], self.log_date_time_string(), fmt % args))

    def do_GET(self):
        target_url = f"{UPSTREAM}{self.path}"
        req = urllib.request.Request(target_url, headers=DEFAULT_HEADERS)
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT, context=ssl.create_default_context()) as resp:
                body = resp.read()
                self.send_response(resp.status)
                content_type = resp.headers.get("Content-Type", "application/json")
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)
        except urllib.error.HTTPError as e:
            body = e.read()
            self.send_response(e.code)
            self.send_header("Content-Type", e.headers.get("Content-Type", "text/plain"))
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            body = str(e).encode()
            self.send_response(502)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)


def main():
    server = ThreadingHTTPServer((HOST, PORT), ProxyHandler)
    print(f"Indodax public proxy listening on http://{HOST}:{PORT}")
    server.serve_forever()


if __name__ == "__main__":
    main()
