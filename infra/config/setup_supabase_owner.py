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


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: infra/config/setup_supabase_owner.py <email>")
        return 1

    root = Path(__file__).resolve().parent.parent
    env = load_env(root / ".env")
    url = env.get("SUPABASE_URL")
    anon_key = env.get("SUPABASE_ANON_KEY")
    password = env.get("SUPABASE_USER_PASSWORD")
    email = sys.argv[1].strip()

    if not url or not anon_key or not password:
        print("Missing SUPABASE_URL, SUPABASE_ANON_KEY, or SUPABASE_USER_PASSWORD in .env")
        return 1

    endpoint = url.rstrip("/") + "/auth/v1/signup"
    payload = json.dumps({"email": email, "password": password}).encode()
    request = urllib.request.Request(
        endpoint,
        data=payload,
        method="POST",
        headers={
            "apikey": anon_key,
            "Content-Type": "application/json",
        },
    )

    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            data = json.load(response)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "ignore")
        print(f"Supabase signup failed: HTTP {exc.code} {body}")
        return 1

    current_env = load_env(root / ".env")
    current_env["SUPABASE_USER_EMAIL"] = email
    lines = [f"{key}={value}" for key, value in current_env.items()]
    (root / ".env").write_text("\n".join(lines) + "\n")

    print(f"Supabase owner recorded in .env for {email}.")
    token_endpoint = url.rstrip("/") + "/auth/v1/token?grant_type=password"
    login_payload = json.dumps({"email": email, "password": password}).encode()
    login_request = urllib.request.Request(
        token_endpoint,
        data=login_payload,
        method="POST",
        headers={
            "apikey": anon_key,
            "Content-Type": "application/json",
        },
    )

    try:
        with urllib.request.urlopen(login_request, timeout=20):
            print("Owner login is active. You can use the same email and password from .env immediately.")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "ignore")
        if "email_not_confirmed" in body:
            resend_request = urllib.request.Request(
                url.rstrip("/") + "/auth/v1/resend",
                data=json.dumps({"type": "signup", "email": email}).encode(),
                method="POST",
                headers={
                    "apikey": anon_key,
                    "Content-Type": "application/json",
                },
            )
            try:
                with urllib.request.urlopen(resend_request, timeout=20):
                    pass
            except urllib.error.HTTPError:
                pass
            print("Email confirmation is still required. Check the inbox/spam folder and open the Supabase signup link.")
        else:
            print(f"Owner login check failed after signup: HTTP {exc.code} {body}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
