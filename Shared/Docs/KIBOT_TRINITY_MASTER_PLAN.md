# KiBot Trinity: Sovereign Trading Architecture (Final)

## 1. Vision & Philosophy
- **Identity**: Single Entity "KiBot" (Removed legacy KiDax/SG nomenclature).
- **Motto**: "Tekan Kerugian, Maksimalkan Probabilitas Keuntungan".
- **Strategy**: Smart Frequency Trading & Compounding Growth (Sedikit demi sedikit lama-lama jadi bukit).

## 2. Infrastructure Layer (The Trinity)

### Node A: BATAM (The Sovereign Brain & Hub)
- **Role**: Command Center, AI Analysis, Consensus Veto, Dashboard Host.
- **Key Services**:
    - `kibot-manager`: Central decision engine (Port 9998 UDP).
    - `kibot-ollama-gateway`: AI Analysis tunnel (Port 11435).
    - `kibot-analyst`: Deep market research module.
    - `kibot-dashboard`: Real-time monitoring UI (Port 8787).
- **Communication**: Receives raw signals from Scanner, processes them, and relays approved orders to Executor.

### Node B: SINGAPORE-1 (The EXECUTOR ENGINE)
- **Role**: Direct Market Execution (Exchange Interface).
- **IP**: `100.122.1.109` (Tailscale).
- **Key Services**:
    - `kibot-executor-engine`: Java-based high-frequency execution (Port 9999 UDP).
- **Input**: Only accepts "APPROVED" signals from Batam Hub.

### Node C: SINGAPORE-2 (The GLOBAL SCANNER)
- **Role**: Market Data Ingestion & Signal Generation.
- **IP**: `100.105.139.21` (Tailscale).
- **Key Services**:
    - `kibot-scanner@mesh`: Aggregated multi-exchange scanner.
    - `kibot-scanner@okx`, `@mexc`, etc.: Dedicated exchange feeders.
- **Output**: Sends raw UDP signals to Batam Hub (Port 9998).

## 3. Connectivity & Security
- **Private Network**: All inter-node communication uses **Tailscale (100.x.x.x)**. No public ports exposed for trading data.
- **Ports Matrix**:
    - `9998 UDP`: Batam Hub (Incoming from Scanner).
    - `9999 UDP`: Executor SG1 (Incoming from Batam).
    - `11435 TCP`: AI Gateway (Local Batam).
- **Firewall**: Iptables configured to only allow Tailscale traffic on critical trading ports.

## 4. Logic & Flow
1. **Scanner (SG2)** detects price movement -> Sends UDP to **Batam**.
2. **Batam (Manager)** receives signal -> Validates via **Consensus Whiteboard**.
3. **AI Veto (Ollama)** analyzes pair sentiment and probability.
4. **Risk Ladder** checks equity and compounding state.
5. If **APPROVED** -> Batam sends encrypted UDP command to **Executor (SG1)**.
6. **Executor (SG1)** opens position on exchange.

## 5. Directory Structure (Standardized)
- `/home/ubuntu/KiBot/Batam/Brain Control/`: Core logic.
- `/home/ubuntu/KiBot/Batam/Web/`: Dashboard source.
- `/home/ubuntu/KiBot/Shared/Docs/`: Architecture & Role manifests.
- `/home/ubuntu/KiBot/Shared/Ops/`: Centralized environment configs.
- `/home/ubuntu/KiBot/logs/`: Unified logging directory.

---
*Last Updated: 2026-05-02*
*Author: Antigravity AI (KiBot Architect)*
