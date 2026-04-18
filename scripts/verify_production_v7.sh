#!/bin/bash
# ═══════════════════════════════════════════════════════════
# KIBOT TRINITY v7 PRODUCTION VERIFIER
# ═══════════════════════════════════════════════════════════

echo "🔍 Starting KiBot Trinity v7 Verification..."

# 1. Component existence
components=(
    "ki_scanner_base.py"
    "ki_binance_scanner.py"
    "ki_bybit_scanner.py"
    "ki_kucoin_scanner.py"
    "ki_cryptocom_scanner.py"
    "ki_mexc_scanner.py"
    "ki_capital_engine.py"
    "multi_scanner_engine.py"
    "kibot_manager.py"
)

for c in "${components[@]}"; do
    if [ -f "$c" ]; then
        echo "✅ Found: $c"
    else
        echo "❌ Missing: $c"
        exit 1
    fi
done

# 2. Syntax check
echo "🧪 Running Syntax Checks (Python)..."
python3 -m py_compile "${components[@]}"
if [ $? -eq 0 ]; then
    echo "✅ Python syntax checks passed."
else
    echo "❌ Python syntax check failed."
    exit 1
fi

# 3. Environment Check
echo "🌍 Checking v7 Environment Variables..."
required_vars=(
    "KIBOT_MSC_MIN_THRESHOLD"
    "KIBOT_HARD_DAILY_LOSS_PCT"
    "KIBOT_LEAD_LAG_BUCKET_PCT"
    "KIBOT_LOCAL_PUMP_BUCKET_PCT"
)

for v in "${required_vars[@]}"; do
    if [ -z "${!v}" ]; then
        echo "⚠️  Missing Env Var: $v (Using defaults or system might fail)"
    else
        echo "✅ Env Var: $v = ${!v}"
    fi
done

# 4. MSC Engine Simulation
echo "🤖 Simulating MSC Engine..."
python3 -c "from multi_scanner_engine import MultiScannerEngine; msc = MultiScannerEngine(); msc.ingest({'exchange': 'BINANCE', 'pair_indodax': 'btc_idr', 'detection_score': 0.8, 'change_24h': 5.0, 'vol_usdt_24h': 100000}); print('MSC Test Result:', msc.compute_msc('btc_idr'))"

echo "🎯 Verification Complete. Ready for deployment."
