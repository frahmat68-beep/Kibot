# Trinity Deployment Guide

## System Overview

Trinity consists of 3 independent services running on 2 Oracle Cloud servers:

**Indodax Server (213.35.118.26):**
- kidax-engine (KiDax trading bot)
- kicryp-manager (Python AI veto daemon)

**Binance Server (152.69.218.198):**
- kinance-engine (Kinance scanner)

## Prerequisites

1. SSH keys in repo: `SSH_INDODAX/` and `SSH_BINANCE/`
2. JDK 21+ installed on both servers
3. Python 3.10+ on Indodax server
4. Systemd service files configured

## Build

```bash
# From repo root
./gradlew :apps:mac-engine:shadowJar

# Output: apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar
```

## Deploy to Indodax Server

```bash
# Copy JAR
scp -i "SSH_INDODAX/ssh-key-2026-03-22.key" \
  apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar \
  ubuntu@213.35.118.26:/home/ubuntu/KiDax/server/mac-engine-all.jar

# Restart services
ssh -i "SSH_INDODAX/ssh-key-2026-03-22.key" ubuntu@213.35.118.26 \
  'sudo systemctl restart kidax-engine kicryp-manager'

# Verify
ssh -i "SSH_INDODAX/ssh-key-2026-03-22.key" ubuntu@213.35.118.26 \
  'systemctl status kidax-engine kicryp-manager'
```

## Deploy to Binance Server

```bash
# Copy JAR
scp -i "SSH_BINANCE/ssh-key-2026-03-27.key" \
  apps/mac-engine/build/libs/mac-engine-0.1.0-all.jar \
  ubuntu@152.69.218.198:/home/ubuntu/Kinance/server/mac-engine-all.jar

# Restart service
ssh -i "SSH_BINANCE/ssh-key-2026-03-27.key" ubuntu@152.69.218.198 \
  'sudo systemctl restart kinance-engine'

# Verify
ssh -i "SSH_BINANCE/ssh-key-2026-03-27.key" ubuntu@152.69.218.198 \
  'systemctl status kinance-engine'
```

## Health Checks

### Check KiDax Status
```bash
ssh -i "SSH_INDODAX/ssh-key-2026-03-22.key" ubuntu@213.35.118.26 \
  'curl -s http://localhost:8787/api/state | python3 -m json.tool'
```

### Check Kinance Status
```bash
ssh -i "SSH_BINANCE/ssh-key-2026-03-27.key" ubuntu@152.69.218.198 \
  'curl -s http://localhost:8788/api/state | python3 -m json.tool'
```

### Check Memory Usage
```bash
# Indodax
ssh -i "SSH_INDODAX/ssh-key-2026-03-22.key" ubuntu@213.35.118.26 'free -m'

# Binance
ssh -i "SSH_BINANCE/ssh-key-2026-03-27.key" ubuntu@152.69.218.198 'free -m'
```

### Monitor Logs
```bash
# KiDax
ssh -i "SSH_INDODAX/ssh-key-2026-03-22.key" ubuntu@213.35.118.26 \
  'journalctl -u kidax-engine -f'

# KiCryp Manager
ssh -i "SSH_INDODAX/ssh-key-2026-03-22.key" ubuntu@213.35.118.26 \
  'journalctl -u kicryp-manager -f'

# Kinance
ssh -i "SSH_BINANCE/ssh-key-2026-03-27.key" ubuntu@152.69.218.198 \
  'journalctl -u kinance-engine -f'
```

## Rollback

```bash
# Indodax
ssh -i "SSH_INDODAX/ssh-key-2026-03-22.key" ubuntu@213.35.118.26 \
  'cd /home/ubuntu/KiDax/server && \
   cp mac-engine-all.jar.bak.$(ls -t mac-engine-all.jar.bak* | head -1) mac-engine-all.jar && \
   sudo systemctl restart kidax-engine'

# Binance
ssh -i "SSH_BINANCE/ssh-key-2026-03-27.key" ubuntu@152.69.218.198 \
  'cd /home/ubuntu/Kinance/server && \
   cp mac-engine-all.jar.bak.$(ls -t mac-engine-all.jar.bak* | head -1) mac-engine-all.jar && \
   sudo systemctl restart kinance-engine'
```

## Emergency Stop

```bash
# Stop all trading
ssh -i "SSH_INDODAX/ssh-key-2026-03-22.key" ubuntu@213.35.118.26 \
  'sudo systemctl stop kidax-engine kicryp-manager'

ssh -i "SSH_BINANCE/ssh-key-2026-03-27.key" ubuntu@152.69.218.198 \
  'sudo systemctl stop kinance-engine'
```

## Configuration Files

### Indodax Server
- `/home/ubuntu/KiDax/.env.kidax` - KiDax config
- `/home/ubuntu/KiCryp/.env.kicryp_manager` - Python AI daemon config
- `/etc/systemd/system/kidax-engine.service` - KiDax systemd
- `/etc/systemd/system/kicryp-manager.service` - Manager systemd

### Binance Server
- `/home/ubuntu/Kinance/.env.kinance` - Kinance config
- `/etc/systemd/system/kinance-engine.service` - Kinance systemd

## Troubleshooting

### High Memory Usage
```bash
# Check swap usage
ssh ubuntu@SERVER 'free -m'

# Clear swap if needed
ssh ubuntu@SERVER 'sudo swapoff -a && sudo swapon -a'
```

### Service Won't Start
```bash
# Check detailed logs
ssh ubuntu@SERVER 'journalctl -u SERVICE_NAME -n 100 --no-pager'

# Check if port is in use
ssh ubuntu@SERVER 'sudo lsof -i :PORT'
```

### UDP Communication Issues
```bash
# Test UDP from Kinance to KiDax
ssh -i "SSH_BINANCE/ssh-key-2026-03-27.key" ubuntu@152.69.218.198 \
  'ping -c 3 213.35.118.26'
```

## Performance Monitoring

```bash
# CPU and memory per service
ssh ubuntu@SERVER 'systemctl status SERVICE_NAME | grep -E "Memory|CPU"'

# Trading activity
ssh -i "SSH_INDODAX/ssh-key-2026-03-22.key" ubuntu@213.35.118.26 \
  'journalctl -u kidax-engine --since "10 minutes ago" | grep -E "BUY|SELL" | tail -20'
```
