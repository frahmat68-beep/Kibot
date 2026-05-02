# Three-Node Topology

## Node Roles

### EXECUTOR

- role: executor
- primary duty: Indodax order execution
- mandatory services:
  - `kibot-executor-indodax`
  - `kibot-manager`
  - `ki-telegram-monitor`
  - `kibot-ollama-tunnel`
  - `kibot-polymarket-tunnel`

### SCANNER

- role: radar
- primary duty: global scan + upstream signal fabric
- mandatory services:
  - `kibot-executor-indodax`
  - `ki-global-scanner-mesh`
  - `kibot-manager`
  - `kibot-ollama-tunnel`
  - `kibot-polymarket-tunnel`

### Batam

- role: sovereign brain hub
- primary duty:
  - Ollama
  - Polymarket
  - AI planning/review
- mandatory services:
  - `ollama`
  - `kibot-ollama-gateway`
  - `kibot-polymarket`

## Placement Rules

- heavy AI reasoning pindah ke Batam
- SG hanya simpan hot path execution
- sidecar yang tidak kritis untuk trading boleh dimatikan di SG kalau RAM sempit
- jangan jadikan Batam sebagai blocking dependency per tick; gunakan plan/tunnel/fallback

## Services Removed

- `kibot-governor.service`
- legacy Telegram listener stack

Semua otoritas keputusan sekarang lewat `kibot-manager`.
