# Two-Bot Direction

## Runtime Separation
- Server A: Indodax runtime only
- Server B: Binance Spot runtime only
- No shared exchange credentials, runtime files, or service names
- One Supabase control plane, but distinct `BOT_ID`, `DEVICE_ID`, ports, and profile keys

## Shared Logic
- One codebase for strategy, target pursuit, rotation, watchdog, and reporting contracts
- Exchange-specific adapters live in separate packages
- One branch/source of truth remains enough for now; separation happens at deploy/runtime, not at source branch level
- `KiDax` and `Kinance` share the same aggressive trading philosophy, but may differ in AI stack and exchange-specific thresholds

## Reporting Brain
- One reporting/overview brain aggregates the two bot states
- Main surfaces later:
  1. Overview gabungan
  2. Indodax detail
  3. Binance detail

## UI Direction
- Web/app remain single product
- Navbar/view switch between overview, Indodax, Binance
- Widgets/notifications default to combined overview

## Safety Rules
- Do not deploy Binance work onto Indodax server
- Do not reuse Indodax env files for Binance
- Keep SSH keys in exchange-specific folders only
- Keep KiDax and Kinance in separate remote roots, service names, ports, and runtime profile keys
- Treat KiCryp as the future reporting brain only; it must not silently inherit live trading credentials from either exchange bot

## Lead-Lag Callout
- Kinance emits breakout callout to KiDax over UDP (private IP) when configured.
- Callout is throttled by cooldown (`KIBOT_LEAD_LAG_SIGNAL_COOLDOWN_MS`) to prevent spam.
- KiDax accepts callout within TTL (`KIBOT_LEAD_LAG_SIGNAL_TTL_MS`) and immediately boosts that pair into decision hints.
- If `KIBOT_LEAD_LAG_FORCE_ROTATION_ON_RECEIVE=true`, KiDax is allowed to bypass full-slot hesitation for the callout pair so momentum is not missed.
- If UDP delivery fails, Kinance automatically falls back to control-plane `command_queue` (`SYNC_NOW` payload).
