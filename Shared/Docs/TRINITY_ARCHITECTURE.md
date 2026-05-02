# 🔱 KIBOT TRINITY ARCHITECTURE (v7.3.2)

Arsitektur 3-node yang didesain untuk **Keamanan Maksimal**, **Latensi Rendah**, dan **Efisiensi RAM**.
# 🛡️ KiBot Trinity v7.3.5: Mesh Architecture

The KiBot Trinity infrastructure is a distributed 3-node cluster operating over a **Tailscale Mesh VPN**. This ensures zero-trust security and stable 100ms-roundtrip latency bypass.

## 📡 Node Inventory (Private Mesh)

| Node | Tailscale IP | Role | Location |
| :--- | :--- | :--- | :--- |
| **Batam** | `100.103.77.10` | **Brain / Manager / Redis Hub** | Batam, ID |
| **SCANNER** | `100.105.139.21` | **Scanner / Radar (Binance)** | Singapore, SG |
| **EXECUTOR** | `100.122.1.109` | **Executor (KiBot / OKX)** | Singapore, SG |

## ⚙️ Core Infrastructure Services

1.  **Tailscale VPN**: Encrypted P2P mesh network. No public ports required.
2.  **Redis (Batam)**: Centralized state store for heartbeats and trade signals.
3.  **Vector Logging**: Centralized log sink on Batam receiving streams from Tokyo/SG via Tailscale.
4.  **Uptime Kuma**: Visual monitoring dashboard at `http://100.103.77.10:3001`.
5.  **Kibot Guardian**: Self-healing sentinel for disk and backup maintenance.

## 🛰️ NODES & ROLES

### 1. Node Batam (Sentinel / Manager)
- **Spek**: 24GB RAM (OCI Ampere).
- **Peran**: Pusat kendali, Database (Supabase), AI Coordinator (Ollama).
- **Service Utama**: `kibot-manager`, `netdata` (Parent).

### 2. Node SCANNER (Scanner / Radar)
- **Spek**: 1GB RAM.
- **Peran**: Mendeteksi anomali harga global.
- **Service Utama**: `ki-global-scanner-mesh`, `kibot-executor-indodax`.
- **Status**: LEAN MODE (Service admin dimatikan).

### 3. Node EXECUTOR (Executor)
- **Spek**: 1GB RAM.
- **Peran**: Eksekusi transaksi ke bursa lokal (Indodax).
- **Service Utama**: `kibot-executor-indodax`.
- **Status**: LEAN MODE (Service admin dimatikan).

## 🔒 SECURITY & TUNNELS
Semua komunikasi antar-server menggunakan **SSH Tunnels** untuk menghindari port terbuka ke publik:
- **Port 8787**: Bridge Equity EXECUTOR ➡️ Batam.
- **Port 8788**: Bridge Equity SCANNER ➡️ Batam.
- **Port 19999**: Monitoring Streaming ➡️ Batam.

---
*Last Hardened: 2026-05-01 by Antigravity*
