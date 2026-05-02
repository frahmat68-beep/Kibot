# KiBot Architecture

## Topology

KiBot berjalan sebagai sistem 3 node:

1. `EXECUTOR` / Indodax executor
   - `kibot-executor-indodax`
   - `kibot-manager`
   - `ki-telegram-monitor`
   - tunnel ke Batam

2. `SCANNER` / global radar
   - `kibot-executor-indodax`
   - `ki-global-scanner-mesh`
   - `kibot-manager` dalam mode scanner/radar
   - support services ops/risk
   - tunnel ke Batam

3. `Batam` / sovereign brain hub
   - `ollama`
   - `kibot-ollama-gateway`
   - `kibot-polymarket`

## Otoritas

- keputusan strategis: `kibot_manager`
- model utama: Ollama di Batam
- fallback saat AI gagal: heuristic sovereign plan
- eksekusi langsung: engine Kotlin di SG

Tidak ada service governor terpisah. Semua brain directive terpusat di manager supaya tidak ada otak ganda.

## Fungsi Tiap Lapisan

Sensor:

- `kibot-executor-indodax`
- `ki_global_scanner_mesh.py`
- market/news/polymarket feeds

Memory:

- `daily_summary.json`
- `learning_review.json`
- `daily_report.json`
- `pattern_library.json`
- `pair_memory.json`
- `decision_ledger.jsonl`

Decision:

- fast/medium/slow sovereign loop di `kibot_manager.py`

Execution:

- `kibot-executor-indodax` untuk Indodax
- `kibot-polymarket.py` untuk Polymarket

Ops/reporting:

- `ki_telegram_monitor.py`
- runtime note + daily report

## Penempatan Beban

Yang harus tetap ringan di SG:

- executor
- manager
- tunnel
- health/risk yang benar-benar lokal

Yang harus berat di Batam:

- Ollama
- sovereign reasoning
- Polymarket research/execution
- nightly review

Prinsipnya: SG tidak boleh bergantung pada RPC berat ke Batam untuk setiap mikro-keputusan. Batam mengirim posture/plan, SG mengeksekusi lokal dengan hard guard.
