# Server Deploy Checklist

Dokumen ini adalah checklist deploy untuk topologi aktif `main` / `kinance` / `kibot` setelah logic chart-adaptive, heartbeat UDP, dan local recovery aktif.

## Status Deploy
- Build runtime utama sudah tervalidasi lewat `MacEngineDaemonTest`.
- Core strategy/risk/chart tests juga sudah hijau.
- Jalur yang sudah diverifikasi langsung:
  - breakout chart entry
  - hyper entry + trailing exit
  - heartbeat timeout -> safe mode + emergency sell
  - local position snapshot -> startup recovery fallback

## Active Systemd Set
- Node A / `213.35.118.26`
  - wajib aktif: `kidax-engine`, `kibot-manager`, `kibot-analyst`
- Node B / `152.69.218.198`
  - wajib aktif: `kinance-engine`, `kibot-manager`, `kibot-analyst`, `kibot-notifier`, `kibot-guardian`, `kibot-auditor`, `kibot-orchestrator`, `kibot-security`
- `kibot_local_signal.py` sekarang dijalankan dari thread internal `kibot-manager`, bukan dari service `kibot-local-scanner.service`
- AI routing / learning review utama sekarang dimiliki `kibot-manager`; service `kibot-coordinator.service` adalah legacy dan harus tetap mati pada topologi aktif 2 server

## Artefak Build
- Fat jar runtime:
  - `./gradlew :apps:mac-engine:fatJar`
- Output:
  - `apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar`

## Env Minimum

### Shared
- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `SUPABASE_USER_EMAIL`
- `SUPABASE_USER_PASSWORD`
- `MAC_ENGINE_BIND_HOST=0.0.0.0`
- `MAC_ENGINE_ENABLE_LAN_ADVERTISE=false`
- `BOT_ENABLE_LIVE_EXECUTION=true`
  Untuk node executor live seperti `main` / `kidax`.
- `KIBOT_LOCAL_POSITION_STATE_ENABLED=true`
- `KIBOT_MONTHLY_PNL_ANCHOR_PATH=/home/ubuntu/KiBot/state/monthly_pnl_anchor.json`
- `KIBOT_LEAD_LAG_UDP_ENABLED=true`
- `KIBOT_LEAD_LAG_UDP_HEARTBEAT_ENABLED=true`
- `KIBOT_LEAD_LAG_UDP_HEARTBEAT_INTERVAL_MS=100`
- `KIBOT_LEAD_LAG_UDP_HEARTBEAT_TIMEOUT_MS=500`

### KiBot Manager Ringan
- gunakan `kibot-manager.service` Python untuk commander ringan jika RAM server tipis
- `KIBOT_MANAGER_STATE_DIR=/home/ubuntu/KiBot/state`
- `KIBOT_MANAGER_PROVIDER_STATE_FILE=/home/ubuntu/KiBot/state/ai_provider_state.json`
- `KIBOT_MANAGER_RUNTIME_NOTE_FILE=/home/ubuntu/KiBot/state/runtime_note.json`
- `KIBOT_MANAGER_HEARTBEAT_INTERVAL_SEC=0.10`
- `KIDAX_UDP_HOST=213.35.118.26`
- `KINANCE_UDP_HOST=152.69.218.198`
- `KIBOT_AI_PROVIDER_ORDER=groq,openrouter,cohere,gemini`
- `KIBOT_AI_REQUEST_TIMEOUT_SEC=12`
- `KIBOT_AI_PROVIDER_DEFAULT_COOLDOWN_SEC=600`
- `KIBOT_AI_PROVIDER_NETWORK_COOLDOWN_SEC=180`
- `KIBOT_AI_PROVIDER_RATE_LIMIT_COOLDOWN_SEC=3600`
- `KIBOT_AI_PROVIDER_EMPTY_COOLDOWN_SEC=120`
- `KIBOT_AI_APPROVAL_MIN_SCORE=0.65`
- `KIBOT_AI_APPROVAL_MIN_EXPECTED_NET_PCT=0.008`
- `KIBOT_AI_APPROVAL_INSTANT_MIN_SCORE=0.55`
- `KIBOT_AI_APPROVAL_INSTANT_MIN_EXPECTED_NET_PCT=0.005`
- `KIBOT_INDODAX_TAKER_FEE=0.003`
- `KIBOT_MAKER_FEE=0.0015`
- `KIBOT_ROUND_TRIP_TAKER_COST=0.006`
- `KIBOT_ROUND_TRIP_MAKER_COST=0.003`
- `KIBOT_SLIPPAGE_BUFFER=0.002`
- `KIBOT_MIN_GROSS_PROFIT_TARGET=0.011`
- `KIBOT_PARTIAL_TP_TRIGGER=0.012`
- `KIBOT_PARTIAL_TP_SIZE=0.40`
- `KIBOT_TRAILING_STOP_MIN_PCT=0.015`
- `KIBOT_POST_MORTEM_BLACKLIST_ENABLED=true`
- `KIBOT_POST_MORTEM_BLACKLIST_MINUTES=30`
- `KIBOT_POST_MORTEM_BLACKLIST_NET_LOSS_IDR=500`
- `KIBOT_POST_MORTEM_BLACKLIST_PNL_PCT=-1.0`
- `KIBOT_DAILY_SUMMARY_ENABLED=true`
- `KIBOT_MANAGER_DAILY_SUMMARY_FILE=/home/ubuntu/KiBot/state/daily_summary.json`

