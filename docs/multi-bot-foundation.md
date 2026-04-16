# Multi-Bot Foundation

KiCryp sekarang disiapkan untuk menjalankan beberapa bot dengan logic inti yang sama tetapi runtime terpisah.

## Prinsip

- Satu Supabase
- Satu codebase logic
- Satu app/web overview
- Banyak runtime bot terpisah

## Identity yang dipisah

- `BOT_ID`
- `DEVICE_ID`
- `MAC_ENGINE_PORT`
- `KICRYP_RELEASE_LABEL`
- AI config per bot
- credential exchange per bot

## Runtime profile

Kalau `BOT_PROFILE_KEY` tidak diisi, loader akan membuat profile key dari `BOT_ID`.

Contoh:

- `BOT_ID=main` -> profile `indodax`
- `BOT_ID=binance-main` -> profile `binance-main`

## Path runtime yang sudah di-scope

- adaptive AI policy:
  - `.tmp/ai-audits/<profile>/latest/adaptive_policy.json`
- target enforcement memory:
  - `.tmp/runtime/<profile>/target_enforcement_memory.json`

Loader akan mencoba migrasi otomatis dari path legacy lama ke path scoped baru saat file scoped belum ada.

## Saran naming nanti

- Indodax:
  - `BOT_ID=indodax-main`
  - `BOT_PROFILE_KEY=indodax`
  - `DEVICE_ID=oracle-indodax-main`
  - `MAC_ENGINE_PORT=8787`

- Binance:
  - `BOT_ID=binance-main`
  - `BOT_PROFILE_KEY=binance`
  - `DEVICE_ID=oracle-binance-main`
  - `MAC_ENGINE_PORT=8788`
