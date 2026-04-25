# Server Deploy Checklist

## Pre-flight

1. `git diff --stat` bersih untuk file yang mau didorong
2. build jar `mac-engine` lulus
3. `python3 -m py_compile` lulus untuk script yang berubah
4. pastikan tidak ada governor/service legacy yang ikut hidup

## Target Services

### SG1

- wajib:
  - `kidax-engine`
  - `kibot-manager`
  - `ki-telegram-monitor`
  - `kibot-ollama-tunnel`
  - `kibot-polymarket-tunnel`

- opsional:
  - `kibot-analyst`
  - `kibot-guardian`

### SG2

- wajib:
  - `kinance-engine`
  - `ki-global-scanner-mesh`
  - `kibot-manager`
  - `kibot-ollama-tunnel`
  - `kibot-polymarket-tunnel`

- support sesuai kebutuhan ops:
  - `kibot-auditor`
  - `kibot-guardian`
  - `kibot-notifier`
  - `kibot-orchestrator`
  - `kibot-security`

### Batam

- wajib:
  - `ollama`
  - `kibot-ollama-gateway`
  - `kibot-polymarket`

## Must Stay Dead

- `kibot-governor.service`
- `kibot-local-scanner.service`
- `kibot-coordinator.service`
- legacy Telegram listener stack

## Resource Rules

- SG adalah node RAM ketat
- sidecar non-kritis dimatikan dulu bila swap mulai berat
- jangan kurangi jatah `kidax-engine` / `kinance-engine` tanpa alasan kuat
- AI berat dan review nightly harus pindah ke Batam

## Smoke Test

1. `systemctl is-active` semua service inti
2. `curl /api/state` SG1 dan SG2 normal
3. `curl /health` gateway Batam normal
4. `curl /api/state` Polymarket Batam normal
5. cek `state/runtime_note.json`
6. cek `state/governor_directives.json`
7. cek tidak ada spam `500` baru di Batam

## 10-Minute Soak

Pantau minimal:

- `journalctl -u kibot-manager --since "10 min ago"`
- `journalctl -u kidax-engine --since "10 min ago"`
- `journalctl -u kinance-engine --since "10 min ago"`
- `journalctl -u kibot-ollama-gateway --since "10 min ago"`
- `journalctl -u kibot-polymarket --since "10 min ago"`

Target aman:

- plan governor terus refresh
- gateway Ollama tidak spam `500`
- Polymarket terus refresh
- executor tidak masuk degrade tanpa alasan
