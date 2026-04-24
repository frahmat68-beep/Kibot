# Ops Update Logs

File indeks ini sengaja dipertahankan supaya pembaca yang mencari `ops_update_logs.md`
tidak salah arah. Log operasional runtime yang wajib di-update ada di:

- [logs/OPS_UPDATE_LOG.md](logs/OPS_UPDATE_LOG.md)

## Rules
- Setiap temuan, patch, deploy, rollback, atau hasil soak wajib dicatat di `logs/OPS_UPDATE_LOG.md`.
- Jangan pecah log operasi ke banyak file berbeda. Pakai satu logbook kanonik supaya agent dan operator tidak split-brain.
- Kalau ada file ini dibuka duluan, tulis update di log kanonik lalu opsional tambahkan pointer singkat di sini.

## RULE: Setiap pembaca WAJIB menulis update di logs/OPS_UPDATE_LOG.md setiap ada temuan, perbaikan, atau deploy, beserta hasil dan claim. Tidak boleh deploy/patch tanpa update log.

## Pointer Terakhir
- Audit/deploy/soak terbaru: lihat entry `2026-04-24 21:36 WIB` di `logs/OPS_UPDATE_LOG.md`.

## [2026-04-20 14:24 WIB] - Hybrid Brain Gate Wired Into Live Entry Path
- **Issue**: Brain sebelumnya hidup, tapi dampaknya ke BUY path masih terlalu lemah; AI belum cukup terlihat mengubah sizing atau memblokir entry lemah.
- **Action**:
  - Tambah `_brain_signal_advisory(...)` di manager.
  - Risk-off / recovery / modal kecil sekarang bisa memblokir pair lemah atau mengecilkan budget.
  - Tambah metrics `entries_blocked.brain` dan `entries_brain_reduced`.
- **Result**:
  - Test offline hybrid pass.
  - Manager sekarang punya jalur advisory nyata tanpa membebani hot path dengan network call.
- **Claim**: Hybrid model `AI strategist -> manager risk governor -> engine executor` sekarang mulai benar-benar hidup di jalur produksi.

## [2026-04-20 14:36 WIB] - SAFE_MODE Health Endpoint Semantics Repaired
- **Issue**: SG aman di hard-stop, tetapi `/api/health` masih `503`, sehingga layer health bisa salah baca node aman sebagai node rusak.
- **Action**:
  - Patch `LocalDashboardServer.kt` supaya `SAFE_MODE` dibalas `200 OK` dengan `status=safe`.
  - Rebuild jar dan redeploy ke SG/Tokyo.
- **Result**:
  - SG health sekarang `200 OK` + `status=safe`.
  - Tokyo health kembali `200 OK` + `status=ok`.
- **Claim**: Semantik health endpoint sekarang selaras dengan status runtime sebenarnya.

## [2026-04-21 03:35 WIB] - Top-Up Separation + Brain Warm-On-Call
- **Issue**: Top-up masih berisiko kebaca sebagai profit karena manager pakai delta equity mentah; snapshot brain juga belum cukup on-call saat mulai stale.
- **Action**:
  - Tambah deteksi `external cashflow` dan keluarkan dari rumus `daily_pnl_pct`.
  - Tambah `BrainManager.ensure_warm(...)` agar refresh snapshot bisa dipicu background tanpa menahan jalur order.
- **Result**:
  - Test offline membuktikan top-up tidak lagi menambah PnL harian palsu.
  - Brain sekarang lebih siap dipanggil kapan pun, tetap lewat snapshot/caching lokal.
- **Claim**: Jalur top-up dan jalur profit sekarang lebih terpisah, dan otak lebih siap-siaga tanpa mengorbankan latency order path.

## [2026-04-20 13:27 WIB] - SAFE_MODE Sync Repair For HP Visibility
- **Issue**: SG engine masih tampil `DEGRADED` saat hard stop valid aktif, sehingga HP/UI terlihat seperti sistem rusak padahal sebenarnya sedang proteksi.
- **Action**:
  - Patch `MacEngineDaemon` agar reason `hard stop active` dikenali sebagai `SAFE_MODE`.
  - Status node `SAFE_MODE` ditampilkan sebagai `safe`, bukan `degraded`.
  - Log misleading `LIFECYCLE_BLOCK` saat hard stop diganti menjadi `SAFE_MODE_HOLD`.
- **Result**:
  - Build lokal lolos dan siap redeploy.
  - Setelah deploy, HP seharusnya membaca node utama sebagai hidup dalam mode aman, bukan down/degraded palsu.
- **Claim**: Sinkronisasi state ke HP menjadi lebih jujur dan selaras dengan guard runtime yang sebenarnya.

## [2026-04-20 12:21 WIB] - Guardian Autorevive Repair
- **Issue**: SG punya gap autorevive nyata; guardian lama bisa skip restart manager/engine saat hard stop aktif.
- **Action**:
  - Guardian dijadikan health-aware, pakai `sd_notify` + watchdog, dan tetap menghidupkan service inti walau hard stop aktif.
  - SG guardian di-enable, proof test restart dilakukan, dan Tokyo dipulihkan setelah transient heartbeat timeout.
- **Result**:
  - `kibot-manager` di SG terbukti bisa hidup lagi otomatis lewat guardian.
  - Tokyo kembali `RUNNING/HEALTHY`; mesh scanner tetap aktif.
  - SG stabil dalam mode proteksi hard stop tanpa entry liar baru.
- **Claim**: Runtime inti 2-server normal kembali; SG sedang aman di hard-stop yang valid, bukan crash.

---
## [2026-04-20] - Trinity v7.2 Resurrection & Discipline
- **Issue**: Auto-Revive broken (systemctl missing), Env race condition, and NoneType crashes.
- **Action**:
    - Refactored Guardian into `kibot_guardian_mac.py` using `psutil`.
    - Consolidated `.env` files and implemented `_init_config()` delayed loading.
    - Added null-safe defaults for capital/equity restoration.
    - Implemented "Atomic Port Scavenger" to prevent address-bind conflicts.
- **Result**: System is fully "Sadar" (Aware) and "Hidup" (Alive). Guardian is monitoring every 15s.
- **Claim**: System is NORMAL, DISCIPLINED, and RECOVERS AUTOMATICALLY.
- **Rules Updated**: Any subsequent issue MUST be logged here with (Issue | Action | Result | Claim).
