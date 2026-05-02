#!/bin/bash

# KiBot Trinity: Single-Role Enforcement Deployment
# Enforces role purity on all three nodes per ROLE_MANIFEST.md
# Usage: bash scripts/deploy_single_role_enforcement.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
SSH_TIMEOUT=10

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
  echo -e "${GREEN}[INFO]${NC} $*"
}

log_warn() {
  echo -e "${YELLOW}[WARN]${NC} $*"
}

log_error() {
  echo -e "${RED}[ERROR]${NC} $*"
}

log_header() {
  echo -e "${BLUE}========== $* ==========${NC}"
}

# Read SSH inventory from ops/SERVERS.json
read_ssh_config() {
  local jq_query='
    .nodes[] | 
    "HOST=\(.host)|USER=\(.user)|KEY=\(.key)|ROLE=\(.role)|ID=\(.id)"
  '
  
  if ! command -v jq &> /dev/null; then
    log_error "jq not found. Cannot parse ops/SERVERS.json"
    return 1
  fi
  
  jq -r "$jq_query" "$REPO_ROOT/ops/SERVERS.json"
}

# Deploy to a single node
deploy_to_node() {
  local node_host=$1
  local node_user=$2
  local node_key=$3
  local node_id=$4
  local node_role=$5

  log_header "Deploying to $node_id ($node_host) - Role: $node_role"

  # Determine which services to enable/disable based on role
  local enable_services=""
  local disable_services=""

  case "$node_id" in
    batam-manager)
      enable_services="kibot-manager kibot-analyst kibot-auditor kibot-notifier kibot-orchestrator kibot-security kibot-guardian kibot-ollama-gateway ki-telegram-monitor indodax-dashboard-proxy lazarus-ampere"
      disable_services="kibot-executor-indodax kibot-polymarket ki-global-scanner-mesh kibot-scanner@ kibot-executor-indodax kicryp-engine kicryp-manager"
      ;;
    EXECUTOR-executor-cluster)
      enable_services="kibot-executor-indodax kibot-polymarket"
      disable_services="kibot-manager kibot-analyst kibot-auditor kibot-notifier kibot-orchestrator kibot-security kibot-guardian kibot-ollama-gateway ki-telegram-monitor indodax-dashboard-proxy lazarus-ampere ki-global-scanner-mesh kibot-scanner@ kibot-executor-indodax kicryp-engine kicryp-manager"
      ;;
    SCANNER-scanner-cluster)
      enable_services="ki-global-scanner-mesh kibot-scanner@"
      disable_services="kibot-executor-indodax kibot-polymarket kibot-manager kibot-analyst kibot-auditor kibot-notifier kibot-orchestrator kibot-security kibot-guardian kibot-ollama-gateway ki-telegram-monitor indodax-dashboard-proxy lazarus-ampere kibot-executor-indodax kicryp-engine kicryp-manager"
      ;;
    *)
      log_error "Unknown node ID: $node_id"
      return 1
      ;;
  esac

  # Generate deployment script
  local deploy_script=$(cat <<'ENDSCRIPT'
#!/bin/bash
set -e

echo "=== Step 1: Enable role-specific services ==="
for svc in ENABLE_SERVICES; do
  echo "Enabling $svc.service..."
  sudo systemctl enable "$svc.service" 2>/dev/null || true
done

echo ""
echo "=== Step 2: Disable non-role services ==="
for svc in DISABLE_SERVICES; do
  echo "Disabling $svc.service..."
  sudo systemctl disable "$svc.service" 2>/dev/null || true
done

echo ""
echo "=== Step 3: Restart systemd daemon ==="
sudo systemctl daemon-reload

echo ""
echo "=== Step 4: Current enabled services (filtered) ==="
systemctl list-units --type=service --state=enabled --no-pager 2>/dev/null | grep -E 'kibot|KiBot|KiBot|ki-|kicryp' | awk '{print $1}' | sort

echo ""
echo "=== Step 5: Verify service status ==="
for svc in ENABLE_SERVICES; do
  status=$(systemctl is-enabled "$svc.service" 2>/dev/null || echo "disabled")
  echo "$svc.service: $status"
done
ENDSCRIPT
)

  # Substitute service lists
  deploy_script="${deploy_script//ENABLE_SERVICES/$enable_services}"
  deploy_script="${deploy_script//DISABLE_SERVICES/$disable_services}"

  # Execute on remote node
  echo "$deploy_script" | ssh \
    -i "$REPO_ROOT/$node_key" \
    -o StrictHostKeyChecking=no \
    -o ConnectTimeout=$SSH_TIMEOUT \
    -o UserKnownHostsFile=/dev/null \
    "$node_user@$node_host" \
    bash 2>&1 | while IFS= read -r line; do
      echo "  $line"
    done

  if [ ${PIPESTATUS[0]} -eq 0 ]; then
    log_info "✓ $node_id role enforcement completed"
  else
    log_error "✗ $node_id role enforcement FAILED"
    return 1
  fi

  sleep 2
}

# Main execution
main() {
  log_header "KiBot Trinity Single-Role Enforcement"
  echo ""
  log_info "Authority: ROLE_MANIFEST.md"
  log_info "Target: Enforce 1 role per node (Batam=Brain, EXECUTOR=Executor, SCANNER=Scanner)"
  echo ""

  if [ ! -f "$REPO_ROOT/ops/SERVERS.json" ]; then
    log_error "ops/SERVERS.json not found at $REPO_ROOT/ops/SERVERS.json"
    return 1
  fi

  # Parse and deploy to each node
  local deployed=0
  local failed=0

  while IFS='|' read -r line; do
    # Parse "KEY=VALUE|KEY=VALUE|..." format
    declare -A config
    for kv in $line; do
      IFS='=' read -r k v <<< "$kv"
      config["$k"]="$v"
    done

    local host="${config[HOST]}"
    local user="${config[USER]}"
    local key="${config[KEY]}"
    local role="${config[ROLE]}"
    local id="${config[ID]}"

    if deploy_to_node "$host" "$user" "$key" "$id" "$role"; then
      ((deployed++))
    else
      ((failed++))
    fi
  done < <(read_ssh_config)

  echo ""
  log_header "Deployment Summary"
  log_info "Successfully enforced: $deployed nodes"
  if [ $failed -gt 0 ]; then
    log_error "Failed: $failed nodes"
    return 1
  fi

  log_info "All nodes now enforce single-role topology ✓"
  log_info "Diagram reference: ARCHITECTURE_SINGLE_ROLE.md"
}

main "$@"
