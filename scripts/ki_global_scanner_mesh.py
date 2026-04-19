#!/usr/bin/env python3
from __future__ import annotations

import os
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Sequence


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

    def run_once(self) -> Dict[str, Any]:
        started_at = time.time()
        total_scanned = 0
        total_sent = 0
        exchange_summaries: List[Dict[str, Any]] = []

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
                        scanner.send_signal(signal)
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

        cycle = {
            "started_at": datetime.now(timezone.utc).isoformat(),
            "total_scanned": total_scanned,
            "total_sent": total_sent,
            "elapsed_sec": round(time.time() - started_at, 2),
            "exchanges": exchange_summaries,
        }
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
