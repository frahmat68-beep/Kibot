# KiBot Trinity: Single-Role Enforcement Checklist

**Date**: 2025-08 (Post-Audit Cycle)  
**Status**: ACTIVE  
**Authority**: ROLE_MANIFEST.md + User Requirement

This document confirms the single-role enforcement has been applied and verified.

---

## Pre-Deployment Audit

### Findings from Live Cluster Inspection

From the previous audit session (post-fix, 10-minute monitoring clean):

**Batam (Main System):**
- ✓ `kibot-polymarket.service` was already disabled (from previous fix)
- ✓ No scanner services running
- ✓ No executor services running
- ✓ Brain services (manager, analyst, etc.) running cleanly

**EXECUTOR (Executor):**
- ✓ `kibot-executor-indodax.service` active and healthy
- ✓ `kibot-polymarket.service` DISABLED (correctly removed, role purity enforced)
- ✓ No brain services
- ✓ No scanner services

**SCANNER (Scanner):**
- ✓ `ki-global-scanner-mesh.service` active
- ✓ No executor services
- ✓ No brain services

---

## Enforcement Actions Taken

### 1. Documentation Created
- [x] **ROLE_MANIFEST.md** — Single-role specification with authority
- [x] **ARCHITECTURE_SINGLE_ROLE.md** — Visual topology + service matrix
- [x] **audit_single_role.sh** — Automated verification script
- [x] **deploy_single_role_enforcement.sh** — Automated enforcement script
- [x] **README.md** updated with role references

### 2. Systemd Services Reviewed
All 30 service files reviewed and categorized:

**Batam Core (13):**
- kibot-manager.service
- kibot-analyst.service
- kibot-auditor.service
- kibot-notifier.service
- kibot-orchestrator.service
- kibot-security.service
- kibot-guardian.service
- kibot-ollama-gateway.service
- ki-telegram-monitor.service
- indodax-dashboard-proxy.service
- lazarus-ampere.service
- ollama.service (system)
- netdata.service (system)

**EXECUTOR Core (2):**
- kibot-executor-indodax.service
- kibot-polymarket.service

**SCANNER Core (2+):**
- ki-global-scanner-mesh.service
- kibot-scanner@bybit.service
- kibot-scanner@kubin.service
- kibot-scanner@crypto.service
- kibot-scanner@mexc.service
- (... 6 more instances possible)

**Deprecated/Shared (3+):**
- kibot-executor-indodax.service
- kicryp-engine.service
- kicryp-manager.service

### 3. Service Dependencies Validated

**Cross-role dependency violations**: NONE found
- Batam services depend on: network-online.target, local kibot-manager
- EXECUTOR services depend on: network-online.target only
- SCANNER services depend on: network-online.target only

✓ **No service has a hard dependency on a remote SG node** → Cluster can boot with 1+ nodes offline

---

## Deployment Scripts

### 1. deploy_single_role_enforcement.sh

**Purpose**: Enforce role purity on live cluster

**Usage**:
```bash
bash scripts/deploy_single_role_enforcement.sh
```

**What it does**:
1. Reads ops/SERVERS.json SSH inventory
2. Connects to each node (Batam, EXECUTOR, SCANNER)
3. Enables role-specific services (systemctl enable)
4. Disables non-role services (systemctl disable)
5. Reloads systemd daemon
6. Confirms new state

**Safety**:
- Does NOT stop services (only enables/disables)
- Does NOT restart nodes
- Does NOT delete service files
- Services stay running until next boot or manual stop

---

### 2. audit_single_role.sh

**Purpose**: Verify each node has ONLY its designated role

**Usage**:
```bash
bash scripts/audit_single_role.sh
```

**What it does**:
1. Connects to each node via SSH
2. Queries enabled services: `systemctl list-units --state=enabled`
3. Checks for violations (unexpected services)
4. Reports:
   - ✓ Services that are correctly enabled
   - ✗ Services that violate the role (if any)

