# GitHub Actions Two-Server Direction

## Active Workflows
- KiDax: `.github/workflows/deploy-kidax.yml`
- Kinance: `.github/workflows/deploy-kinance.yml`

## Required GitHub Secrets
### KiDax
- `KIDAX_HOST`
- `KIDAX_USER`
- `KIDAX_SSH_KEY`
- `KIDAX_SSH_PORT`

### Kinance
- `KINANCE_HOST`
- `KINANCE_USER`
- `KINANCE_SSH_KEY`
- `KINANCE_SSH_PORT`

## Remote Layout
### KiDax
- root: `/home/ubuntu/KiDax`
- env: `/home/ubuntu/KiDax/.env.kidax`
- service: `kidax-engine.service`
- dashboard port: `8787`
- exchange kind: `INDODAX`
- bot id: `kidax`

### Kinance
- root: `/home/ubuntu/Kinance`
- env: `/home/ubuntu/Kinance/.env.kinance`
- service: `kinance-engine.service`
- dashboard port: `8788`
- exchange kind: `BINANCE_SPOT`
- bot id: `kinance`

## Bootstrap Requirements For Oracle Micro
Run once before first deploy on each server:

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
printf '/swapfile none swap sw 0 0\n' | sudo tee -a /etc/fstab
sudo sysctl vm.swappiness=10
printf 'vm.swappiness=10\n' | sudo tee -a /etc/sysctl.conf
sudo apt-get update && sudo apt-get install -y chrony
sudo systemctl enable chrony --now
chronyc tracking
```

## Guardrail
- KiDax and Kinance never share service names, roots, ports, or env files.
- KiDax workflow must not deploy Binance-only runtime assets.
- Kinance workflow stays manual until Binance runtime is ready for live execution.
