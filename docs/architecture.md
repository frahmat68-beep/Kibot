# Architecture

## High Level — TRINITY System

Trinity adalah sistem High-Frequency Trading (HFT) otomatis berbasis Microservices yang ditulis menggunakan Kotlin (JVM). Sistem ini terdiri dari 3 bot terpisah yang berkomunikasi via UDP untuk sub-ms latency:

### The Three Nodes

1. **KINANCE (The Predictive Radar)** — Binance Server
   - Mengawasi pergerakan market global di Binance
   - Mendeteksi Volume Anomaly, Imbalance Order Book, Sector Lead-Lag
   - Mengirim sinyal UDP ke KiDax/KiBot dengan latensi sangat rendah
   - Mode PEKA: mendeteksi Bandar Ignition sebelum pump di Indodax

2. **KIDAX (The Executioner)** — Indodax Server
   - Eksekutor Buy/Sell langsung di market Indodax
   - Menghitung slippage, optimalisasi fee (Maker/Taker)
   - Trailing Stop dengan adaptive threshold untuk micro-caps

3. **KIBOT Manager (The Brain)** — Indodax Server (Python)
   - Manajer stabilitas, capital rotation, dan VETO eksekusi
   - AI-powered approval via Groq/OpenRouter/Cohere/Gemini
   - Post-mortem learning untuk continuous improvement
   - Heartbeat monitoring untuk Trinity health

## Safety Model

- Only one lease holder may trade at a time.
- Lease ownership is protected by monotonically increasing `term`.
- Trade writes require both a valid lease and a short-lived execution action reservation.
- Conflict or ambiguous exchange state forces `SAFE_MODE`.
- UDP signals older than 500ms are dropped (stale data protection).
- NO PANIC SELL on heartbeat timeout — only suspend new entries.

## Infrastructure

- **Indodax Server** (Oracle Cloud, 1GB RAM): KiDax + KiBot Manager
- **Binance Server** (Oracle Cloud, 1GB RAM): Kinance only (scanner mode)
- Communication: UDP broadcasting for signals, Supabase for control plane

## Runtime Split

- `apps/mac-engine`
  JVM daemon for KiDax and Kinance trading engines.
- `packages/shared-models`
  Serializable transport models.
- `packages/core`
  Lease rules, health rules, pair selection, risk, reconciliation.
- `packages/control-plane`
  Supabase auth, polling snapshot client, RPC wrappers.
- `packages/indodax-client`
  Exchange adapter, signed REST helpers.
- `scripts/kibot_manager.py`
  Python AI veto daemon with multi-provider LLM support.
- `infra/supabase`
  SQL schema, RLS, RPC functions, cleanup policy.

## Trading Philosophy

- **Zero-Cash Mindset**: Modal tidak boleh diam — rotate aggressively
- **Predictive, Not Reactive**: Beli sebelum pump di Indodax (signal dari Binance)
- **Micro-Cap Priority**: Fokus koin murah (< Rp500) untuk ROI maksimal
- **Adaptive Trailing Stop**: Widen stop for micro-caps to avoid noise exits