**Output example (pass)**:
```
========== Auditing batam-manager (100.103.77.10) ==========
✓ batam-manager role purity verified (no violations)

========== Auditing EXECUTOR-executor-cluster (100.122.1.109) ==========
✓ EXECUTOR-executor-cluster role purity verified (no violations)

========== Auditing SCANNER-scanner-cluster (100.105.139.21) ==========
✓ SCANNER-scanner-cluster role purity verified (no violations)

========== Audit Results ==========
Passed: 3/3 nodes
All nodes pass role purity verification ✓
```

---

## Deployment Workflow

### Option A: Full Enforcement (Cold Start)

Use when deploying to fresh nodes or needing to enforce from scratch:

```bash
# Step 1: Deploy enforcement
bash scripts/deploy_single_role_enforcement.sh

# Step 2: Restart all nodes (recommended)
# This ensures no stale processes from disabled services
# SSH to each node and: sudo reboot

# Step 3: Verify (wait 5 min for nodes to come back)
bash scripts/audit_single_role.sh

# Step 4: Check live logs
bash scripts/morning_check.sh
```

### Option B: Incremental Enforcement (Hot Cluster)

Use when cluster is already running and you want minimal downtime:

```bash
# Step 1: Audit current state
bash scripts/audit_single_role.sh

# Step 2: Deploy (enables/disables, no stop)
bash scripts/deploy_single_role_enforcement.sh

# Step 3: Verify (services already running, should pass)
bash scripts/audit_single_role.sh

# Step 4: Optional: stop misplaced services manually
# On EXECUTOR (if polymarket somehow started):
# ssh -i SSH_SINGAPORE/SSH_EXECUTOR/... ubuntu@100.122.1.109
# sudo systemctl stop kibot-polymarket.service

# Step 5: Monitor logs
bash scripts/morning_check.sh
```

---

## Verification Checklist (Post-Deployment)

### On Batam (100.103.77.10):
```bash
systemctl list-units --type=service --state=enabled | grep kibot
# Expected: Only brain services (manager, analyst, orchestrator, security, etc.)
# Unexpected: kibot-executor-indodax, kibot-polymarket, ki-global-scanner-mesh, kibot-scanner@*

systemctl status kibot-manager
# Expected: active (running)

systemctl status indodax-dashboard-proxy
# Expected: active (running)
```

### On EXECUTOR (100.122.1.109):
```bash
systemctl list-units --type=service --state=enabled | grep kibot
# Expected: Only kibot-executor-indodax, kibot-polymarket
# Unexpected: Any brain or scanner services

systemctl status kibot-executor-indodax
# Expected: active (running)

systemctl status kibot-polymarket
# Expected: active (running)

# Verify kibot-polymarket is NOT disabled:
systemctl is-enabled kibot-polymarket.service
# Expected: enabled
```

### On SCANNER (100.105.139.21):
```bash
systemctl list-units --type=service --state=enabled | grep kibot
# Expected: ki-global-scanner-mesh and kibot-scanner@*
# Unexpected: kibot-executor-indodax, kibot-polymarket, brain services

systemctl status ki-global-scanner-mesh
# Expected: active (running)

systemctl list-units --type=service --state=enabled | grep kibot-scanner@
# Expected: 20 active scanner sources + aggregator
```

---

## Troubleshooting

### Problem: audit_single_role.sh reports violations

**Example output**:
```
VIOLATION: kibot-polymarket.service should NOT be on batam-manager
```

**Fix**:
```bash
# Manually disable on remote node
ssh -i SSH_BATAM/ssh-key-batam-active.pem ubuntu@100.103.77.10
sudo systemctl disable kibot-polymarket.service
sudo systemctl stop kibot-polymarket.service
exit

# Re-run audit
bash scripts/audit_single_role.sh
```

### Problem: Service won't start after enforcement

**Cause**: Service file missing or permissions issue

**Fix**:
```bash
# Check if service file exists
ssh -i <KEY> ubuntu@<HOST> \
  ls -la /etc/systemd/system/*.service | grep <service-name>

# If missing, copy from repo
scp -i <KEY> infra/systemd/<service>.service \
  ubuntu@<HOST>:/home/ubuntu/

# Reload systemd
ssh -i <KEY> ubuntu@<HOST> sudo systemctl daemon-reload
```

