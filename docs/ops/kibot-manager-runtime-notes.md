# KiBot Manager Runtime Notes

Dokumen ini menjelaskan state ringan yang dipakai `KiBot manager` supaya keputusan AI dan status live bisa dilihat lagi tanpa menebak-nebak kondisi runtime sebelumnya.

## Jalur File Runtime
- Provider state:
  - `/home/ubuntu/KiBot/state/ai_provider_state.json`
- Pair cooldown:
  - `/home/ubuntu/KiBot/state/pair_cooldowns.json`
- Runtime note:
  - `/home/ubuntu/KiBot/state/runtime_note.json`
- Daily summary:
  - `/home/ubuntu/KiBot/state/daily_summary.json`

## Isi Penting
- `ai_provider_order`
  - urutan provider efektif yang diprioritaskan router
- `ai_provider_last_status`
  - provider AI terakhir yang sukses/gagal beserta task
- `provider_runtime_state`
  - cooldown, alasan gagal, success/failure count per provider
- `tracked_active_positions`
  - pair aktif terbaru yang sedang diawasi KiBot
- `pair_cooldowns`
  - pair yang sedang dibekukan sementara karena post-mortem loss atau cooldown lain
- `veto_metrics`
  - hitungan approve/reject/emergency sell untuk audit kualitas keputusan KiBot
- `sector_preview`
  - ringkasan sector map hasil correlation matrix AI
- `recent_events`
  - event terakhir seperti `ai_provider_success`, `ai_provider_failure`, `emergency_veto_sell`, `active_positions_snapshot`
- `trinity_heartbeat_emit`
  - heartbeat UDP ringan dari `kibot-manager` supaya node lain tidak masuk safe mode karena peer `kibot` dianggap hilang

## Tuning Live Yang Dipakai
- `KiBot` tetap ringan sebagai Python daemon, bukan JVM tambahan.
- `KiBot manager` sekarang juga ikut memancarkan heartbeat `kibot` ke `KiDax` dan `Kinance` kalau host UDP tujuan diset di service/env.
- Default router fokus ke 4 provider:
  - `groq`
  - `openrouter`
  - `cohere`
  - `gemini`
- `AI confidence gate` ikut mem-filter approval sinyal:
  - pair tidak boleh lolos jika net expectation dan confidence AI masih terlalu lemah
- `Post-mortem blacklist` bisa membekukan pair yang baru bikin loss agar bot tidak balas dendam masuk lagi terlalu cepat
- Provider yang gagal akan masuk cooldown otomatis supaya bot tidak buang waktu ke endpoint rusak/rate-limited.
- Provider yang paling sering sukses akan naik prioritas otomatis di routing berikutnya.

## Catatan Operasional
- Jika `runtime_note.json` berhenti update padahal `kibot-manager.service` hidup, curigai loop AI/router macet.
- Jika semua provider masuk cooldown, KiBot tetap jalan sebagai veto/watchdog dasar, tapi kualitas correlation AI akan turun sementara.
- Cek `daily_summary.json` untuk lihat provider mana yang sering gagal, berapa veto reject, dan pair apa yang sempat masuk blacklist hari ini.
- Untuk server RAM kecil, target aman KiBot manager adalah tetap di bawah `MemoryMax=192M`.
