#!/usr/bin/env python3
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"')
    return values


def auth_login(values: dict[str, str]) -> tuple[str, str]:
    payload = json.dumps(
        {
            "email": values["SUPABASE_USER_EMAIL"],
            "password": values["SUPABASE_USER_PASSWORD"],
        },
    ).encode()
    request = urllib.request.Request(
        values["SUPABASE_URL"].rstrip("/") + "/auth/v1/token?grant_type=password",
        data=payload,
        method="POST",
        headers={
            "apikey": values["SUPABASE_ANON_KEY"],
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        data = json.load(response)
    return data["access_token"], data["user"]["id"]


def fetch_bot_state(values: dict[str, str], access_token: str) -> str:
    request = urllib.request.Request(
        values["SUPABASE_URL"].rstrip("/")
        + "/rest/v1/bot_state?select=bot_id,desired_state,effective_state,current_term,sync_health&limit=1",
        headers={
            "apikey": values["SUPABASE_ANON_KEY"],
            "Authorization": f"Bearer {access_token}",
            "Accept": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        data = json.load(response)
    if not data:
        return "EMPTY"
    return "READY"


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    values = load_env(root / ".env")

    required = ("SUPABASE_URL", "SUPABASE_ANON_KEY", "SUPABASE_USER_EMAIL", "SUPABASE_USER_PASSWORD")
    missing = [key for key in required if not values.get(key)]
    if missing:
        print(json.dumps({"status": "missing_config", "missing": missing}))
        return 1

    try:
        access_token, user_id = auth_login(values)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "ignore")
        if "email_not_confirmed" in body:
            print(json.dumps({"status": "pending_confirmation"}))
            return 2
        print(json.dumps({"status": "auth_error", "http_status": exc.code, "body": body[:300]}))
        return 1

    try:
        control_plane_status = fetch_bot_state(values, access_token)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "ignore")
        print(json.dumps({"status": "authenticated_but_control_plane_error", "user_id": user_id, "http_status": exc.code, "body": body[:300]}))
        return 3

    print(json.dumps({"status": "ready", "user_id": user_id, "control_plane": control_plane_status}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
