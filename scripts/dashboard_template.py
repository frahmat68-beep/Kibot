DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>KiCryp Trinity v7.0 | Command Center</title>
    <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;600;800&family=JetBrains+Mono:wght@400;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg: #0a0b0e;
            --card-bg: rgba(255, 255, 255, 0.03);
            --border: rgba(255, 255, 255, 0.08);
            --accent: #00ffa3;
            --danger: #ff4d4d;
            --warning: #ffb800;
            --text-primary: #ffffff;
            --text-secondary: #94a3b8;
            --glass: rgba(255, 255, 255, 0.02);
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
                radial-gradient(circle at 10% 20%, rgba(0, 255, 163, 0.03) 0%, transparent 40%),
                radial-gradient(circle at 90% 80%, rgba(255, 77, 77, 0.03) 0%, transparent 40%);
        }

        .container {
            max-width: 1400px;
            margin: 0 auto;
            padding: 2rem;
        }

        header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 2rem;
            padding-bottom: 1rem;
            border-bottom: 1px solid var(--border);
        }

        .logo-area h1 {
            font-size: 1.5rem;
            font-weight: 800;
            letter-spacing: -0.5px;
            background: linear-gradient(90deg, #fff, var(--accent));
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }

        .logo-area span {
            font-family: 'JetBrains Mono', monospace;
            font-size: 0.75rem;
            color: var(--text-secondary);
            text-transform: uppercase;
        }

        .system-status {
            display: flex;
            gap: 1.5rem;
            align-items: center;
        }

        .badge {
            padding: 0.4rem 0.8rem;
            border-radius: 99px;
            font-size: 0.7rem;
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            display: flex;
            align-items: center;
            gap: 0.5rem;
        }

        .badge-healthy { background: rgba(0, 255, 163, 0.1); color: var(--accent); border: 1px solid rgba(0, 255, 163, 0.2); }
        .badge-warning { background: rgba(255, 184, 0, 0.1); color: var(--warning); border: 1px solid rgba(255, 184, 0, 0.2); }
        .badge-danger { background: rgba(255, 77, 77, 0.1); color: var(--danger); border: 1px solid rgba(255, 77, 77, 0.2); }

        .led { width: 8px; height: 8px; border-radius: 50%; box-shadow: 0 0 10px currentColor; }
        .blink { animation: pulse 2s infinite; }

        @keyframes pulse {
            0% { opacity: 1; }
            50% { opacity: 0.4; }
            100% { opacity: 1; }
        }

        .grid-stats {
            display: grid;
            grid-template-columns: repeat(4, 1fr);
            gap: 1.5rem;
            margin-bottom: 2rem;
        }

        .stat-card {
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 1.25rem;
            padding: 1.5rem;
            position: relative;
            overflow: hidden;
            transition: transform 0.3s ease, border-color 0.3s ease;
        }

        .stat-card:hover {
            transform: translateY(-4px);
            border-color: rgba(255, 255, 255, 0.15);
        }

        .stat-card .label {
            font-size: 0.8rem;
            color: var(--text-secondary);
            margin-bottom: 0.5rem;
        }

        .stat-card .value {
            font-size: 1.5rem;
            font-weight: 600;
            font-family: 'JetBrains Mono', monospace;
        }

        .grid-main {
            display: grid;
            grid-template-columns: 2fr 1fr;
            gap: 1.5rem;
        }

        .panel {
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 1.5rem;
            padding: 1.5rem;
            height: fit-content;
        }

        .panel-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 1.5rem;
        }

        .panel-title {
            font-size: 1.1rem;
            font-weight: 600;
            display: flex;
            align-items: center;
            gap: 0.75rem;
        }

        .position-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 1rem;
        }

        .pos-card {
            background: rgba(255, 255, 255, 0.02);
            border: 1px solid var(--border);
            border-radius: 1rem;
            padding: 1rem;
            transition: all 0.3s ease;
        }

        .pos-card.empty {
            border: 1px dashed var(--border);
            display: flex;
            flex-direction: column;
            justify-content: center;
            align-items: center;
            color: var(--text-secondary);
            font-style: italic;
            min-height: 120px;
        }

        .pos-card .header {
            display: flex;
            justify-content: space-between;
            margin-bottom: 0.75rem;
        }

        .pos-card .pair {
            font-weight: 600;
            text-transform: uppercase;
        }

        .pos-card .pnl {
            font-family: 'JetBrains Mono', monospace;
            font-size: 0.9rem;
        }

        .pnl-positive { color: var(--accent); }
        .pnl-negative { color: var(--danger); }

        .progress-bar {
            width: 100%;
            height: 6px;
            background: rgba(255, 255, 255, 0.05);
            border-radius: 10px;
            overflow: hidden;
            margin-top: 0.5rem;
        }

        .progress-fill {
            height: 100%;
            border-radius: 10px;
            transition: width 0.5s ease;
        }

        .pair-universe {
            display: flex;
            flex-direction: column;
            gap: 0.75rem;
        }

        .uni-item {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 0.75rem;
            background: rgba(255, 255, 255, 0.02);
            border-radius: 0.75rem;
            font-size: 0.85rem;
        }

        .uni-badge {
            font-size: 0.65rem;
            padding: 0.2rem 0.5rem;
            border-radius: 4px;
            background: var(--border);
            color: var(--text-secondary);
        }

        .footer {
            margin-top: 2rem;
            text-align: center;
            font-size: 0.75rem;
            color: var(--text-secondary);
            padding-bottom: 2rem;
        }

        #error-overlay {
            position: fixed;
            top: 0; left: 0; width: 100%; height: 100%;
            background: rgba(0,0,0,0.8);
            display: none;
            justify-content: center;
            align-items: center;
            z-index: 1000;
            backdrop-filter: blur(10px);
        }

        .error-card {
            background: var(--bg);
            border: 1px solid var(--danger);
            padding: 2rem;
            border-radius: 1rem;
            text-align: center;
        }
    </style>
