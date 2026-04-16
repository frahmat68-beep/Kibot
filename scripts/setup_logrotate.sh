#!/usr/bin/env bash
# KiCryp Trinity - Logrotate Setup Script
# Prevents disk full by rotating logs daily in /var/log/kicryp/
# 
# Usage:
#   sudo bash scripts/setup_logrotate.sh
#
# This script:
# - Creates /var/log/kicryp/ directory if not exists
# - Configures logrotate for daily rotation
# - Keeps last 3 days of logs
# - Compresses old logs with gzip
# - Limits file size to 50MB max

set -euo pipefail

LOGROTATE_CONF="/etc/logrotate.d/kicryp"
LOG_DIR="/var/log/kicryp"

echo "==================================="
echo "KiCryp Logrotate Setup"
echo "==================================="

# Check if running as root
if [[ $EUID -ne 0 ]]; then
   echo "❌ ERROR: This script must be run as root (use sudo)" 
   exit 1
fi

# Create log directory if not exists
if [[ ! -d "$LOG_DIR" ]]; then
    echo "📁 Creating log directory: $LOG_DIR"
    mkdir -p "$LOG_DIR"
    chown -R ubuntu:ubuntu "$LOG_DIR"
    chmod 755 "$LOG_DIR"
else
    echo "✅ Log directory already exists: $LOG_DIR"
fi

# Create logrotate configuration
echo "📝 Creating logrotate configuration: $LOGROTATE_CONF"
cat > "$LOGROTATE_CONF" << 'EOF'
# KiCryp Trinity - Logrotate Configuration
# Prevents disk full in degraded mode (local logging)

/var/log/kicryp/*.log {
    # Rotate daily (or when file reaches 50MB, whichever comes first)
    daily
    size 50M
    
    # Keep last 3 days of logs (prevents disk full on Oracle Cloud free tier)
    rotate 3
    
    # Compress old logs with gzip (saves ~90% space)
    compress
    delaycompress
    
    # Don't error if log file is missing
    missingok
    
    # Don't rotate if log is empty
    notifempty
    
    # Create new log file with correct permissions after rotation
    create 0644 ubuntu ubuntu
    
    # Use date as suffix for rotated files (e.g., kicryp.log-20260406)
    dateext
    dateformat -%Y%m%d
    
    # Rotate even if multiple logs match the pattern
    sharedscripts
}

# Also rotate JSON fallback logs (degraded mode)
/var/log/kicryp/*.json {
    daily
    size 50M
    rotate 3
    compress
    delaycompress
    missingok
    notifempty
    create 0644 ubuntu ubuntu
    dateext
    dateformat -%Y%m%d
}
EOF

# Set correct permissions for logrotate config
chmod 644 "$LOGROTATE_CONF"

# Test logrotate configuration
echo "🧪 Testing logrotate configuration..."
if logrotate -d "$LOGROTATE_CONF" 2>&1 | grep -q "error"; then
    echo "❌ ERROR: Logrotate configuration has errors!"
    logrotate -d "$LOGROTATE_CONF"
    exit 1
else
    echo "✅ Logrotate configuration is valid"
fi

# Force initial rotation (dry run to test)
echo "🔄 Testing manual rotation (dry run)..."
logrotate -d -f "$LOGROTATE_CONF" > /dev/null 2>&1 || true

# Show current disk usage
echo ""
echo "💾 Current disk usage:"
df -h "$LOG_DIR" | tail -1

echo ""
echo "==================================="
echo "✅ Logrotate Setup Complete!"
echo "==================================="
echo ""
echo "Configuration file: $LOGROTATE_CONF"
echo "Log directory: $LOG_DIR"
echo ""
echo "Logrotate schedule:"
echo "  - Rotation: Daily (or when file > 50MB)"
echo "  - Retention: 3 days"
echo "  - Compression: gzip (saves ~90% space)"
echo "  - Max file size: 50MB"
echo ""
echo "Manual rotation:"
echo "  sudo logrotate -f $LOGROTATE_CONF"
echo ""
echo "Check status:"
echo "  cat /var/lib/logrotate/status | grep kicryp"
echo ""
echo "View compressed logs:"
echo "  zcat /var/log/kicryp/kicryp-manager.log-20260406.gz"
echo ""
echo "Monitor disk usage:"
echo "  du -sh $LOG_DIR"
echo "  df -h $LOG_DIR"
echo ""
