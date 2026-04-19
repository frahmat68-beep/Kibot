#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Sequence
from urllib.request import Request, urlopen


def _enabled(flag_name: str, default: bool = True) -> bool:
    raw = os.getenv(flag_name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


class GlobalScannerMesh:
    """
    Lightweight sequential runner for the 4 auxiliary global scanners.

    This keeps the 5+1 topology alive on low-memory hosts by sharing one Python
    process instead of four separate daemons.
    """

    def __init__(self, scanners: Sequence[Any] | None = None, interval_s: int | None = None):
        self.interval_s = int(interval_s or os.getenv("SCAN_INTERVAL_S", "30"))
        self.scanners: List[Any] = list(scanners or self._build_scanners())
        runtime_root = Path(os.getenv("KIBOT_RUNTIME_ROOT", str(Path(__file__).resolve().parent.parent)))
        state_root = Path(
            os.getenv(
                "KIBOT_SCANNER_STATE_DIR",
                str(runtime_root / "state" / "scanners"),
            )
        )
        state_root.mkdir(parents=True, exist_ok=True)
        self.feed_path = state_root / "global_scanner_feed.json"
        self.supabase_url = os.getenv("SUPABASE_URL", "").rstrip("/")
        self.supabase_service_role_key = os.getenv("SUPABASE_SERVICE_ROLE_KEY", "")
        self.supabase_anon_key = os.getenv("SUPABASE_ANON_KEY", "")
        self.supabase_key = self.supabase_service_role_key or self.supabase_anon_key
        self.supabase_user_email = os.getenv("SUPABASE_USER_EMAIL", "")
        self.supabase_user_password = os.getenv("SUPABASE_USER_PASSWORD", "")
        self.supabase_access_token = ""
        self.supabase_access_token_exp = 0.0
        self.feed_bot_id = (
            os.getenv("KIBOT_SCANNER_FEED_BOT_ID")
            or os.getenv("BOT_ID")
            or "kinance"
        )
        self.feed_device_id = os.getenv("DEVICE_ID", "")
        self.feed_category = os.getenv("KIBOT_SCANNER_FEED_CATEGORY", "scanner_feed_cycle")
        self.max_signals_per_cycle = int(os.getenv("KIBOT_SCANNER_FEED_MAX_SIGNALS", "24"))
        self.supabase_enabled = _enabled("KIBOT_SCANNER_SUPABASE_MIRROR_ENABLED", True)

    def _build_scanners(self) -> List[Any]:
        from ki_bybit_scanner import KiBitScanner
        from ki_cryptocom_scanner import KiComScanner
        from ki_kucoin_scanner import KiKuScanner
        from ki_mexc_scanner import KiMexScanner

        factories = [
            ("BYBIT", "KIBOT_ENABLE_BYBIT_SCANNER", KiBitScanner),
            ("KUCOIN", "KIBOT_ENABLE_KUCOIN_SCANNER", KiKuScanner),
            ("CRYPTOCOM", "KIBOT_ENABLE_CRYPTOCOM_SCANNER", KiComScanner),
            ("MEXC", "KIBOT_ENABLE_MEXC_SCANNER", KiMexScanner),
        ]
        active: List[Any] = []
        for name, flag, factory in factories:
            if not _enabled(flag, True):
                print(f"[GLOBAL_SCANNER_MESH] skip {name} disabled via {flag}", flush=True)
                continue
            try:
                active.append(factory())
            except Exception as error:
                print(f"[GLOBAL_SCANNER_MESH][WARN] init {name} failed reason={error}", flush=True)
        return active

    def _signal_uid(self, signal: Dict[str, Any]) -> str:
        return ":".join(
            [
                str(signal.get("exchange") or "UNKNOWN").upper(),
                str(signal.get("pair_indodax") or "").lower(),
                str(signal.get("timestamp") or ""),
            ]
        )

    def _write_feed_snapshot(self, cycle: Dict[str, Any]) -> None:
        try:
            tmp_path = self.feed_path.with_suffix(".tmp")
            tmp_path.write_text(json.dumps(cycle, ensure_ascii=False), encoding="utf-8")
            os.replace(tmp_path, self.feed_path)
        except Exception as error:
            print(f"[GLOBAL_SCANNER_MESH][WARN] write feed failed reason={error}", flush=True)

    def _supabase_bearer_token(self) -> str:
        if self.supabase_service_role_key:
            return self.supabase_service_role_key
        if self.supabase_access_token and time.time() < self.supabase_access_token_exp:
            return self.supabase_access_token
        if not (self.supabase_url and self.supabase_anon_key and self.supabase_user_email and self.supabase_user_password):
            return self.supabase_key
        payload = json.dumps(
            {
                "email": self.supabase_user_email,
                "password": self.supabase_user_password,
            }
        ).encode("utf-8")
        request = Request(
            f"{self.supabase_url}/auth/v1/token?grant_type=password",
            data=payload,
            headers={
                "apikey": self.supabase_anon_key,
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urlopen(request, timeout=10) as response:
                auth_payload = json.loads(response.read().decode("utf-8"))
            token = str(auth_payload.get("access_token") or "")
            expires_in = int(auth_payload.get("expires_in") or 3600)
            if token:
                self.supabase_access_token = token
                self.supabase_access_token_exp = time.time() + max(60, expires_in - 120)
            return token or self.supabase_key
        except Exception as error:
            print(f"[GLOBAL_SCANNER_MESH][WARN] supabase auth error reason={error}", flush=True)
            return self.supabase_key

    def _mirror_cycle_to_supabase(self, cycle: Dict[str, Any]) -> None:
        if not self.supabase_enabled or not self.supabase_url or not self.supabase_key:
            return
        if int(cycle.get("total_sent") or 0) <= 0:
            return
        payload = [
            {
                "bot_id": self.feed_bot_id,
                "device_id": self.feed_device_id or None,
                "level": "INFO",
                "category": self.feed_category,
                "message": (
                    f"scanner mesh feed_id={cycle.get('feed_id')} "
                    f"sent={cycle.get('total_sent')} scanned={cycle.get('total_scanned')}"
                ),
                "metadata": {
                    "feed_id": cycle.get("feed_id"),
                    "source": "global_scanner_mesh",
                    "summary": {
                        "started_at": cycle.get("started_at"),
                        "published_at": cycle.get("published_at"),
                        "total_scanned": cycle.get("total_scanned"),
                        "total_sent": cycle.get("total_sent"),
                        "elapsed_sec": cycle.get("elapsed_sec"),
                        "exchange_count": len(cycle.get("exchanges") or []),
                    },
                    "signals": cycle.get("signals") or [],
                    "exchanges": cycle.get("exchanges") or [],
                },
            }
        ]
        try:
            bearer_token = self._supabase_bearer_token()
            request = Request(
                f"{self.supabase_url}/rest/v1/logs",
                data=json.dumps(payload).encode("utf-8"),
                headers={
                    "apikey": self.supabase_anon_key or self.supabase_key,
                    "Authorization": f"Bearer {bearer_token}",
                    "Content-Type": "application/json",
                    "Prefer": "return=minimal",
                },
                method="POST",
            )
            with urlopen(request, timeout=10) as response:
                status_code = int(getattr(response, "status", 0) or 0)
                body = response.read().decode("utf-8", errors="ignore")
            if status_code not in (200, 201, 204):
                print(
                    f"[GLOBAL_SCANNER_MESH][WARN] supabase mirror failed "
                    f"status={status_code} body={body[:200]}",
                    flush=True,
                )
        except Exception as error:
            print(f"[GLOBAL_SCANNER_MESH][WARN] supabase mirror error reason={error}", flush=True)

    def run_once(self) -> Dict[str, Any]:
        started_at = time.time()
        total_scanned = 0
        total_sent = 0
        exchange_summaries: List[Dict[str, Any]] = []
        collected_signals: List[Dict[str, Any]] = []

        for scanner in self.scanners:
            exchange = str(getattr(scanner, "exchange", "UNKNOWN")).upper()
            scan_started = time.time()
            scanned = 0
            sent = 0
            error_text = ""
            try:
                tickers = scanner.fetch_tickers() or {}
                scanned = len(tickers)
                for base_sym, data in tickers.items():
                    signal = scanner.detect_signal(
                        base_symbol=base_sym,
                        price=data.get("price", 0),
                        vol_usdt=data.get("vol_usdt_24h", 0),
                        change_24h=data.get("change_24h", 0),
                        change_1h=data.get("change_1h", 0),
                    )
                    if signal:
                        signal["signal_uid"] = self._signal_uid(signal)
                        scanner.send_signal(signal)
                        collected_signals.append(dict(signal))
                        sent += 1
                if hasattr(scanner, "_save_state"):
                    scanner._save_state()
            except Exception as error:
                error_text = str(error)
                print(f"[GLOBAL_SCANNER_MESH][WARN] {exchange} failed reason={error}", flush=True)

            elapsed = time.time() - scan_started
            total_scanned += scanned
            total_sent += sent
            exchange_summaries.append(
                {
                    "exchange": exchange,
                    "scanned": scanned,
                    "sent": sent,
                    "elapsed_sec": round(elapsed, 2),
                    "error": error_text,
                }
            )

        ranked_by_exchange: Dict[str, List[Dict[str, Any]]] = {}
        for signal in sorted(
            collected_signals,
            key=lambda item: (
                float(item.get("weighted_contrib") or 0.0),
                float(item.get("detection_score") or 0.0),
                float(item.get("change_1h") or 0.0),
            ),
            reverse=True,
        ):
            ranked_by_exchange.setdefault(str(signal.get("exchange") or "UNKNOWN").upper(), []).append(signal)
        top_signals: List[Dict[str, Any]] = []
        exchange_order = list(ranked_by_exchange.keys())
        while exchange_order and len(top_signals) < self.max_signals_per_cycle:
            next_round: List[str] = []
            for exchange in exchange_order:
                bucket = ranked_by_exchange.get(exchange) or []
                if bucket:
                    top_signals.append(bucket.pop(0))
                    if bucket:
                        next_round.append(exchange)
                    if len(top_signals) >= self.max_signals_per_cycle:
                        break
            exchange_order = next_round
        cycle = {
            "feed_id": f"mesh-{int(started_at * 1000)}",
            "started_at": datetime.now(timezone.utc).isoformat(),
            "published_at": datetime.now(timezone.utc).isoformat(),
            "total_scanned": total_scanned,
            "total_sent": total_sent,
            "elapsed_sec": round(time.time() - started_at, 2),
            "exchanges": exchange_summaries,
            "signals": top_signals,
        }
        self._write_feed_snapshot(cycle)
        self._mirror_cycle_to_supabase(cycle)
        print(
            f"[GLOBAL_SCANNER_MESH] scanned={total_scanned} sent={total_sent} "
            f"elapsed={cycle['elapsed_sec']:.2f}s exchanges={len(exchange_summaries)}",
            flush=True,
        )
        return cycle

    def run(self) -> None:
        if not self.scanners:
            print("[GLOBAL_SCANNER_MESH][WARN] no scanners enabled; sleeping", flush=True)
        while True:
            cycle_started = time.time()
            self.run_once()
            elapsed = time.time() - cycle_started
            time.sleep(max(1.0, float(self.interval_s) - elapsed))


if __name__ == "__main__":
    GlobalScannerMesh().run()
