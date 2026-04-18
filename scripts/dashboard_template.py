DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>KiBot Trinity v7.0 | Global Command Center</title>
    <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg: #050608;
            --card-bg: rgba(255, 255, 255, 0.03);
            --card-border: rgba(255, 255, 255, 0.08);
            --accent: #00ffa3;
            --accent-glow: rgba(0, 255, 163, 0.2);
            --danger: #ff4d4d;
            --danger-glow: rgba(255, 77, 77, 0.2);
            --warning: #ffb800;
            --text-primary: #f8fafc;
            --text-secondary: #94a3b8;
            --glass: rgba(255, 255, 255, 0.02);
            --msc-low: #94a3b8;
            --msc-mid: #00d2ff;
            --msc-high: #00ffa3;
        }

        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
            font-family: 'Outfit', sans-serif;
        }

        body {
            background-color: var(--bg);
            color: var(--text-primary);
            min-height: 100vh;
            overflow-x: hidden;
            background-image:
                radial-gradient(circle at 0% 0%, rgba(0, 255, 163, 0.05) 0%, transparent 50%),
                radial-gradient(circle at 100% 100%, rgba(0, 210, 255, 0.05) 0%, transparent 50%);
        }

        .container {
            max-width: 1600px;
            margin: 0 auto;
            padding: 2rem;
        }

        /* ── Header ──────────────────────────────── */
        header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 3rem;
        }

        .logo-area {
            display: flex;
            flex-direction: column;
        }

        .logo-area h1 {
            font-size: 1.75rem;
            font-weight: 800;
            letter-spacing: -1px;
            background: linear-gradient(135deg, #fff 0%, var(--accent) 100%);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            margin-bottom: 0.25rem;
        }

        .logo-area .version {
            font-family: 'JetBrains Mono', monospace;
            font-size: 0.7rem;
            color: var(--text-secondary);
            text-transform: uppercase;
            letter-spacing: 2px;
            background: var(--glass);
            padding: 2px 8px;
            border-radius: 4px;
            width: fit-content;
        }

        .system-status {
            display: flex;
            gap: 1rem;
        }

        .status-pill {
            background: var(--card-bg);
            border: 1px solid var(--card-border);
            padding: 0.5rem 1rem;
            border-radius: 99px;
            display: flex;
            align-items: center;
            gap: 0.75rem;
            font-size: 0.75rem;
            font-weight: 600;
            backdrop-filter: blur(10px);
        }

        .dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            position: relative;
        }

        .dot::after {
            content: '';
            position: absolute;
            top: 0; left: 0; width: 100%; height: 100%;
            border-radius: 50%;
            background: inherit;
            filter: blur(4px);
            animation: pulse 2s infinite;
        }

        .dot-green { background: var(--accent); color: var(--accent); }
        .dot-red { background: var(--danger); color: var(--danger); }
        .dot-orange { background: var(--warning); color: var(--warning); }

        @keyframes pulse {
            0% { transform: scale(1); opacity: 0.8; }
            50% { transform: scale(1.5); opacity: 0; }
            100% { transform: scale(1); opacity: 0.8; }
        }

        /* ── Grid Layout ────────────────────────── */
        .dashboard-grid {
            display: grid;
            grid-template-columns: repeat(12, 1fr);
            gap: 1.5rem;
        }

        .span-3 { grid-column: span 3; }
        .span-4 { grid-column: span 4; }
        .span-6 { grid-column: span 6; }
        .span-8 { grid-column: span 8; }
        .span-12 { grid-column: span 12; }

        /* ── Card Styles ────────────────────────── */
        .card {
            background: var(--card-bg);
            border: 1px solid var(--card-border);
            border-radius: 1.5rem;
            padding: 1.5rem;
            backdrop-filter: blur(20px);
            transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
        }

        .card:hover {
            border-color: rgba(255, 255, 255, 0.15);
            background: rgba(255, 255, 255, 0.04);
        }

        .card-title {
            font-size: 0.85rem;
            font-weight: 600;
            color: var(--text-secondary);
            margin-bottom: 1rem;
            display: flex;
            align-items: center;
            gap: 0.5rem;
            text-transform: uppercase;
            letter-spacing: 1px;
        }

        .stat-value {
            font-family: 'JetBrains Mono', monospace;
            font-size: 1.75rem;
            font-weight: 700;
            margin-bottom: 0.5rem;
        }

        .trend {
            font-size: 0.85rem;
            display: flex;
            align-items: center;
            gap: 0.25rem;
        }

        /* ── Multi-Scanner Radar ────────────────── */
        .scanner-list {
            display: flex;
            flex-direction: column;
            gap: 0.75rem;
        }

        .scanner-item {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 0.75rem;
            background: rgba(255, 255, 255, 0.02);
            border-radius: 0.75rem;
            font-size: 0.85rem;
        }

        .scanner-name { font-weight: 600; font-family: 'JetBrains Mono', monospace; }
        .scanner-status { font-size: 0.7rem; color: var(--accent); }

        /* ── Position Table ─────────────────────── */
        table {
            width: 100%;
            border-collapse: collapse;
            font-size: 0.85rem;
        }

        th {
            text-align: left;
            color: var(--text-secondary);
            font-weight: 500;
            padding: 1rem;
            border-bottom: 1px solid var(--card-border);
        }

        td {
            padding: 1rem;
            border-bottom: 1px solid var(--card-border);
        }

        .pair-cell { font-weight: 700; font-family: 'JetBrains Mono', monospace; text-transform: uppercase; }
        .bucket-badge {
            font-size: 0.65rem;
            padding: 2px 6px;
            border-radius: 4px;
            background: rgba(255, 255, 255, 0.05);
            color: var(--text-secondary);
        }

        /* ── Resource Gauges ─────────────────────── */
        .gauge-container {
            margin-top: 1rem;
        }

        .gauge-label {
            display: flex;
            justify-content: space-between;
            font-size: 0.75rem;
            margin-bottom: 0.5rem;
        }

        .gauge-track {
            height: 8px;
            background: rgba(255, 255, 255, 0.05);
            border-radius: 99px;
            overflow: hidden;
        }

        .gauge-fill {
            height: 100%;
            border-radius: 99px;
            transition: all 1s ease;
        }

        /* ── Responsive ─────────────────────────── */
        @media (max-width: 1200px) {
            .span-3, .span-4, .span-6, .span-8 { grid-column: span 12; }
        }

        #error-overlay {
            position: fixed;
            top: 0; left: 0; width: 100%; height: 100%;
            background: rgba(5, 6, 8, 0.9);
            display: none;
            justify-content: center;
            align-items: center;
            z-index: 9999;
            backdrop-filter: blur(20px);
        }

        .error-message {
            background: var(--bg);
            border: 1px solid var(--danger);
            padding: 3rem;
            border-radius: 2rem;
            text-align: center;
            box-shadow: 0 0 50px var(--danger-glow);
        }

    </style>
