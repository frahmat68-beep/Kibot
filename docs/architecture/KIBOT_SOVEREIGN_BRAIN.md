# KiBot Sovereign Brain

## Ringkas

KiBot sekarang didesain sebagai satu kepala sistem untuk tiga node:

- `SG1` executor Indodax
- `SG2` radar/scanner global
- `Batam` brain + Ollama + Polymarket

Tidak ada lagi daemon governor terpisah. Otoritas strategi hidup di `kibot_manager.py`, dengan Ollama Batam sebagai model utama dan fallback heuristik jika AI sedang gagal.

## Prinsip

1. Satu kepala, banyak tangan
2. Kecepatan berpikir harus sesuai urgensi
3. Brain boleh salah, tapi sistem tidak boleh lumpuh

## Loop Sovereign

### Fast Loop

- target: setiap `30s`
- prompt: `STRATEGY_GOVERNOR_FAST`
- model live: `qwen3:1.7b`
- tujuan:
  - cek health live
  - cek active pairs
  - cek perubahan scanner/polymarket
  - putuskan entry boleh/tidak
- output: short-lived plan

### Medium Loop

- target: setiap `5m`
- prompt: `STRATEGY_GOVERNOR_MEDIUM`
- model live: `qwen3:4b`
- tujuan:
  - update posture strategi
  - update aggression mode
  - update focus pairs / focus markets
  - evaluasi memory ringkas
- output: plan yang lebih matang dan tahan lebih lama

### Slow Loop

- target: nightly
- prompt: `SOVEREIGN_DAILY_REVIEW`
- model live: `qwen3:8b`
- tujuan:
  - post-mortem harian
  - missed opportunity review
  - parameter recommendation
  - tomorrow mode / tomorrow focus
- output: sovereign review + pattern library update

## Bentuk Plan

Plan sovereign tersimpan di `state/governor_directives.json` dan minimal berisi:

- `plan_id`
- `plan_generated_at`
- `expires_at`
- `plan_ttl_sec`
- `plan_state`
- `brain_mode`
- `market_regime`
- `capital_posture`
- `confidence`
- `confidence_decay_per_hour`
- `fallback_if_expired`
- `why`
- `what_could_make_this_wrong`
- `ops_alerts`
- `indodax`
- `polymarket`
- `refresh_profile`

## Memory Yang Dipakai Brain

Working memory:

- runtime state
- gate state
- active pairs
- scanner feed
- Polymarket state

Compact history:

- `daily_summary.json`
- `learning_review.json`
- `daily_report.json`
- `pattern_library.json`
- `pair_memory.json`
- `decision_ledger.jsonl`

## Guardrail

Walau KiBot sovereign, eksekusi tetap dibatasi hard guard:

- balance minimum
- stale state
- invalid pair
- position cap
- hard stop harian
- duplicate order / unhealthy control plane

## Implementasi Saat Ini

Yang sudah live di code:

- governor fast/medium loop
- daily sovereign review
- decision ledger
- pattern library
- richer Telegram / runtime status
- Polymarket state yang lebih kaya untuk alpha + maker/rebate analysis
- fallback heuristik kalau AI kosong

Yang sengaja dihapus:

- `kibot_governor.py`
- `kibot-governor.service`

Alasannya: komponen itu membuat brain dobel dan bertabrakan dengan manager sovereign path.
