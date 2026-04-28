# Trinity Deployment Guide

## Active Layout

### SG1 / Indodax Executor

- host: `213.35.118.26`
- services:
  - `kidax-engine`
  - `kibot-manager`
  - `ki-telegram-monitor`
  - `kibot-ollama-tunnel`
  - `kibot-polymarket-tunnel`

### SG2 / Global Radar

- host: `152.69.218.198`
- services:
  - `kinance-engine`
  - `ki-global-scanner-mesh`
  - `kibot-manager`
  - support ops services as needed
  - `kibot-ollama-tunnel`
  - `kibot-polymarket-tunnel`

### Batam / Brain Hub

- host: `168.110.201.228`
- services:
  - `ollama`
  - `kibot-ollama-gateway`
  - `kibot-polymarket`

## Build

```bash
./gradlew :apps:mac-engine:shadowJar
```

Artifact:

- `apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar`

## Deploy SG1

```bash
scp -i "SSH_SINGAPORE/SSH_SG1/ssh-key-2026-03-22.key" \
  apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar \
  ubuntu@213.35.118.26:/home/ubuntu/KiBot/server/mac-engine-all.jar

ssh -i "SSH_SINGAPORE/SSH_SG1/ssh-key-2026-03-22.key" ubuntu@213.35.118.26 \
  'sudo systemctl restart kidax-engine kibot-manager ki-telegram-monitor'
```

## Deploy SG2

```bash
scp -i "SSH_SINGAPORE/SSH_SG2/ssh-key-2026-03-27.key" \
  apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar \
  ubuntu@152.69.218.198:/home/ubuntu/KiBot/server/mac-engine-all.jar

ssh -i "SSH_SINGAPORE/SSH_SG2/ssh-key-2026-03-27.key" ubuntu@152.69.218.198 \
  'sudo systemctl restart kinance-engine kibot-manager ki-global-scanner-mesh'
```

## Deploy Batam

```bash
rsync -avz -e "ssh -i SSH_BATAM/ssh-key-batam-active.pem" \
  core/kibot_ollama_gateway.py \
  core/kibot_polymarket.py \
  ubuntu@168.110.201.228:/home/ubuntu/KiBot/core/

scp -i "SSH_BATAM/ssh-key-batam-active.pem" \
  infra/systemd/kibot-ollama-gateway.service \
  infra/systemd/kibot-polymarket.service \
  infra/systemd/ollama-batam.override.conf \
  ubuntu@168.110.201.228:/tmp/

ssh -i "SSH_BATAM/ssh-key-batam-active.pem" ubuntu@168.110.201.228 '
  sudo cp /tmp/kibot-ollama-gateway.service /etc/systemd/system/kibot-ollama-gateway.service &&
  sudo cp /tmp/kibot-polymarket.service /etc/systemd/system/kibot-polymarket.service &&
  sudo mkdir -p /etc/systemd/system/ollama.service.d &&
  sudo cp /tmp/ollama-batam.override.conf /etc/systemd/system/ollama.service.d/override.conf &&
  sudo systemctl daemon-reload &&
  sudo systemctl restart ollama kibot-ollama-gateway kibot-polymarket
'
```

## Deploy Ampere Runner

- Jalankan launcher `lazarus_ampere.sh` dari Batam saja.
- SG1 tidak dipakai untuk runner ini.
- Target launch tetap `ap-singapore-1` dengan `VM.Standard.A1.Flex` `1 OCPU / 6 GB`.

Jika file runner sudah ada di Batam:

```bash
ssh -i "SSH_BATAM/ssh-key-batam-active.pem" ubuntu@168.110.201.228 \
  'cd /home/ubuntu/ampere-hunt && ./lazarus_ampere.sh'
```

## Health Checks

SG1:

```bash
ssh -i "SSH_SINGAPORE/SSH_SG1/ssh-key-2026-03-22.key" ubuntu@213.35.118.26 \
  'curl -s http://127.0.0.1:9998/api/state'
```

SG2:

```bash
ssh -i "SSH_SINGAPORE/SSH_SG2/ssh-key-2026-03-27.key" ubuntu@152.69.218.198 \
  'curl -s http://127.0.0.1:9998/api/state'
```

Batam:

```bash
ssh -i "SSH_BATAM/ssh-key-batam-active.pem" ubuntu@168.110.201.228 \
  'curl -s http://127.0.0.1:11435/health && echo && curl -s http://127.0.0.1:11600/api/state'
```

## Rules

- jangan jalankan governor terpisah
- jangan jadikan Batam blocking dependency untuk setiap keputusan kecil
- kalau RAM SG mepet, matikan sidecar non-kritis dulu sebelum sentuh engine utama
- `.oci` / Ampere assets tetap dipertahankan dan tidak termasuk cleanup ini