</head>
<body>
    <div id="error-overlay">
        <div class="error-message">
            <h2 style="color: var(--danger); margin-bottom: 1rem; font-size: 2rem;">CORE DISCONNECTED</h2>
            <p style="color: var(--text-secondary);">Attempting emergency reconnection to KiBot SG-Node...</p>
        </div>
    </div>

    <div class="container">
        <header>
            <div class="logo-area">
                <h1>KIBOT TRINITY</h1>
                <div class="version">V7.0 CODEX MASTER</div>
            </div>
            <div class="system-status">
                <div class="status-pill">
                    <div id="msc-dot" class="dot dot-green"></div>
                    <span>MSC ENGINE: <span id="msc-status">ACTIVE</span></span>
                </div>
                <div class="status-pill">
                    <div id="risk-dot" class="dot dot-green"></div>
                    <span>RISK GUARD: <span id="risk-status">ARMED</span></span>
                </div>
            </div>
        </header>

        <div class="dashboard-grid">
            <!-- ── Top Stats ────────── -->
            <div class="card span-3">
                <div class="card-title">Portfolio Equity</div>
                <div id="val-equity" class="stat-value">Rp ---.---</div>
                <div id="val-equity-trend" class="trend pnl-positive">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="m5 12 7-7 7 7M12 19V5"/></svg>
                    <span>+2.4% Est.</span>
                </div>
            </div>

            <div class="card span-3">
                <div class="card-title">Daily Realized PnL</div>
                <div id="val-daily-pnl" class="stat-value">0.00%</div>
                <div id="val-pnl-idr" style="font-size: 0.85rem; color: var(--text-secondary);">Rp 0</div>
            </div>

            <div class="card span-3">
                <div class="card-title">Profit Locked</div>
                <div id="val-locked" class="stat-value">Rp 0</div>
                <div style="font-size: 0.85rem; color: var(--text-secondary);">Target: 30% of Gains</div>
            </div>

            <div class="card span-3">
                <div class="card-title">MSC Avg Confidence</div>
                <div id="val-msc-avg" class="stat-value" style="color: var(--accent);">0.84</div>
                <div style="font-size: 0.85rem; color: var(--text-secondary);">Global Radar Sync</div>
            </div>

            <!-- ── Main View ────────── -->
            <div class="card span-8">
                <div class="card-title">Active Positions & Partial TP Tracker</div>
                <table id="pos-table">
                    <thead>
                        <tr>
                            <th>Pair</th>
                            <th>Entry</th>
                            <th>PnL%</th>
                            <th>TP Stage</th>
                            <th>Risk Buffer</th>
                        </tr>
                    </thead>
                    <tbody id="pos-body">
                        <!-- Positions injected by JS -->
                    </tbody>
                </table>
            </div>

            <div class="card span-4">
                <div class="card-title">Multi-Scanner Radar (Tokyo)</div>
                <div class="scanner-list" id="scanner-list">
                    <div class="scanner-item">
                        <span class="scanner-name">BINANCE</span>
                        <span class="scanner-status">READY <small>(W=0.30)</small></span>
                    </div>
                    <div class="scanner-item">
                        <span class="scanner-name">BYBIT</span>
                        <span class="scanner-status">READY <small>(W=0.25)</small></span>
                    </div>
                    <div class="scanner-item">
                        <span class="scanner-name">KUCOIN</span>
                        <span class="scanner-status">READY <small>(W=0.20)</small></span>
                    </div>
                    <div class="scanner-item">
                        <span class="scanner-name">CRYPTO.COM</span>
                        <span class="scanner-status">READY <small>(W=0.15)</small></span>
                    </div>
                    <div class="scanner-item">
                        <span class="scanner-name">MEXC</span>
                        <span class="scanner-status">READY <small>(W=0.10)</small></span>
                    </div>
                </div>

                <div class="card-title" style="margin-top: 2rem;">Capital Allocation</div>
                <div class="gauge-container">
                    <div class="gauge-label">
                        <span>LEAD_LAG BUCKET</span>
                        <span id="label-bucket-ll">50%</span>
                    </div>
                    <div class="gauge-track">
                        <div id="fill-bucket-ll" class="gauge-fill" style="width: 50%; background: var(--accent);"></div>
                    </div>
                </div>
                <div class="gauge-container">
                    <div class="gauge-label">
                        <span>LOCAL_PUMP BUCKET</span>
                        <span id="label-bucket-lp">50%</span>
                    </div>
                    <div class="gauge-track">
                        <div id="fill-bucket-lp" class="gauge-fill" style="width: 50%; background: var(--msc-mid);"></div>
                    </div>
                </div>
                <div class="gauge-container">
                    <div class="gauge-label">
                        <span style="color: var(--danger);">DAILY LOSS LIMIT</span>
                        <span id="label-loss-limit">0.0% / 3.0%</span>
                    </div>
                    <div class="gauge-track">
                        <div id="fill-loss-limit" class="gauge-fill" style="width: 5%; background: var(--danger);"></div>
                    </div>
                </div>
            </div>

        </div>

        <div style="margin-top: 2rem; border-top: 1px solid var(--card-border); padding-top: 1rem; display: flex; justify-content: space-between; font-size: 0.75rem; color: var(--text-secondary);">
            <div>Auto-Refresh: <span id="refresh-counter">5</span>s</div>
            <div>Node-SG: Healthy | Gateway: Real-Time</div>
            <div id="clock">00:00:00</div>
        </div>
    </div>

    <script>
        async function updateData() {
            try {
                const resp = await fetch('/api/state');
                const data = await resp.json();
                document.getElementById('error-overlay').style.display = 'none';

                // Update Stats
                const equity = data.capital?.total_equity || data.portfolio?.total_equity_idr || 0;
                document.getElementById('val-equity').textContent = `Rp ${Math.round(equity).toLocaleString('id-ID')}`;

                const pnl = data.risk?.daily_pnl_pct || 0;
                document.getElementById('val-daily-pnl').textContent = `${(pnl * 100).toFixed(2)}%`;
                document.getElementById('val-daily-pnl').style.color = pnl >= 0 ? 'var(--accent)' : 'var(--danger)';

                const locked = data.capital?.locked_profit || 0;
                document.getElementById('val-locked').textContent = `Rp ${Math.round(locked).toLocaleString('id-ID')}`;

                // Update Table
                const tbody = document.getElementById('pos-body');
                tbody.innerHTML = '';
                const positions = data.portfolio?.positions || [];

                if (positions.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; color:var(--text-secondary); padding: 3rem;">No Active Positions - Radar Scanning Universe...</td></tr>';
                }

                positions.forEach(p => {
                    const tr = document.createElement('tr');
                    tr.innerHTML = `
                        <td class="pair-cell">${p.pairId} <span class="bucket-badge">${p.bucket || 'v7'}</span></td>
                        <td>Rp ${Math.round(p.entryPrice).toLocaleString()}</td>
                        <td style="color: ${p.profitPct >= 0 ? 'var(--accent)' : 'var(--danger)'}; font-weight: 700;">
                            ${(p.profitPct * 100).toFixed(2)}%
                        </td>
                        <td>
                            <div style="font-size: 0.65rem; color: var(--text-secondary);">TP STAGE: ${p.tpStage || 0}/3</div>
                            <div class="gauge-track" style="height: 4px; width: 60px; margin-top: 4px;">
                                <div class="gauge-fill" style="width: ${(p.tpStage || 0) * 33.3}%; background: var(--accent);"></div>
                            </div>
                        </td>
                        <td>${(p.riskBuffer || 12).toFixed(1)}h left</td>
                    `;
                    tbody.appendChild(tr);
                });

                // Update Gauges
                const llBucket = data.capital?.buckets?.LEAD_LAG?.pct || 50;
                document.getElementById('fill-bucket-ll').style.width = `${llBucket}%`;
                document.getElementById('label-bucket-ll').textContent = `${llBucket.toFixed(0)}%`;

                const lpBucket = data.capital?.buckets?.LOCAL_PUMP?.pct || 50;
                document.getElementById('fill-bucket-lp').style.width = `${lpBucket}%`;
                document.getElementById('label-bucket-lp').textContent = `${lpBucket.toFixed(0)}%`;

                const currentLoss = Math.abs(data.risk?.daily_pnl_pct || 0) * 100;
                document.getElementById('fill-loss-limit').style.width = `${(currentLoss / 3.0) * 100}%`;
                document.getElementById('label-loss-limit').textContent = `${currentLoss.toFixed(2)}% / 3.0%`;

            } catch (err) {
                document.getElementById('error-overlay').style.display = 'flex';
            }
        }

        setInterval(updateData, 5000);
        setInterval(() => {
            document.getElementById('clock').textContent = new Date().toLocaleTimeString();
        }, 1000);
        updateData();
    </script>
</body>
</html>
"""
