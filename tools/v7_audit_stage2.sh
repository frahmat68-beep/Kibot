#!/bin/bash
# KiBot v7 Verification Script

MGR="core/kibot_manager.py"

echo "=== KiBot v7 Stage 2 Audit ==="
PASS=0; FAIL=0

chk() {
    local desc="$1" pat="$2" exp="$3"
    local cnt=$(grep -c "$pat" "$MGR" 2>/dev/null || echo 0)
    if [ "$exp" = "nonzero" ] && [ "$cnt" -gt 0 ]; then
        echo "✅ PASS: $desc ($cnt)"
        ((PASS++))
    elif [ "$exp" = "zero" ] && [ "$cnt" = "0" ]; then
        echo "✅ PASS: $desc"
        ((PASS++))
    else
        echo "❌ FAIL: $desc (got=$cnt expect=$exp)"
        ((FAIL++))
    fi
}

# Fix #1: TTL
chk "Signal TTL (800ms)" "STALE_SIGNAL_MS.*800" "nonzero"
chk "Stale signal check in relay" "_is_signal_stale" "nonzero"

# Fix #2: AI Threshold
chk "AI min score (0.58)" "AI_APPROVAL_MIN_SCORE.*0\.58" "nonzero"
chk "AI min net (0.0025)" "AI_APPROVAL_MIN_EXPECTED_NET_PCT.*0\.0025" "nonzero"

# Fix #3: FOMO Guard
chk "FOMO micro-cap (18%)" "18\.0.*Micro-cap" "nonzero"
chk "FOMO mid-cap (12%)" "12\.0.*Mid-cap" "nonzero"

# Fix #4: Central Entry Gate
chk "Central Gate (_can_enter)" "def _can_enter" "nonzero"
chk "Central Egress (_relay_to_kidax)" "def _relay_to_kidax" "nonzero"

# Fix #5: Hard Stop Persistence
chk "Daily state persistence" "_load_daily_state" "nonzero"
chk "pytz timezone usage" "pytz.timezone" "nonzero"

# Fix #6: Quarantine
chk "Quarantine (45 min)" "cooldown_min.*45" "nonzero"
chk "Loss count tracking" "_entry_loss_count" "nonzero"

# Fix #7: AI Health
chk "AI failure streak logic" "_ai_failure_streak" "nonzero"

# Fix #8: Dedup
chk "Signal dedup (90s)" "dedup_s.*90" "nonzero"

# Fix #9: Effective Mode
chk "Risk Mode check (_get_effective_mode)" "def _get_effective_mode" "nonzero"
chk "Full freeze check" "FULL_FREEZE" "nonzero"

echo ""
echo "Audit Result: $PASS/15 passed."
if [ $FAIL -gt 0 ]; then
    echo "🚨 $FAIL checks failed! Audit rejected."
    exit 1
else
    echo "🏁 Audit successful. System hardened."
    exit 0
fi
