#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOST="152.69.218.198"
USER="ubuntu"
PORT="22"
SSH_KEY="${ROOT_DIR}/SSH_BINANCE/ssh-key-2026-03-27.key"
OCI_CONFIG="/home/ubuntu/.oci/config"
LOG_FILE="/home/ubuntu/KiBot/logs/lazarus.log"
OCID="ocid1.instance.oc1.ap-singapore-1.anzwsljrjnctqdycjnedjgrwqodx3amfb7bf6vinqc5faebrftwmdzgi7nga"
REMOTE_ROOT="/home/ubuntu/KiBot"
REMOTE_SSH_KEY="/home/ubuntu/.ssh/ssh-key-2026-03-22.key"
LOCAL_TARGET_SSH_KEY="${ROOT_DIR}/SSH_INDODAX/ssh-key-2026-03-22.key"
LOCAL_LAZARUS_SCRIPT="${ROOT_DIR}/infra/scripts/lazarus_auto_migrate.sh"
LOCAL_SUPERVISOR_SCRIPT="${ROOT_DIR}/infra/scripts/lazarus_supervisor.sh"
LOCAL_SUPERVISOR_SERVICE="${ROOT_DIR}/infra/systemd/lazarus-supervisor.service"

ssh_cmd() {
  ssh -i "$SSH_KEY" -p "$PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new "${USER}@${HOST}" "$@"
}

scp_to() {
  scp -i "$SSH_KEY" -P "$PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new "$1" "${USER}@${HOST}:$2"
}

ssh_cmd "set -euo pipefail; mkdir -p ${REMOTE_ROOT}/logs ${REMOTE_ROOT}/infra/scripts /home/ubuntu/.oci /home/ubuntu/.ssh"
cat <<'REMOTE' | ssh_cmd 'bash -s'
set -euo pipefail
pkill -9 -f '/home/ubuntu/KiBot/infra/scripts/lazarus_autostart_watch.sh' || true
pkill -9 -f '/home/ubuntu/KiBot/infra/scripts/lazarus_auto_migrate.sh' || true
pkill -9 -f 'tail -f /home/ubuntu/KiBot/logs/lazarus.log' || true
rm -f /home/ubuntu/KiBot/logs/lazarus.watch.lock /tmp/lazarus-auto-migrate.lock /home/ubuntu/KiBot/logs/lazarus.pid || true
REMOTE

scp_to "${LOCAL_LAZARUS_SCRIPT}" "${REMOTE_ROOT}/infra/scripts/lazarus_auto_migrate.sh"
scp_to "${LOCAL_SUPERVISOR_SCRIPT}" "${REMOTE_ROOT}/infra/scripts/lazarus_supervisor.sh"
scp_to "${LOCAL_SUPERVISOR_SERVICE}" "${REMOTE_ROOT}/infra/systemd/lazarus-supervisor.service"
scp_to "${LOCAL_TARGET_SSH_KEY}" "${REMOTE_SSH_KEY}"

ssh_cmd "chmod 600 ${REMOTE_SSH_KEY} && chmod +x ${REMOTE_ROOT}/infra/scripts/lazarus_auto_migrate.sh ${REMOTE_ROOT}/infra/scripts/lazarus_supervisor.sh"

cat <<'REMOTE' | ssh_cmd 'bash -s'
set -euo pipefail
cat > /home/ubuntu/KiBot/infra/scripts/lazarus_autostart_watch.sh <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
exec 200>/home/ubuntu/KiBot/logs/lazarus.watch.lock
flock -n 200 || exit 0

LOG_FILE="/home/ubuntu/KiBot/logs/lazarus.log"
STATUS_FILE="/home/ubuntu/KiBot/state/lazarus.status.json"
OCID="ocid1.instance.oc1.ap-singapore-1.anzwsljrjnctqdycjnedjgrwqodx3amfb7bf6vinqc5faebrftwmdzgi7nga"
LZ="/home/ubuntu/KiBot/infra/scripts/lazarus_auto_migrate.sh"
SSH_KEY="/home/ubuntu/.ssh/ssh-key-2026-03-22.key"
OCI_CONFIG="/home/ubuntu/.oci/config"
PATH="/home/ubuntu/bin:/home/ubuntu/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

log() {
  printf '[%s] %s\n' "$(date '+%F %T')" "$*" | tee -a "$LOG_FILE"
}

oci_bin() {
  if [[ -x /home/ubuntu/bin/oci ]]; then
    echo /home/ubuntu/bin/oci
  elif [[ -x /home/ubuntu/.local/bin/oci ]]; then
    echo /home/ubuntu/.local/bin/oci
  else
    command -v oci || true
  fi
}

wait_for_oci() {
  while true; do
    local bin
    bin="$(oci_bin)"
    if [[ -n "$bin" ]]; then
      if "$bin" --version >/tmp/lazarus-oci-version.log 2>&1; then
        if "$bin" --config-file "$OCI_CONFIG" iam region list >/tmp/lazarus-oci-auth.log 2>&1; then
          log "[Watch] OCI ready: $("$bin" --version 2>/dev/null | head -n1)"
          return 0
        fi
        log "[Watch] OCI present but auth not ready yet."
      fi
    fi
    log "[Watch] OCI binary not ready yet. Retrying in 30s..."
    sleep 30
  done
}

wait_for_oci
if [[ -f "$STATUS_FILE" ]] && grep -q '"status":"completed"' "$STATUS_FILE"; then
  log "[Watch] Lazarus already completed. Supervisor will stay idle."
  exit 0
fi
log "[Watch] Launching Lazarus auto-migrate."
env PATH="/home/ubuntu/bin:/home/ubuntu/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
  bash "$LZ" \
  --ocid "$OCID" \
  --ssh-key "$SSH_KEY" \
  --kidax-jar /home/ubuntu/KiBot/server/mac-engine-all.jar \
  --kibot-jar /home/ubuntu/KiBot/server/mac-engine-all.jar \
  >/home/ubuntu/KiBot/logs/lazarus.migrate.out 2>&1
rc=$?
if [[ $rc -eq 0 ]] && [[ -f "$STATUS_FILE" ]]; then
  log "[Watch] Lazarus completed successfully; leaving supervisor idle."
  rm -f /home/ubuntu/KiBot/logs/lazarus.pid
  exit 0
fi
log "[Watch] Lazarus exited with rc=$rc; will let supervisor retry."
exit "$rc"
EOF
chmod +x /home/ubuntu/KiBot/infra/scripts/lazarus_autostart_watch.sh
REMOTE

ssh_cmd "tmux kill-session -t lazarus >/dev/null 2>&1 || true"
ssh_cmd "sudo install -m 0644 /home/ubuntu/KiBot/infra/systemd/lazarus-supervisor.service /etc/systemd/system/lazarus-supervisor.service && sudo systemctl daemon-reload && sudo systemctl enable --now lazarus-supervisor.service"

echo "started"
