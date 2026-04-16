# KiBot Trinity — Changelog

## v7.0 — 2026-04 (Current)
- New: Dual Bucket Strategy (50% Lead-Lag + 50% Local)
- New: KiCom Scanner (Crypto.com sebagai konfirmator ke-2)
- New: ConvictionScore 7-layer untuk Indodax-only pairs
- New: CascadeIntelligence (GROWTH→HARD_STOP adaptive)
- New: TradeLogger (record setiap trade, sync Supabase)
- New: ExitLadder multi-level (30/30/20 partial TP)
- New: Peak detection (3/4 sinyal → exit 70%)
- New: PostMortem classifier (TIMING/PEAK_ENTRY/STOP_LOSS/FAKE_PUMP)
- New: Position persistence (survive restart)
- New: API rate limiting (avoid Indodax ban)
- New: Graceful shutdown handler
- New: Auto-discovery pair baru listing Indodax
- New: Supabase schema 5 tabel
- Fix: Binance pair mapping XLMIDR → XLMUSDT
- Fix: Order fill verification
- Cleanup: Hapus 26 file MD tidak relevan

## v6.2 — 2026-04
- Fix: ownership split, DEGRADED loop, control-plane timeout
- Fix: NUKE threshold -0.5% → -2%, CRASH_GUARD LIMIT first
