# Ops Update Log

Dokumen ini wajib di-update setiap ada temuan, perubahan, deploy, rollback, atau anomali baru.

## Rules
- Tulis satu entry baru setiap kali ada perubahan yang mempengaruhi runtime, log, risk gate, learning, atau deploy.
- Entry wajib mencatat `temuan`, `akar masalah`, `perbaikan`, `verifikasi`, dan `hasil`.
- Jangan hapus entry lama. Jika ada koreksi, tambah entry baru yang merujuk entry sebelumnya.
- Kalau deploy ke server, tulis host yang disentuh, commit/artifact yang dipakai, dan hasil soak minimal 10 menit.
- Kalau ada bug yang belum selesai, tandai jelas sebagai `OPEN` dan jelaskan blast radius-nya.

## Template
```md
### YYYY-MM-DD HH:MM WIB — Nama Singkat
- Status: OPEN | FIXED | MONITORING
- Temuan:
- Akar masalah:
- Perbaikan:
- Verifikasi:
- Hasil:
```

### 2026-04-19 14:20 WIB — Runtime Gate, Parsing, dan State Endpoint Audit
- Status: MONITORING
- Temuan:
  - Node Tokyo `kinance` terus masuk `DEGRADED` walau `BOT_ENABLE_LIVE_EXECUTION=false`.
  - `kibot-manager /api/state` di SG sempat lambat karena melakukan fetch runtime ganda untuk satu response.
  - Parser angka lokal rawan salah baca format `Rp1.200` atau `1.296.999.000`, yang bisa merusak fallback portfolio dan PnL learning.
- Akar masalah:
  - `resolveManagerEntryBlockReason()` tetap memaksa gate manager pada node report-only.
  - `_http_state_payload()` mem-fetch runtime state sekali lalu memicu fetch kedua lewat `_get_total_equity_estimate()`.
  - Parser angka di worktree hanya aman untuk sebagian kecil format locale.
- Perbaikan:
  - Scanner/report-only node sekarang skip manager entry gate untuk health derivation.
  - Manager state endpoint memakai satu fetch runtime + cache ringan + reuse equity extraction.
  - Parsing angka dibuat lebih tahan terhadap grouped integer, mixed locale, dan label IDR.
- Verifikasi:
  - Tambah test untuk report-only Binance node, DecimalValue locale parsing, dan fallback portfolio IDR saat degraded.
  - Akan diverifikasi lagi lewat build/test/deploy/soak 10 menit setelah patch terpasang.
- Hasil:
  - Menunggu verifikasi runtime pascadeploy.
