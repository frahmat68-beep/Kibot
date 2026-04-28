#!/usr/bin/env bash

set -euo pipefail

RUNTIME_ROOT="${KIBOT_RUNTIME_ROOT:-/home/ubuntu/KiBot}"
LOGROTATE_CONF="/etc/logrotate.d/kibot-batam"
WATCHDOG_SCRIPT="/usr/local/bin/kibot-batam-watchdog.sh"
WATCHDOG_SERVICE="/etc/systemd/system/kibot-batam-watchdog.service"
WATCHDOG_TIMER="/etc/systemd/system/kibot-batam-watchdog.timer"
HEALTH_SCRIPT="/usr/local/bin/kibot-batam-health-report.sh"
HEALTH_SERVICE="/etc/systemd/system/kibot-batam-health-report.service"
HEALTH_TIMER="/etc/systemd/system/kibot-batam-health-report.timer"
NOTIFIER_SERVICE="/etc/systemd/system/kibot-notifier.service"
ORCHESTRATOR_SERVICE="/etc/systemd/system/kibot-orchestrator.service"
SECURITY_SERVICE="/etc/systemd/system/kibot-security.service"
GUARDIAN_SERVICE="/etc/systemd/system/kibot-guardian.service"
ANALYST_SERVICE="/etc/systemd/system/kibot-analyst.service"
MANAGER_SERVICE="/etc/systemd/system/kibot-manager.service"
OLLAMA_GATEWAY_SERVICE="/etc/systemd/system/kibot-ollama-gateway.service"
POLYMARKET_SERVICE="/etc/systemd/system/kibot-polymarket.service"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script as root." >&2
  exit 1
fi

install -d -o ubuntu -g ubuntu /home/ubuntu/logs
install -d -o ubuntu -g ubuntu /home/ubuntu/ampere-hunt/state

cat > "${LOGROTATE_CONF}" <<'EOF'
/home/ubuntu/logs/*.log /home/ubuntu/logs/*.out /home/ubuntu/ampere-hunt/*.log /home/ubuntu/ampere-hunt/state/*.log {
    daily
    size 20M
    rotate 5
    compress
    delaycompress
    missingok
    notifempty
    create 0644 ubuntu ubuntu
    dateext
    dateformat -%Y%m%d
    sharedscripts
}
EOF
chmod 644 "${LOGROTATE_CONF}"

cat > "${WATCHDOG_SCRIPT}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

check_unit() {
  local unit="$1"
  if ! systemctl is-active --quiet "${unit}"; then
    systemctl restart "${unit}"
  fi
}

check_unit unified-monitoring-agent.service
check_unit snap.oracle-cloud-agent.oracle-cloud-agent.service
check_unit ollama.service
check_unit kibot-ollama-gateway.service
check_unit kibot-polymarket.service
check_unit lazarus-ampere.service
EOF
chmod 755 "${WATCHDOG_SCRIPT}"

cat > "${HEALTH_SCRIPT}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

LOG_FILE="/home/ubuntu/logs/kibot-batam-health.log"
{
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] HOST=$(hostname)"
  echo "services:"
  for unit in unified-monitoring-agent.service snap.oracle-cloud-agent.oracle-cloud-agent.service ollama.service kibot-ollama-gateway.service kibot-polymarket.service lazarus-ampere.service; do
    printf '  %s=%s\n' "$unit" "$(systemctl is-active "$unit" 2>/dev/null || true)"
  done
  echo "mem:"
  free -h | sed 's/^/  /'
  echo "disk:"
  df -h /home / | sed 's/^/  /'
  echo "load:"
  uptime | sed 's/^/  /'
  echo
} >> "$LOG_FILE"
EOF
chmod 755 "${HEALTH_SCRIPT}"

install_service() {
  local src="$1"
  local dst="$2"
  install -m 0644 "$src" "$dst"
}

install_service "${RUNTIME_ROOT}/infra/systemd/kibot-notifier.service" "${NOTIFIER_SERVICE}"
install_service "${RUNTIME_ROOT}/infra/systemd/kibot-orchestrator.service" "${ORCHESTRATOR_SERVICE}"
install_service "${RUNTIME_ROOT}/infra/systemd/kibot-security.service" "${SECURITY_SERVICE}"
install_service "${RUNTIME_ROOT}/infra/systemd/kibot-guardian.service" "${GUARDIAN_SERVICE}"
install_service "${RUNTIME_ROOT}/infra/systemd/kibot-analyst.service" "${ANALYST_SERVICE}"
install_service "${RUNTIME_ROOT}/infra/systemd/kibot-manager.service" "${MANAGER_SERVICE}"
install_service "${RUNTIME_ROOT}/infra/systemd/kibot-ollama-gateway.service" "${OLLAMA_GATEWAY_SERVICE}"
install_service "${RUNTIME_ROOT}/infra/systemd/kibot-polymarket.service" "${POLYMARKET_SERVICE}"

cat > "${WATCHDOG_SERVICE}" <<'EOF'
[Unit]
Description=KiBot Batam watchdog

[Service]
Type=oneshot
ExecStart=/usr/local/bin/kibot-batam-watchdog.sh
EOF

cat > "${WATCHDOG_TIMER}" <<'EOF'
[Unit]
Description=KiBot Batam watchdog timer

[Timer]
OnBootSec=45s
OnUnitActiveSec=60s
AccuracySec=10s
Persistent=true
Unit=kibot-batam-watchdog.service

[Install]
WantedBy=timers.target
EOF

cat > "${HEALTH_SERVICE}" <<'EOF'
[Unit]
Description=KiBot Batam health report

[Service]
Type=oneshot
ExecStart=/usr/local/bin/kibot-batam-health-report.sh
EOF

cat > "${HEALTH_TIMER}" <<'EOF'
[Unit]
Description=KiBot Batam health report timer

[Timer]
OnBootSec=60s
OnUnitActiveSec=10m
AccuracySec=15s
Persistent=true
Unit=kibot-batam-health-report.service

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now kibot-notifier.service
systemctl enable --now kibot-orchestrator.service
systemctl enable --now kibot-security.service
systemctl enable --now kibot-guardian.service
systemctl enable --now kibot-analyst.service
systemctl enable --now kibot-manager.service
systemctl enable --now kibot-ollama-gateway.service
systemctl enable --now kibot-polymarket.service
systemctl enable --now kibot-batam-watchdog.timer
systemctl enable --now kibot-batam-health-report.timer

if [[ -f "${RUNTIME_ROOT}/core/kibot_ollama_gateway.py" ]]; then
  install -d -o ubuntu -g ubuntu /home/ubuntu/KiBot/state
fi

echo "Batam autonomous baseline installed."
