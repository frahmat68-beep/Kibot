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
- Audit/deploy/soak terbaru: lihat entry `2026-04-25 08:47 WIB` di `logs/OPS_UPDATE_LOG.md`.

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
