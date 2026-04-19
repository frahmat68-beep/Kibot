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

### 2026-04-19 22:27 WIB — Brain Advisory Hardening, Path Cleanup, dan Soak Manager 10 Menit
- Status: FIXED
- Temuan:
  - Worktree lokal sempat berisi integrasi `ki_brain.py` yang memanggil web/LLM research sinkron langsung dari `_can_enter()`.
  - Modul `ki_brain.py` / `ki_stats.py` awal bergantung pada paket yang tidak tersedia default (`google.generativeai`, `tavily`, `duckduckgo_search`, `finnhub`, `numpy`, `pandas`), sehingga rawan membuat manager gagal start jika langsung dideploy.
  - Ada split-brain dokumentasi antara `ops_update_logs.md` dan `logs/OPS_UPDATE_LOG.md`.
  - Beberapa doc dan script operasional masih menunjuk path lama `KiDax/` / `Kinance/`, padahal topologi aktif sekarang memakai `/home/ubuntu/KiBot`.
- Akar masalah:
  - Brain assist baru dipasang di hot path trading, bukan di jalur advisory/review.
  - Integrasi baru tidak mematuhi prinsip dependency-light untuk runtime manager.
  - Logbook operasi tidak punya satu sumber kebenaran yang jelas.
  - Dokumen deploy/recovery belum seluruhnya mengikuti penempatan server aktif terbaru.
- Perbaikan:
  - Brain assist dipindah jadi advisory-only background loop; jalur entry live sekarang hanya memakai stat sanity check lokal (`STATS_REJECT`) dan tidak lagi menunggu web research.
  - `ki_brain.py` ditulis ulang jadi ringan berbasis stdlib/`urllib` + timeout ketat; `ki_stats.py` ditulis ulang ke stdlib tanpa `numpy/pandas`.
  - Manager sekarang mengekspos `brain_assist` ke `runtime_note.json` dan `/api/state`.
  - `ops_update_logs.md` dijadikan pointer ke logbook kanonik `logs/OPS_UPDATE_LOG.md`.
  - Doc dan script operasional aktif dirapikan ke root `/home/ubuntu/KiBot`.
- Verifikasi:
  - Local:
    - `python3 -m py_compile scripts/kibot_manager.py scripts/ki_brain.py scripts/ki_stats.py scripts/test_brain_integration.py`
    - `python3 scripts/test_brain_integration.py`
    - `python3 tests/test_whatif_complete.py`
    - `python3 scripts/trinity_production_test.py`
  - Deploy:
    - Push head: `8475aa82`
    - Files deployed manual via SSH ke dua server: `scripts/kibot_manager.py`, `scripts/ki_brain.py`, `scripts/ki_stats.py`
    - Service restarted: `kibot-manager` pada `213.35.118.26` dan `152.69.218.198`
  - Soak 10 menit:
    - SG: manager tetap `SUSPENDED` karena `daily_loss_limit_hit`, `brain_assist` tampil normal di `/api/state`, executor tetap `tradingAllowed=false`/`hardStopActive=true`, tidak ada `EXECUTION_BUY` baru.
    - Tokyo: manager tetap `SUSPENDED` karena `math_review_recovery_impossible`, `brain_assist` tampil normal di `/api/state`, `kinance-engine` tetap `RUNNING/HEALTHY`.
- Hasil:
  - Otak sistem tetap hidup, sadar internet, dan terlihat di state API, tetapi tidak lagi mengganggu jalur order live.
  - Topologi aktif tetap disiplin: SG menjaga modal lewat hard-stop, Tokyo tetap sehat sebagai radar/scanner, dan tidak muncul traceback baru dari manager pascadeploy.
  - Penempatan operasional aktif sekarang lebih konsisten antara repo, script deploy, dan server live.

### 2026-04-19 23:34 WIB — Legacy PnL Repair, Analyst Sync, dan Soak Final 10 Menit
- Status: FIXED
- Temuan:
  - Log trade hari ini masih mengandung SELL yang tertulis seperti untung padahal `netPnlPct`/`netPnlIdr` negatif, dan banyak fill market tercatat `filledPrice=0.0`.
  - `kibot-analyst` sebelumnya membaca log yang salah (`state/analyst/trade_log.jsonl`), sehingga ringkasan harian bisa kosong/template walau trade live sudah terjadi.
  - `math_review` di manager bisa salah menghitung `trades_to_recover = inf` saat loss hari itu sebenarnya nol, lalu masuk loop `math_review_recovery_impossible`.
  - Jalur `record_trade(...)` ke learning engine punya mismatch signature tersembunyi, sehingga event fill berisiko gagal masuk ke otak belajar.
