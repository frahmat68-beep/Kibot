# KiBot Trinity

**High-Frequency Trading (HFT) system** berbasis microservices untuk market **Indodax** dengan sinyal prediktif **Lead-Lag** dari market global **Binance**. Sistem berjalan di **Oracle Cloud** (Singapore) dengan arsitektur 3-bot (Trinity).

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

### The Three Bots

| Bot | Language | Function | Port |
|-----|----------|----------|------|
| **KINANCE** | Kotlin/JVM | Binance market radar — volume anomaly, order book imbalance, sector lead-lag | 8788 |
| **KIDAX** | Kotlin/JVM | Indodax executor — slippage calc, fee optimization, trailing stop | 8787 |
| **KIBOT Manager** | Python | Brain & veto gate — AI consensus, capital rotation, health monitoring | 9998 |

## Core Trading Logic

### Trading Mindset
- **Zero-Cash Mindset** — modal tidak boleh diam, rotasi agresif
- **Predictive, Not Reactive** — beli sebelum terbang (based on Binance signals), bukan setelah
- **Micro-Cap Priority** — fokus koin receh dengan persentase gain tinggi

### Entry Flow
```
PairSelector (11-point scoring) → VetoService (lead-lag check) → 
BotModeDecider (aggression level) → CapitalDeployment (max 25%/coin) → 
LiveExecution (order submit)
```

### Exit Flow (Multi-layer)
- Partial take-profit (30-50% saat profit >0.5%)
- Trailing stop (dynamic % based on volatility)
- Hard stop-loss (2-3% below entry)
- Time-based exit (force close if held >12 hours)
- Emergency sell (AI-triggered on momentum loss)

## Modules

| Module | Description |
|--------|-------------|
| `apps/mac-engine` | KiDax/Kinance JVM daemon — the actual trading engine |
| `packages/core` | Shared business logic — RiskEngine, PairSelector, TradeAutomation |
| `packages/shared-models` | DTOs, enums, serializable payloads |
| `packages/control-plane` | Supabase integration — auth, lease, commands |
| `packages/indodax-client` | Indodax REST adapter |
| `packages/binance-client` | Binance REST adapter |
| `packages/ai-support` | AI integration — GeminiClient, MultiAIClient |
| `packages/test-kit` | Test helpers |
| `scripts/kibot_manager.py` | Python veto daemon (1600+ lines) |
| `infra/supabase` | SQL migrations, RLS |
| `infra/systemd` | Service files for Oracle server |

## AI Integration

Multi-provider AI veto system dengan fallback order:

1. **Groq** — llama-3.1-8b-instant
2. **OpenRouter** — meta-llama/llama-3.1-8b-instruct
3. **Cohere** — command-r
4. **Gemini** — gemini-2.0-flash-lite

Approval thresholds:
- Standard: score ≥ 0.62, expected net ≥ 0.18%
- Instant: score ≥ 0.48, expected net ≥ -0.02%

## Strict Guardrails

**Aturan yang TIDAK BOLEH diubah tanpa instruksi eksplisit:**

1. **NO PANIC SELL ON TIMEOUT** — UDP putus = suspend entry saja, BUKAN market sell
2. **ADAPTIVE TRAILING STOP** — koin <Rp500 diperlebar (3-5%) untuk hindari noise
3. **RATIONAL QUARANTINE** — stop-loss = max 15 menit cooldown, bukan berjam-jam
4. **STRICT TTL** — sinyal UDP >500ms = stale, wajib di-drop
5. **SOFT AI-AUDIT** — AI degraded = warning only, bukan hard veto

## Infrastructure

### Oracle Free Tier (Singapore)
- RAM: 1GB
- CPU: 1/8 OCPU
- JVM tuning: hemat memori, minimal GC pause

### Systemd Services
```bash
sudo systemctl status kidax-engine      # Indodax executor
sudo systemctl status kinance-engine    # Binance radar
sudo systemctl status kibot-manager     # Python veto daemon
```

## Quick Start (Local Development)

1. Install JDK 21
2. Run `scripts/bootstrap_local.sh` to create local secrets scaffolding
3. Run `scripts/check_local_setup.sh` to verify setup
4. Run tests:
   ```bash
   ./gradlew :packages:core:jvmTest :packages:indodax-client:jvmTest :apps:mac-engine:test
   ```
5. Open project in IntelliJ IDEA

## Deployment

### Deploy to Oracle Server
```bash
# Build fat JAR
./gradlew :apps:mac-engine:shadowJar

# Copy to server
scp apps/mac-engine/build/libs/mac-engine-all.jar ubuntu@<server>:/home/ubuntu/KiDax/server/

# Restart services
ssh ubuntu@<server> 'sudo systemctl restart kidax-engine kinance-engine kibot-manager'
```

### Health Check
```bash
curl localhost:8787    # KiDax dashboard
curl localhost:8788    # Kinance dashboard
```

## Documentation

- [setup.md](docs/setup.md) — Local setup guide
- [access-and-secrets.md](docs/access-and-secrets.md) — API keys & secrets management
- [trading-intelligence.md](docs/trading-intelligence.md) — Strategy & scoring logic
- [.github/copilot-instructions.md](.github/copilot-instructions.md) — AI agent guidelines