### KiDax
- `BOT_ID=main`
- `BOT_PROFILE_KEY=indodax`
- `KIBOT_EXCHANGE_KIND=INDODAX`
- `DEVICE_ROLE=PRIMARY`
- `MAC_ENGINE_PORT=8787`
- `DEVICE_ID=kidax-oracle-sg`
- `KIBOT_HIVE_EXPECTED_BOT_IDS=kinance,kibot`
- `KIBOT_LEAD_LAG_UDP_LISTEN_PORT=9999`
- `KIBOT_LEAD_LAG_UDP_TARGET_HOST=<ip-kinance-atau-kibot-lan>`
- `KIBOT_LEAD_LAG_UDP_TARGET_PORT=9999`
- `INDODAX_API_KEY`
- `INDODAX_API_SECRET`

### Kinance
- `BOT_ID=kinance`
- `BOT_PROFILE_KEY=kinance`
- `KIBOT_EXCHANGE_KIND=BINANCE_SPOT`
- `BOT_ENABLE_LIVE_EXECUTION=false`
- `DEVICE_ROLE=PRIMARY`
- `MAC_ENGINE_PORT=8788`
- `DEVICE_ID=kinance-oracle-sg`
- `KIBOT_HIVE_EXPECTED_BOT_IDS=main,kibot`
- `KIBOT_LEAD_LAG_UDP_LISTEN_PORT=9999`
- `KIBOT_LEAD_LAG_UDP_TARGET_HOST=<ip-main-atau-kibot-lan>`
- `KIBOT_LEAD_LAG_UDP_TARGET_PORT=9999`
- `BINANCE_API_KEY`
- `BINANCE_API_SECRET`
- Pada topologi 2 server aktif, `kinance` dipakai sebagai scanner / lead-lag radar.
  Jalur scan, heartbeat, UDP callout, dan AI review tetap aktif walau live execution dimatikan.

### KiBot Commander
- `BOT_ID=kibot`
- `BOT_PROFILE_KEY=kibot`
- `DEVICE_ROLE=PRIMARY`
- `MAC_ENGINE_PORT=8789`
- `DEVICE_ID=kibot-oracle-sg`
- `KIBOT_HIVE_EXPECTED_BOT_IDS=main,kinance`
- `KIBOT_LEAD_LAG_UDP_LISTEN_PORT=9999`
- `KIBOT_LEAD_LAG_UDP_TARGET_HOST=<ip-kidax-atau-kinance-lan>`
- `KIBOT_LEAD_LAG_UDP_TARGET_PORT=9999`
- exchange credential boleh kosong jika node ini hanya commander/reporting

## Local Recovery Paths
- Default file runtime:
  - `.tmp/runtime/<profile>/local_position_state.json`
- Monthly PnL anchor file:
  - `.tmp/runtime/<profile>/monthly_pnl_anchor.json`
- Pastikan direktori runtime writable oleh service user.
- Jangan share file runtime antar node.
- Recovery service lokal:
  - `infra/scripts/engine-recovery.sh`
  - gunakan hanya pada unit engine yang memang aktif di topologi node tersebut
  - jangan aktifkan watchdog recovery untuk `kibot-engine`, `kibot-local-scanner`, atau service legacy lain

## Legacy Units Yang Harus Tetap Mati
- `kibot-recovery.service`
- `kibot-local-scanner.service`
- `kibot-coordinator.service`
- `kibot-engine.service`
- `kibot-engine-recovery.service`
- `kibot-engine-recovery.timer`
- `kinance-engine-recovery.service` dan `kinance-engine-recovery.timer` pada node scanner-only
- `kidax-standby-engine.service` dan `kidax-standby-engine-recovery.timer` jika standby node tidak dipakai