- Akar masalah:
  - Parser lama terlalu permisif: string alasan seperti `forced sell ... at 33.36%` bisa dianggap PnL walau itu bukan field `pnl=...%`.
  - Historis log live hari ini sudah terkorup oleh fill price nol, jadi summarizer lama membaca data mentah yang salah.
  - Rumus review matematika tidak memisahkan kondisi `current_loss_idr <= 0`.
  - `kibot_learning_engine.record_trade()` belum menerima metadata `used_limit_order` yang sudah dikirim manager.
- Perbaikan:
  - Disiplin parser diperketat di manager, learning engine, dan analyst:
    - hanya percaya `pnl=...%`
    - masih menerima pola `at -...%` untuk loss legacy
    - mengabaikan pola `at +...%` generik yang sering menipu.
  - `kibot_analyst.py` sekarang memakai log kanonik `state/trade_log.jsonl`, tetap merge file legacy analyst bila ada, lalu normalisasi ulang PnL pct/IDR untuk record historis yang rusak.
  - `TradeLogger.kt` dikeraskan agar fill price/nilai nol di masa depan difallback ke `requestedPrice` dan `price * amount`, plus mirror log ke path analyst agar tidak split-brain lagi.
  - `math_review` diperbaiki supaya loss nol tidak memicu `need inf`/`recovery impossible`.
  - `record_trade(...)` dibuat backward-compatible dengan argumen metadata dari manager.
  - `ki_brain.py` optional dependency probe dibungkus aman supaya tidak memunculkan warning namespace `google` di runtime.
- Verifikasi:
  - Local:
    - `python3 -m py_compile scripts/kibot_manager.py scripts/kibot_learning_engine.py scripts/kibot_analyst.py scripts/ki_brain.py scripts/test_offline.py`
    - `python3 scripts/test_offline.py` → `RESULT 21 PASS 0 FAIL`
    - `python3 tests/test_whatif_complete.py` → `200/200`
    - `python3 scripts/trinity_production_test.py` → `ALL SYSTEMS GREEN`
    - `./gradlew :apps:mac-engine:compileKotlin :apps:mac-engine:fatJar --no-daemon`
  - Deploy:
    - Push head berurutan: `c705fdb0`, `82c8c2b0`, `56a56a5d`
    - Host disentuh: `213.35.118.26` dan `152.69.218.198`
    - Artifact deployed: `8c72dee8c6cb457bff750fa21f25ef5f6fe799d089f37dae87401f502be935ae`
    - Files deployed manual via SSH: `server/mac-engine-all.jar`, `scripts/kibot_manager.py`, `scripts/kibot_learning_engine.py`, `scripts/kibot_analyst.py`, `scripts/ki_brain.py`
    - Restart:
      - SG: `kibot-manager`, `kibot-analyst`, `kidax-engine`
      - Tokyo: `kibot-manager`, `kibot-analyst`, `kinance-engine`
  - Soak 10 menit:
    - SG `213.35.118.26`: manager tetap `SUSPENDED` karena `daily_loss_limit_hit`, engine `tradingAllowed=false` dan `hardStopActive=true`, tidak ada `EXECUTION_BUY` baru selama soak.
    - SG analyst summary tidak lagi `no_trades`, tetapi membaca 14 trade hari ini dengan total PnL yang selaras ke arah rugi, bukan template untung palsu.
    - Tokyo `152.69.218.198`: false loop `need inf` / `Recovery too far` tidak muncul lagi; `kinance-engine` tetap `RUNNING/HEALTHY`.
    - Tidak ada warning baru dari `ki_brain.py` sesudah patch probe dependency.
- Hasil:
  - Jalur hitung untung/rugi, review matematika, analyst summary, dan feed learning sekarang jauh lebih jujur terhadap kondisi trade live.
  - Sistem tetap disiplin: SG menahan entry sampai reset harian berikutnya, sementara patch logger/analyst yang baru membuat trade hari berikutnya tercatat lebih bersih dan lebih bisa dipelajari.
  - Topologi aktif 2 server kembali normal dalam mode proteksi yang benar, bukan normal palsu karena salah baca log.
