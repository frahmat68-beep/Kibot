# KiBot Trinity (v5.0)

**Lead-Lag trading system** berbasis microservices untuk market **Indodax** dengan sinyal prediktif dari market global **Binance**. Sistem berjalan di **Oracle Cloud** (Singapore) dengan arsitektur 3-bot (Trinity).

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    ORACLE CLOUD (Singapore)                     │
│                                                                 │
│  ┌─────────────────┐  UDP   ┌─────────────────┐                │
│  │  KIBOT MANAGER  │◄──────►│     KIDAX       │                │
│  │  (Python 🐍)    │        │ (Kotlin/JVM ☕)  │                │
│  │  Port: 9998     │        │ Port: 8787      │                │
│  │  AI Veto Gate   │        │ Indodax Exec    │                │
│  └────────┬────────┘        └─────────────────┘                │
│           │ UDP                                                 │
│           ▼                                                     │
│  ┌─────────────────┐                                           │
│  │    KINANCE      │                                           │
│  │ (Kotlin/JVM ☕)  │                                           │
│  │ Port: 8788      │                                           │
│  │ Binance Radar   │                                           │
│  └─────────────────┘                                           │
└─────────────────────────────────────────────────────────────────┘
```

## Core Trading Logic (v5.0 Overhaul)

### 🛡️ Survival-First Mindset
Sistem tidak lagi menggunakan "Zero-Cash" mindset agresif. Trinity v5.0 fokus pada **modal perlindungan**:
- **Math-First Decision**: Perhitungan EV (Expected Value) mendahului AI.
- **Strict Data Retention**: Rolling history TTL (max 100 samples) untuk menjamin performa di server RAM terbatas.
- **Reliable Symbol Mapping**: Koreksi bug mapping (e.g., `xlm_idr` → `XLMUSDT`) untuk akurasi lead-lag global.

### AI Approval Thresholds (Updated)
Kami telah menaikkan standar kualitas sinyal:
* **Standard**: score ≥ 0.70, expected net ≥ 0.25%
* **Strict**: score ≥ 0.80, expected net ≥ 0.40%
* **Instant approval dilarang total** — semua sinyal wajib melewati Veto Gate.

## Daily Risk Management

| State | PnL Harian | Action |
|-------|------------|--------|
| HEALTHY | > -0.5% | Entry normal semua tier |
| WARNING | -0.5% to -1% | Tier A+B only, size 70% |
| CRITICAL | -1% to -2% | Tier A only, size 40% |
| HARD_STOP | < -2% | Entry blocked, reset 00:00 WIB |

## Modules

| Module | Description |
|--------|-------------|
| `apps/mac-engine` | KiDax/Kinance JVM daemon — Optimized shadow JAR deployment |
| `scripts/kibot_manager.py` | Python veto daemon v5.0 — Math Review & Data Retention logic |
| `infra/supabase` | Redacted Security Credentials guide included |

## Quick Start (Deploy)
```bash
./gradlew :apps:mac-engine:shadowJar
scp apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar ubuntu@213.35.118.26:/home/ubuntu/KiDax/server/
```

## Documentation
- [audit_remediation_plan.md](.gemini/antigravity/brain/fdf984b9-d1ac-4459-b26d-781000048390/audit_remediation_plan.md) — Solusi Audit Temuan Lengkap.
- [.github/copilot-instructions.md](.github/copilot-instructions.md) — Trinity v5.0 AI Agent guidelines.