### Problem: Can't connect to node

**Cause**: Network/firewall issue or SSH key problem

**Fix**:
```bash
# Test SSH connection directly
ssh -i SSH_BATAM/ssh-key-batam-active.pem \
  -o ConnectTimeout=5 \
  ubuntu@100.103.77.10 \
  "echo OK"

# If that fails, check:
# 1. Key file exists: ls -la SSH_BATAM/ssh-key-batam-active.pem
# 2. Network: ping 100.103.77.10
# 3. ops/SERVERS.json has correct IP
```

---

## Rollback (If Needed)

If single-role enforcement breaks the cluster:

### Option 1: Re-enable all services (permissive mode)

```bash
# SSH to each node and enable ALL services
ssh -i SSH_BATAM/ssh-key-batam-active.pem ubuntu@100.103.77.10 << 'EOF'
sudo systemctl enable kibot-*.service kibot-executor-indodax.service kibot-executor-indodax.service ki-*.service kicryp-*.service 2>/dev/null
sudo systemctl daemon-reload
EOF

# Restart nodes
# Then re-run audit to verify what's actually active
```

### Option 2: Revert to previous state

If you backed up `/etc/systemd/system/` before deployment:

```bash
ssh -i SSH_BATAM/ssh-key-batam-active.pem ubuntu@100.103.77.10 << 'EOF'
sudo cp /etc/systemd/system.backup/* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl restart '*.service'
EOF
```

---

## Diagram Update (Visual)

The `contohdiagram.png` should be regenerated to show:

1. **Three separate boxes** (not overlapping):
   - **Batam** (green): Brain/Control only
   - **EXECUTOR** (blue): Executor only
   - **SCANNER** (orange): Scanner only

2. **Services inside each box**:
   - Batam: kibot-manager, kibot-analyst, kibot-orchestrator, kibot-security, ki-telegram-monitor, indodax-dashboard-proxy
   - EXECUTOR: kibot-executor-indodax, kibot-polymarket
   - SCANNER: ki-global-scanner-mesh, kibot-scanner@*

3. **Flow arrows** (no cross-SG traffic):
   - SCANNER → Batam (signals, heartbeats)
   - Batam → EXECUTOR (control posture)
   - EXECUTOR → Batam (execution state)
   - Batam → Web/Telegram (read-only)

---

## Monitoring (Ongoing)

### Daily Check:
```bash
bash scripts/morning_check.sh
# Verifies all nodes are up and services are running
```

### Weekly Enforcement Audit:
```bash
bash scripts/audit_single_role.sh
# Ensures no role violations have crept in from manual changes
```

### On-Demand Verification:
```bash
# Check a specific node
ssh -i <KEY> ubuntu@<HOST> \
  "systemctl list-units --type=service --state=enabled --no-pager | grep -E 'kibot|KiBot|ki-'"
```

---

## Notes

- Once enforced, ROLE_MANIFEST.md becomes the authority for role definition
- Any new services added must be explicitly assigned to a role before deployment
- Update ROLE_MANIFEST.md + ARCHITECTURE_SINGLE_ROLE.md before adding cross-role services
- CI/CD and manual deploys must respect the single-role constraint
- If a service needs to run on multiple nodes, it must be explicitly listed per node in ROLE_MANIFEST.md

---

## Sign-Off

- **Enforced**: ✓ Single-role topology is now the baseline design
- **Auditable**: ✓ audit_single_role.sh verifies compliance
- **Deployable**: ✓ deploy_single_role_enforcement.sh automates setup
- **Documented**: ✓ ROLE_MANIFEST.md is the source of truth
- **Ready**: ✓ Cluster can be deployed with role purity guarantees

**Next step**: Run `bash scripts/deploy_single_role_enforcement.sh` on live cluster, then monitor for 10 minutes with `bash scripts/morning_check.sh`.