</head>
<body>
    <div id="error-overlay">
        <div class="error-card">
            <h2 style="color: var(--danger); margin-bottom: 1rem;">CONNECTION LOST</h2>
            <p>Attempting to reconnect to KiCryp Core...</p>
        </div>
    </div>

    <div class="container">
        <header>
            <div class="logo-area">
                <h1>KICRYP TRINITY</h1>
                <span>Enterprise Trading Engine v7.0</span>
            </div>
            <div class="system-status">
                <div id="math-badge" class="badge">
                    <div class="led"></div>
                    <span id="math-mode">INIT</span>
                </div>
                <div id="status-badge" class="badge">
                    <div class="led blink"></div>
                    <span id="system-state">SEARCHING</span>
                </div>
            </div>
        </header>

        <div class="grid-stats">
            <div class="stat-card">
                <div class="label">Total Equity (IDR)</div>
                <div id="stat-equity" class="value">Rp ---.---</div>
            </div>
            <div class="stat-card">
                <div class="label">Daily PnL</div>
                <div id="stat-daily-pnl" class="value">0.00%</div>
            </div>
            <div class="stat-card">
                <div class="label">Active Positions</div>
                <div id="stat-pos-count" class="value">0 / 5</div>
            </div>
            <div class="stat-card">
                <div class="label">AI Discovery</div>
                <div id="stat-ai-cms" class="value">Active</div>
            </div>
        </div>

        <div class="grid-main">
            <div class="panel">
                <div class="panel-header">
                    <div class="panel-title">
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>
                        Portfolio Manager
                    </div>
                </div>
                <div id="position-grid" class="position-grid">
                    <!-- Positions injected here -->
                </div>
            </div>

            <div class="panel">
                <div class="panel-header">
                    <div class="panel-title">
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="m16 12-4-4-4 4M12 16V9"/></svg>
                        Market Universe
                    </div>
                </div>
                <div class="pair_universe">
                    <div class="uni-item">
                        <span>Lead-Lag Pairs</span>
                        <span id="count-lead-lag" class="uni-badge">--</span>
                    </div>
                    <div class="uni-item">
                        <span>Futures Proxy</span>
                        <span id="count-futures" class="uni-badge">--</span>
                    </div>
                    <div class="uni-item">
                        <span>Indodax-Only</span>
                        <span id="count-indodax" class="uni-badge">--</span>
                    </div>
                    <div class="uni-item">
                        <span>Math Review</span>
                        <span id="math-last-result">OK</span>
                    </div>
                </div>
            </div>
        </div>

        <div class="footer">
            KiCryp Trinity v7.0 © 2026 | Local Engine: <span id="local-time">--:--:--</span>
        </div>
    </div>

    <script>
        const API_URL = '/api/state';
        
        async function updateDashboard() {
            try {
                const response = await fetch(API_URL);
                if (!response.ok) throw new Error('API Offline');
                const data = await response.json();
                
                document.getElementById('error-overlay').style.display = 'none';
                
                // Update Badges
                const state = data.system_state || 'HEALTHY';
                const statusBadge = document.getElementById('status-badge');
                document.getElementById('system-state').textContent = state;
                statusBadge.className = 'badge ' + (state === 'HEALTHY' ? 'badge-healthy' : state === 'CRITICAL' ? 'badge-warning' : 'badge-danger');
                
                const mathMode = data.math_review?.last_action || 'BALANCED';
                document.getElementById('math-mode').textContent = mathMode;
                document.getElementById('math-badge').className = 'badge ' + (mathMode === 'BALANCED' ? 'badge-healthy' : 'badge-warning');

                // Update Stats
                const equity = data.capital_health?.total_equity_est_idr || 0;
                document.getElementById('stat-equity').textContent = `Rp ${equity.toLocaleString('id-ID')}`;
                
                const dailyPnl = data.daily_pnl_pct || 0;
                const pnlEl = document.getElementById('stat-daily-pnl');
                pnlEl.textContent = `${(dailyPnl * 100).toFixed(2)}%`;
                pnlEl.className = 'value ' + (dailyPnl >= 0 ? 'pnl-positive' : 'pnl-negative');

                document.getElementById('stat-pos-count').textContent = `${data.portfolio?.active_count || 0} / 5`;
                
                // Stats Universe
                document.getElementById('count-lead-lag').textContent = data.v6_stats?.lead_lag_count || 0;
                document.getElementById('count-futures').textContent = data.v6_stats?.futures_proxy_count || 0;
                document.getElementById('count-indodax').textContent = data.v6_stats?.indodax_only_count || 0;
                document.getElementById('math-last-result').textContent = data.math_review?.last_reason?.split(',')[0] || 'OK';

                // Positions
                const grid = document.getElementById('position-grid');
                grid.innerHTML = '';
                
                const positions = data.portfolio?.positions || [];
                for(let i=0; i<5; i++) {
                    if(positions[i]) {
                        const p = positions[i];
                        const card = document.createElement('div');
                        card.className = 'pos-card';
                        const pnlVal = (p.pnl_pct * 100).toFixed(2);
                        card.innerHTML = `
                            <div class="header">
                                <span class="pair">${p.pair.split('_')[0]} <small style="font-size:0.6rem; color:var(--text-secondary)">${p.category}</small></span>
                                <span class="pnl ${p.pnl_pct >= 0 ? 'pnl-positive' : 'pnl-negative'}">${pnlVal}%</span>
                            </div>
                            <div style="font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 5px;">
                                Entry: Rp ${p.entry_price.toLocaleString()} | Hold: ${p.hold_min.toFixed(0)}m
                            </div>
                            <div class="progress-bar">
                                <div class="progress-fill" style="width: ${Math.min(100, Math.max(0, 50 + p.pnl_pct*500))}%; background: ${p.pnl_pct >= 0 ? 'var(--accent)' : 'var(--danger)'}"></div>
                            </div>
                        `;
                        grid.appendChild(card);
                    } else {
                        const empty = document.createElement('div');
                        empty.className = 'pos-card empty';
                        empty.innerHTML = `<span>Position Slot ${i+1} Available</span>`;
                        grid.appendChild(empty);
                    }
                }

                document.getElementById('local-time').textContent = new Date().toLocaleTimeString();

            } catch (err) {
                document.getElementById('error-overlay').style.display = 'flex';
                console.error('Update failed:', err);
            }
        }

        setInterval(updateDashboard, 5000);
        updateDashboard();
    </script>
</body>
</html>
"""
