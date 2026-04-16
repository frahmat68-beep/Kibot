# KiBot Trinity v7.0

Dual Bucket autonomous trading — Indodax + Binance lead-lag + Crypto.com confirmation.

**Filosofi:** Profit sedikit demi sedikit lama lama jadi bukit
**Motto:** Minimalisir kerugian, maksimalkan probabilitas keuntungan
**Modal:** Adaptive dari equity aktual (dimulai Rp 60,000)

## Strategy
*   **Bucket A (50%) — Global Lead-Lag**: Kinance (Binance) AND KiCom (Crypto.com) harus setuju. Target: +1-3% per trade, Stop: -1.5%, Max 3 posisi.
*   **Bucket B (50%) — Local Indodax-Only**: ConvictionScore 7-layer ≥ 0.85 (pure math, no AI). Target: +3-8% per trade, Stop: -3%, Max 2 posisi.

## Architecture
KINANCE (Binance radar, 8788) ───┬─── AND ────→ KIBOT MANAGER (9998) ────→ KIDAX (8787) → Indodax
KICOM (Crypto.com REST API)      ───┘

## Servers
- Indodax: ubuntu@213.35.118.26
- Binance: ubuntu@152.69.218.198
- Supabase: vptlelbgyxwieyfdpuja.supabase.co

## Files
- `scripts/kibot_engine_v2.py` — Engine utama (TradeLogger, ConvictionScore, dll)
- `scripts/kibot_manager.py` — Python brain + integration
- `packages/core/` — Kotlin business logic
- `state/` — Runtime state (posisi, cascade, trade log)
- `infra/supabase/` — SQL schema

## Services
```bash
sudo systemctl status kidax-engine kinance-engine kibot-manager oracle-keepalive
```