Verifikasi cepat:
- `systemctl is-enabled kibot-recovery kibot-local-scanner kibot-coordinator kibot-engine-recovery.timer`
- target aman: `disabled` atau `not-found`

## Systemd Memory Baseline
- Baseline aman awal:
  - `-Xms128m -Xmx300m`
- Jika dashboard, AI assist, atau telemetry makin ramai:
  - naikkan jadi `-Xmx512m`
- Untuk `kibot-manager.service` ringan:
  - `MemoryMax=192M`
  - `Nice=5`

## Urutan Deploy
1. Build fat jar lokal atau di server.
2. Copy jar ke `server/mac-engine-all.jar`.
3. Sync env file node.
4. Jika top-up baru masuk dan ingin PnL bulan berjalan mulai dari nol, hapus file anchor lama atau biarkan runtime membuat anchor baru saat sync pertama bulan ini.
5. `sudo systemctl daemon-reload`
6. Restart `Kinance`.
7. Restart `KiBot Commander` jika node commander aktif.
8. Restart `KiDax` terakhir.
9. Pastikan cron recovery aktif:
   - `crontab -l | grep engine-recovery.sh`

Alasan:
- `Kinance` dulu supaya feed global dan heartbeat siap.
- `KiBot Commander` kedua supaya veto/safe-mode layer aktif jika deployment memang memakai commander engine.
- `KiDax` terakhir supaya executor tidak start sendirian tanpa partner.
- Recovery check tetap jalan tiap 2 menit agar 1 server lebih tahan crash kecil.

## Smoke Test Pasca Deploy
1. Cek service hidup:
  - `sudo systemctl status kinance-engine --no-pager`
  - `sudo systemctl status kibot-engine --no-pager`
  - `sudo systemctl status kidax-engine --no-pager`
  - `sudo systemctl status kibot-manager kibot-analyst kibot-notifier kibot-guardian kibot-auditor kibot-orchestrator kibot-security --no-pager`
  - `systemctl is-active kibot-recovery kibot-local-scanner kibot-coordinator kibot-engine-recovery.timer kinance-engine-recovery.timer || true`
2. Cek dashboard state:
  - `curl http://127.0.0.1:8787/api/state`
  - `curl http://127.0.0.1:8788/api/state`
  - `curl http://127.0.0.1:8789/api/state` jika commander engine aktif
3. Jalankan smoke script:
   - `scripts/smoke_test_trinity.sh`
4. Pantau log:
   - `sudo journalctl -u kidax-engine -f -n 200`
   - `sudo journalctl -u kinance-engine -f -n 200`
   - `sudo journalctl -u kibot-engine -f -n 200`
   - `sudo journalctl -u kibot-manager -f -n 200`
5. Cek note AI manager:
  - `cat /home/ubuntu/KiBot/state/runtime_note.json`
  - `cat /home/ubuntu/KiBot/state/ai_provider_state.json`
  - `cat /home/ubuntu/KiBot/state/pair_cooldowns.json`
  - `cat /home/ubuntu/KiBot/state/daily_summary.json`
  - `cat /home/ubuntu/KiBot/state/learning_review.json`
  - `cat /home/ubuntu/KiBot/state/daily_report.json`
  - `cat /home/ubuntu/KiBot/state/daily_cycle_state.json`

## Log Yang Harus Kelihatan
- `TRINITY_HEARTBEAT`
- `LEAD_LAG`
- `LOCAL_RECOVERY`
- `EXECUTION_BUY`
- `EXECUTION_SELL`
- `WHY_NOT_BUY`

## Tanda Aman Masuk Live Kecil
- Semua endpoint `/api/state` respon normal.
- Tidak ada node yang masuk `SAFE_MODE` tanpa sebab.
- Tidak ada spam `chart_activity_blocked` pada pair yang seharusnya aktif.
- Heartbeat timeout tidak muncul saat pasangan node yang diwajibkan hidup.
- File `local_position_state.json` terbuat dan terupdate saat ada posisi.

## Hold Deploy Jika
- `SAFE_MODE` muncul terus setelah restart.
- `LOCAL_RECOVERY` terus fallback walau control plane sehat.
- `Exchange unreachable` atau timeout berulang.
- KiDax open posisi saat Kinance/KiCryp belum online.

## Go-Live Bertahap
1. Shadow/saldo kecil.
2. Live kecil selama 2-4 jam.
3. Review log `EXECUTION_BUY`, `EXECUTION_SELL`, `TRINITY_HEARTBEAT`, `LOCAL_RECOVERY`.
4. Baru naikkan modal.
