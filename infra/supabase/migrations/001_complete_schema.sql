-- =============================================
-- KIBOT TRINITY — Complete Supabase Schema
-- Project: vptlelbgyxwieyfdpuja
-- Filosofi: SURVIVAL FIRST, COMPOUNDING GRADUAL
-- =============================================

-- Trade history dengan rolling 30-hari
CREATE TABLE IF NOT EXISTS trade_history (
    id                BIGSERIAL PRIMARY KEY,
    pair_id           TEXT NOT NULL,
    category          TEXT NOT NULL DEFAULT 'LEAD_LAG', -- LEAD_LAG | INDODAX_ONLY | FUTURES_PROXY
    entry_price       DECIMAL(20,8) NOT NULL,
    exit_price        DECIMAL(20,8),
    budget_idr        DECIMAL(15,2) NOT NULL,
    pnl_idr           DECIMAL(15,2),
    pnl_pct           DECIMAL(8,4),
    order_type_entry  TEXT NOT NULL DEFAULT 'LIMIT',   -- LIMIT | MARKET
    order_type_exit   TEXT DEFAULT 'LIMIT',
    pump_phase        TEXT,                             -- EARLY | MID | LATE | PEAK
    pump_score        DECIMAL(5,1),
    hold_minutes      INTEGER,
    win               BOOLEAN,
    exit_reason       TEXT,                             -- TRAILING_STOP | PARTIAL_TP | HARD_STOP | PEAK_EXIT | TIME_EXIT
    bucket_type       TEXT DEFAULT 'STABLE',            -- STABLE | AGGRESSIVE
    entry_at          TIMESTAMPTZ DEFAULT NOW(),
    exit_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ DEFAULT NOW()
);

-- Pair memory — pembelajaran per pair
CREATE TABLE IF NOT EXISTS pair_memory (
    id                BIGSERIAL PRIMARY KEY,
    pair_id           TEXT NOT NULL UNIQUE,
    category          TEXT NOT NULL DEFAULT 'LEAD_LAG',
    total_trades      INTEGER DEFAULT 0,
    win_count         INTEGER DEFAULT 0,
    loss_count        INTEGER DEFAULT 0,
    win_rate          DECIMAL(5,3) DEFAULT 0.5,
    avg_win_idr       DECIMAL(15,2) DEFAULT 0,
    avg_loss_idr      DECIMAL(15,2) DEFAULT 0,
    avg_hold_minutes  INTEGER DEFAULT 0,
    profit_factor     DECIMAL(6,3) DEFAULT 1.0,
    avg_slippage_pct  DECIMAL(6,4) DEFAULT 0.012,
    best_hour_wib     INTEGER,                          -- jam WIB dengan win rate terbaik
    worst_hour_wib    INTEGER,
    fake_pump_count   INTEGER DEFAULT 0,               -- berapa kali pump palsu
    consecutive_losses INTEGER DEFAULT 0,
    last_trade_at     TIMESTAMPTZ,
    cooldown_until    TIMESTAMPTZ,                     -- pair di-cooldown sampai kapan
    updated_at        TIMESTAMPTZ DEFAULT NOW()
);

-- PnL snapshots per 30 menit
CREATE TABLE IF NOT EXISTS performance_snapshots (
    id                BIGSERIAL PRIMARY KEY,
    snapshot_at       TIMESTAMPTZ DEFAULT NOW(),
    equity_idr        DECIMAL(15,2),
    daily_pnl_idr     DECIMAL(15,2),
    daily_pnl_pct     DECIMAL(8,4),
    pnl_state         TEXT,         -- HEALTHY | WARNING | CRITICAL | HARD_STOP
    win_rate_today    DECIMAL(5,3),
    ev_per_trade_idr  DECIMAL(10,2),
    trades_today      INTEGER DEFAULT 0,
    action_taken      TEXT,         -- CONTINUE | DEFENSIVE | TIGHTEN | PREPARE_STOP
    threshold_mult    DECIMAL(4,2) DEFAULT 1.0,
    active_positions  INTEGER DEFAULT 0
);

-- Capital allocation tracking
CREATE TABLE IF NOT EXISTS capital_allocation (
    id                BIGSERIAL PRIMARY KEY,
    recorded_at       TIMESTAMPTZ DEFAULT NOW(),
    total_equity_idr  DECIMAL(15,2),
    stable_idr        DECIMAL(15,2),
    aggressive_idr    DECIMAL(15,2),
    stable_pct        DECIMAL(5,3),
    aggressive_pct    DECIMAL(5,3),
    drift_pct         DECIMAL(5,3),
    rebalanced        BOOLEAN DEFAULT FALSE
);

-- AI-CMS coin discovery log
CREATE TABLE IF NOT EXISTS coin_discovery_log (
    id                BIGSERIAL PRIMARY KEY,
    discovered_at     TIMESTAMPTZ DEFAULT NOW(),
    pair_id           TEXT NOT NULL,
    category          TEXT NOT NULL,
    reason            TEXT,
    urgency           TEXT,         -- NOW | WATCH | MONITOR
    vol_24h_idr       DECIMAL(15,2),
    validated         BOOLEAN DEFAULT FALSE,
    added_to_watchlist BOOLEAN DEFAULT FALSE
);

-- Daily summary
CREATE TABLE IF NOT EXISTS daily_summary (
    id                BIGSERIAL PRIMARY KEY,
    trade_date        DATE NOT NULL UNIQUE,
    start_equity_idr  DECIMAL(15,2),
    end_equity_idr    DECIMAL(15,2),
    total_pnl_idr     DECIMAL(15,2),
    total_pnl_pct     DECIMAL(8,4),
    total_trades      INTEGER DEFAULT 0,
    win_count         INTEGER DEFAULT 0,
    loss_count        INTEGER DEFAULT 0,
    win_rate          DECIMAL(5,3),
    hard_stop_hit     BOOLEAN DEFAULT FALSE,
    best_pair         TEXT,
    worst_pair        TEXT,
    created_at        TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes
CREATE INDEX ON trade_history (pair_id, created_at DESC);
CREATE INDEX ON trade_history (created_at DESC);
CREATE INDEX ON trade_history (win, category);
CREATE INDEX ON pair_memory (pair_id);
CREATE INDEX ON performance_snapshots (snapshot_at DESC);
CREATE INDEX ON daily_summary (trade_date DESC);

-- Auto-delete data >30 hari via function
CREATE OR REPLACE FUNCTION cleanup_old_data() RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM trade_history WHERE created_at < NOW() - INTERVAL '30 days';
    DELETE FROM performance_snapshots WHERE snapshot_at < NOW() - INTERVAL '30 days';
    DELETE FROM capital_allocation WHERE recorded_at < NOW() - INTERVAL '30 days';
    DELETE FROM coin_discovery_log WHERE discovered_at < NOW() - INTERVAL '7 days';
END;
$$;
