# Ops Update Logs

File indeks ini sengaja dipertahankan supaya pembaca yang mencari `ops_update_logs.md`
tidak salah arah. Log operasional runtime yang wajib di-update ada di:

- [logs/OPS_UPDATE_LOG.md](logs/OPS_UPDATE_LOG.md)

## Rules
- Setiap temuan, patch, deploy, rollback, atau hasil soak wajib dicatat di `logs/OPS_UPDATE_LOG.md`.
- Jangan pecah log operasi ke banyak file berbeda. Pakai satu logbook kanonik supaya agent dan operator tidak split-brain.
- Kalau ada file ini dibuka duluan, tulis update di log kanonik lalu opsional tambahkan pointer singkat di sini.

## Pointer Terakhir
- Audit/deploy/soak terbaru: lihat entry `2026-04-19 23:34 WIB` di `logs/OPS_UPDATE_LOG.md`.
