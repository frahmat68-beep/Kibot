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

### 2026-04-19 14:55 WIB — Deploy 2 Server, Soak 10 Menit, dan Audit Penutup
- Status: FIXED
- Temuan:
  - Node Tokyo `kinance` sebelumnya terjebak loop `DEGRADED` karena node scanner-only tetap ikut memproses manager gate lokal yang sedang `SUSPENDED`.
  - Endpoint `kibot-manager /api/state` di SG sebelumnya rawan lambat/timeout karena satu response memicu fetch runtime ganda.
  - Parsing angka `DecimalValue` dan fallback label IDR berisiko salah baca format grouped integer atau scientific notation, yang bisa merusak equity/PnL baseline.
  - Test heartbeat guard sempat salah skenario sehingga panic-sell regression tidak teruji dengan benar.
  - Checklist deploy repo drift dari kondisi server live: wording heartbeat masih menyiratkan panic sell dan timeout masih tertulis `500ms`, padahal unit live memakai `5000ms`.
- Akar masalah:
  - `resolveManagerEntryBlockReason()` belum membedakan node executor vs report-only/scanner-only.
  - Manager state payload masih melakukan readback runtime berulang.
  - Normalisasi angka terlalu agresif dan tidak memprioritaskan parse numerik native.
  - Fixture test heartbeat memakai quote helper default yang menghasilkan profit palsu besar.
  - Dokumentasi operasional tertinggal dari konfigurasi systemd aktif.
- Perbaikan:
  - Runtime gate diperketat: node report-only/shadow tidak lagi ikut terseret manager gate untuk health derivation.
  - `kibot_manager.py` sekarang memakai cache ringan + reuse runtime snapshot untuk payload state/equity.
  - Parser angka diperkeras untuk locale campuran, grouped IDR, dan scientific notation.
  - Test heartbeat safe-mode distabilkan agar benar-benar menguji `suspend entry without panic sell`.
  - Checklist deploy dirapikan agar konsisten dengan guardrail live (`no panic sell on heartbeat timeout`, timeout `5000ms`).
- Verifikasi:
  - Local:
    - `python3 tests/test_whatif_complete.py` → `200/200 pass`
    - `python3 scripts/trinity_production_test.py` → `ALL SYSTEMS GREEN`
    - `./gradlew :packages:shared-models:jvmTest --tests com.kibot.shared.models.DecimalValueTest ... :apps:mac-engine:fatJar --no-daemon`
    - `./gradlew :apps:mac-engine:test --tests "com.kibot.macengine.MacEngineDaemonTest.missing trinity heartbeat suspends entry without panic sell" --tests "com.kibot.macengine.MacEngineDaemonTest.report only binance node ignores manager gate availability for health state" --no-daemon`
  - Deploy manual SSH:
    - Host disentuh: `213.35.118.26` dan `152.69.218.198`
    - Repo head saat push: `e201dd1c`
    - Artifact deployed: `482067af14bc5eb4f050e219d610bf6f9b9decc5f699c242a126fb09eed83955`
    - Files deployed: `server/mac-engine-all.jar`, `scripts/kibot_manager.py`
    - Restart: `kibot-manager` + `kidax-engine` (SG), `kibot-manager` + `kinance-engine` (Tokyo)
  - Soak:
    - SG `213.35.118.26`: setelah boot recovery, `kidax-engine` menetap di `SAFE_MODE` dengan `tradingAllowed=false` dan `hardStopActive=true`; tidak ada `EXECUTION_BUY` baru selama soak.
    - Tokyo `152.69.218.198`: `kinance-engine` menetap `RUNNING/HEALTHY`, `tradingAllowed=false` by design, dan loop `DEGRADED` lama berhenti setelah restart baru.
- Hasil:
  - Topologi aktif 2 server kembali sinkron sesuai peran: SG sebagai executor yang patuh hard-stop, Tokyo sebagai scanner/radar yang sehat tanpa terseret suspend manager lokal.
  - Tidak ada bukti BUY/Sell liar baru selama soak pascadeploy.
  - Repo sekarang punya logbook operasi wajib + checklist deploy yang lebih jujur terhadap runtime aktif.
