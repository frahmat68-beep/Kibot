#!/usr/bin/env python3
import json
import urllib.error
import urllib.request
from pathlib import Path


REQUIRED_TABLES = (
    "bots",
    "bot_state",
    "engine_leases",
    "devices",
    "command_queue",
    "logs",
    "orders",
    "weekly_learning_reviews",
)


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


def table_exists(values: dict[str, str], access_token: str, table: str) -> bool:
    request = urllib.request.Request(
        values["SUPABASE_URL"].rstrip("/") + f"/rest/v1/{table}?select=*&limit=1",
        headers={
            "apikey": values["SUPABASE_ANON_KEY"],
            "Authorization": f"Bearer {access_token}",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20):
            return True
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "ignore")
        if exc.code == 404 and "schema cache" in body:
            return False
        raise


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
        print(json.dumps({"status": "auth_error", "http_status": exc.code, "body": body[:300]}))
        return 2

    missing_tables: list[str] = []
    for table in REQUIRED_TABLES:
        try:
            if not table_exists(values, access_token, table):
                missing_tables.append(table)
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", "ignore")
            print(
                json.dumps(
                    {
                        "status": "table_check_error",
                        "table": table,
                        "http_status": exc.code,
                        "body": body[:300],
                    },
                ),
            )
            return 3

    if missing_tables:
        print(json.dumps({"status": "missing_tables", "user_id": user_id, "missing_tables": missing_tables}))
        return 4

    print(json.dumps({"status": "ready", "user_id": user_id, "checked_tables": list(REQUIRED_TABLES)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
