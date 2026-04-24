#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def _load_dotenv_early() -> None:
    candidates = [
        Path(".env.server"),
        Path(".env.kibot"),
        Path(".env.kibot_manager"),
        Path(".env"),
        Path("scripts/.env"),
        Path("../.env"),
    ]
    explicit = os.getenv("KIBOT_OLLAMA_GATEWAY_ENV_FILE")
    if explicit:
        candidates.insert(0, Path(explicit))
    for path in candidates:
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            raw = line.strip()
            if not raw or raw.startswith("#") or "=" not in raw:
                continue
            key, value = raw.split("=", 1)
            key = key.strip()
            value = value.strip().strip("'").strip('"')
            if key and key not in os.environ:
                os.environ[key] = value


_load_dotenv_early()

HOST = os.getenv("KIBOT_OLLAMA_GATEWAY_BIND_HOST", "0.0.0.0")
PORT = int(os.getenv("KIBOT_OLLAMA_GATEWAY_PORT", "11435"))
UPSTREAM = os.getenv("KIBOT_OLLAMA_UPSTREAM", "http://127.0.0.1:11434")
TOKEN = (
    os.getenv("KIBOT_OLLAMA_GATEWAY_TOKEN", "").strip()
    or os.getenv("OLLAMA_API_KEY", "").strip()
)
TIMEOUT = float(os.getenv("KIBOT_OLLAMA_GATEWAY_TIMEOUT_SEC", "90"))
ALLOWED_POST = {"/api/chat", "/api/generate", "/api/embed"}
ALLOWED_GET = {"/api/tags", "/api/ps"}


class OllamaGatewayHandler(BaseHTTPRequestHandler):
    server_version = "KiBotOllamaGateway/1.0"

    def _json(self, code: int, payload: Dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        if not TOKEN:
            return False
        auth = self.headers.get("Authorization", "").strip()
        return auth == f"Bearer {TOKEN}"

    def _forward(self, method: str, path: str, body: bytes | None = None) -> None:
        upstream_url = f"{UPSTREAM}{path}"
        headers = {"Content-Type": "application/json"}
        request = Request(upstream_url, data=body, headers=headers, method=method)
        try:
            with urlopen(request, timeout=TIMEOUT) as response:
                payload = response.read()
                self.send_response(response.status)
                self.send_header("Content-Type", response.headers.get("Content-Type", "application/json"))
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
        except HTTPError as error:
            payload = error.read() or b"{}"
            self.send_response(error.code)
            self.send_header("Content-Type", error.headers.get("Content-Type", "application/json"))
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except URLError as error:
            self._json(HTTPStatus.BAD_GATEWAY, {"ok": False, "error": f"upstream_unreachable:{error.reason}"})
        except Exception as error:
            self._json(HTTPStatus.INTERNAL_SERVER_ERROR, {"ok": False, "error": f"gateway_error:{type(error).__name__}"})

    def do_GET(self) -> None:
        if self.path == "/health":
            tags_ok = False
            try:
                with urlopen(Request(f"{UPSTREAM}/api/tags", method="GET"), timeout=5) as response:
                    tags_ok = response.status == 200
            except Exception:
                tags_ok = False
            self._json(
                HTTPStatus.OK if tags_ok else HTTPStatus.BAD_GATEWAY,
                {
                    "ok": tags_ok,
                    "upstream": UPSTREAM,
                    "auth_configured": bool(TOKEN),
                },
            )
            return
        if self.path not in ALLOWED_GET:
            self._json(HTTPStatus.NOT_FOUND, {"ok": False, "error": "not_found"})
            return
        if not self._authorized():
            self._json(HTTPStatus.UNAUTHORIZED, {"ok": False, "error": "unauthorized"})
            return
        self._forward("GET", self.path)

    def do_POST(self) -> None:
        if self.path not in ALLOWED_POST:
            self._json(HTTPStatus.NOT_FOUND, {"ok": False, "error": "not_found"})
            return
        if not self._authorized():
            self._json(HTTPStatus.UNAUTHORIZED, {"ok": False, "error": "unauthorized"})
            return
        length = int(self.headers.get("Content-Length", "0") or 0)
        body = self.rfile.read(length) if length > 0 else b"{}"
        self._forward("POST", self.path, body)

    def log_message(self, format: str, *args: Any) -> None:
        print(f"[OLLAMA_GATEWAY] {self.address_string()} {format % args}", flush=True)


def main() -> None:
    if not TOKEN:
        raise SystemExit("KIBOT_OLLAMA_GATEWAY_TOKEN or OLLAMA_API_KEY is required")
    server = ThreadingHTTPServer((HOST, PORT), OllamaGatewayHandler)
    print(f"[OLLAMA_GATEWAY] listening on {HOST}:{PORT} -> {UPSTREAM}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
