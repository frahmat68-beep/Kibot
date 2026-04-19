# KiCryp Manager Runtime Notes

Dokumen ini menjelaskan state ringan yang dipakai `KiCryp manager` supaya keputusan AI dan status live bisa dilihat lagi tanpa menebak-nebak kondisi runtime sebelumnya.

## Jalur File Runtime
- Provider state:
  - `/home/ubuntu/KiCryp/state/ai_provider_state.json`
- Pair cooldown:
  - `/home/ubuntu/KiCryp/state/pair_cooldowns.json`
- Runtime note:
  - `/home/ubuntu/KiCryp/state/runtime_note.json`
- Daily summary:
  - `/home/ubuntu/KiCryp/state/daily_summary.json`

## Isi Penting
- `ai_provider_order`
  - urutan provider efektif yang diprioritaskan router
- `ai_provider_last_status`
  - provider AI terakhir yang sukses/gagal beserta task
- `provider_runtime_state`
  - cooldown, alasan gagal, success/failure count per provider
- `tracked_active_positions`
  - pair aktif terbaru yang sedang diawasi KiCryp
- `pair_cooldowns`
  - pair yang sedang dibekukan sementara karena post-mortem loss atau cooldown lain
- `veto_metrics`
  - hitungan approve/reject/emergency sell untuk audit kualitas keputusan KiCryp
- `sector_preview`
  - ringkasan sector map hasil correlation matrix AI
- `recent_events`
  - event terakhir seperti `ai_provider_success`, `ai_provider_failure`, `emergency_veto_sell`, `active_positions_snapshot`
- `trinity_heartbeat_emit`
  - heartbeat UDP ringan dari `kibot-manager` supaya node lain tidak masuk safe mode karena peer Python commander dianggap hilang
- `lastLeadLagSignalAgeMs`
  - age sinyal lead-lag terakhir yang diproses engine, buat audit freshness feed dan stale signal

## Tuning Live Yang Dipakai
- `KiCryp` tetap ringan sebagai Python daemon, bukan JVM tambahan.
- `KiCryp manager` sekarang juga ikut memancarkan heartbeat `kicryp` ke `KiDax` dan `Kinance` kalau host UDP tujuan diset di service/env.
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
- Jika semua provider masuk cooldown, KiCryp tetap jalan sebagai veto/watchdog dasar, tapi kualitas correlation AI akan turun sementara.
- Cek `daily_summary.json` untuk lihat provider mana yang sering gagal, berapa veto reject, dan pair apa yang sempat masuk blacklist hari ini.
- Untuk server RAM kecil, target aman KiCryp manager adalah tetap di bawah `MemoryMax=192M`.
