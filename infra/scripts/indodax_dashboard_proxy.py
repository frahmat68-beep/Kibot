#!/usr/bin/env python3
import os
from aiohttp import web, ClientSession, WSMsgType, ClientConnectorError


UPSTREAM = os.environ.get("KIBOT_PROXY_UPSTREAM", "http://127.0.0.1:8788").rstrip("/")
LISTEN_HOST = os.environ.get("KIBOT_PROXY_LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("KIBOT_PROXY_LISTEN_PORT", "8787"))
AUTH_TOKEN = os.environ.get("KIBOT_DASHBOARD_AUTH_TOKEN", "").strip()


def upstream_headers(request: web.Request) -> dict[str, str]:
    headers = {}
    for key, value in request.headers.items():
        lower = key.lower()
        if lower in {"host", "content-length", "connection", "upgrade", "sec-websocket-key", "sec-websocket-version", "sec-websocket-extensions"}:
            continue
        headers[key] = value
    if AUTH_TOKEN:
        headers["Authorization"] = f"Bearer {AUTH_TOKEN}"
    return headers


async def proxy_http(request: web.Request) -> web.StreamResponse:
    target = f"{UPSTREAM}{request.rel_url}"
    try:
        async with request.app["client"].request(
            request.method,
            target,
            headers=upstream_headers(request),
            data=await request.read(),
            allow_redirects=False,
        ) as resp:
            body = await resp.read()
            response = web.Response(status=resp.status, body=body)
            for key, value in resp.headers.items():
                lower = key.lower()
                if lower in {"content-length", "transfer-encoding", "connection", "content-encoding"}:
                    continue
                response.headers[key] = value
            response.headers["Cache-Control"] = "no-store"
            return response
    except (ClientConnectorError, OSError):
        return web.json_response(
            {"error": "upstream_unavailable", "upstream": UPSTREAM},
            status=503,
        )


async def proxy_ws(request: web.Request) -> web.WebSocketResponse:
    target = f"{UPSTREAM}{request.rel_url}"
    ws_server = web.WebSocketResponse(autoping=True, heartbeat=30)
    await ws_server.prepare(request)
    try:
        async with request.app["client"].ws_connect(
            target,
            headers=upstream_headers(request),
            autoping=True,
            heartbeat=30,
        ) as ws_client:
            async for msg in ws_client:
                if msg.type == WSMsgType.TEXT:
                    await ws_server.send_str(msg.data)
                elif msg.type == WSMsgType.BINARY:
                    await ws_server.send_bytes(msg.data)
                elif msg.type == WSMsgType.CLOSE:
                    break
    except (ClientConnectorError, OSError):
        await ws_server.send_json({"error": "upstream_unavailable", "upstream": UPSTREAM})
    finally:
        await ws_server.close()
    return ws_server


async def handle(request: web.Request) -> web.StreamResponse:
    if request.path in {"/ws", "/api/live/ws"}:
        return await proxy_ws(request)
    return await proxy_http(request)


async def on_startup(app: web.Application) -> None:
    app["client"] = ClientSession()


async def on_cleanup(app: web.Application) -> None:
    await app["client"].close()


def main() -> None:
    app = web.Application()
    app.router.add_route("*", "/{tail:.*}", handle)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    web.run_app(app, host=LISTEN_HOST, port=LISTEN_PORT, access_log=None)


if __name__ == "__main__":
    main()
