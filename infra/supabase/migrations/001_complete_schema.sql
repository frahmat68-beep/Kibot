-- =============================================
-- KIBOT TRINITY v7.0 — Complete Supabase Schema
-- Project: vptlelbgyxwieyfdpuja
-- Filosofi: MATH-FIRST, DUAL-BUCKET, CASCADE-LOSS
-- =============================================

-- Trade history (Unified Bucket A/B logging)
CREATE TABLE IF NOT EXISTS trade_history (
    id                BIGSERIAL PRIMARY KEY,
    trade_id          TEXT UNIQUE NOT NULL,             -- Required by kibot_engine_v2
    pair_id           TEXT NOT NULL,
    bucket            TEXT NOT NULL CHECK (bucket IN ('A','B')),
    entry_price       DECIMAL(20,8) NOT NULL,
    exit_price        DECIMAL(20,8),
    budget_idr        DECIMAL(15,2) NOT NULL,
    pnl_idr           DECIMAL(15,2),
    pnl_pct           DECIMAL(8,5),
    conviction_score  DECIMAL(5,3),
    pump_phase        TEXT,                             -- EARLY | MID | LATE | PEAK
    order_type_entry  TEXT NOT NULL DEFAULT 'LIMIT',
    order_type_exit   TEXT DEFAULT 'LIMIT',
    cascade_mode      TEXT DEFAULT 'GROWTH',            -- GROWTH | CAUTION | DEFENSIVE | RESTRICTED | HARD_STOP
    hold_minutes      INTEGER,
    win               BOOLEAN,
    exit_reason       TEXT,                             -- TRAILING_STOP | PARTIAL_TP | HARD_STOP | PEAK_EXIT | VOLUME_CRASH
    entry_at          TIMESTAMPTZ DEFAULT NOW(),
    exit_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ DEFAULT NOW()
);

-- Pair memory (Bayesian-Kelly learning)
CREATE TABLE IF NOT EXISTS pair_memory (
    id                BIGSERIAL PRIMARY KEY,
    pair_id           TEXT NOT NULL UNIQUE,
    total_trades      INTEGER DEFAULT 0,
    wins              INTEGER DEFAULT 0,
    losses            INTEGER DEFAULT 0,
    win_rate          DECIMAL(5,3) DEFAULT 0.5,
    avg_win_idr        DECIMAL(15,2) DEFAULT 0,
    avg_loss_idr       DECIMAL(15,2) DEFAULT 0,
    profit_factor     DECIMAL(6,3) DEFAULT 1.0,
    kelly_fraction    DECIMAL(6,4) DEFAULT 0.05,        -- Dynamic growth factor
    avg_conviction    DECIMAL(5,3) DEFAULT 0.5,
    consecutive_losses INTEGER DEFAULT 0,
    last_trade_at     TIMESTAMPTZ,
    cooldown_until    TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ DEFAULT NOW()
);

-- Performance snapshots (30-minute state tracking)
CREATE TABLE IF NOT EXISTS performance_snapshots (
    id                BIGSERIAL PRIMARY KEY,
    snapshot_at       TIMESTAMPTZ DEFAULT NOW(),
    equity_idr        DECIMAL(15,2),
    bucket_a_idr      DECIMAL(15,2),
    bucket_b_idr      DECIMAL(15,2),
    cash_idr          DECIMAL(15,2),
    daily_pnl_idr     DECIMAL(15,2),
    daily_pnl_pct     DECIMAL(8,4),
    cascade_mode      TEXT DEFAULT 'GROWTH',
    wins_today        INTEGER DEFAULT 0,
    losses_today      INTEGER DEFAULT 0,
    ev_today_idr      DECIMAL(10,2),
    action_taken      TEXT,                             -- CONTINUE | TIGHTEN | OPTIMAL
    active_positions  INTEGER DEFAULT 0
);

-- Post-Mortem Log (Loss Analysis)
CREATE TABLE IF NOT EXISTS post_mortem_log (
    id                BIGSERIAL PRIMARY KEY,
    trade_id          TEXT REFERENCES trade_history(trade_id),
    pair_id           TEXT NOT NULL,
    bucket            TEXT NOT NULL,
    loss_idr          DECIMAL(15,2),
    exit_reason       TEXT,
    classification    TEXT,                             -- TIMING | PEAK_ENTRY | STOP_LOSS
    lesson            TEXT,
    recorded_at       TIMESTAMPTZ DEFAULT NOW()
);

-- Daily summary (Historical tracking)
CREATE TABLE IF NOT EXISTS daily_summary (
    id                BIGSERIAL PRIMARY KEY,
    trade_date        DATE NOT NULL UNIQUE,
    start_equity_idr  DECIMAL(15,2),
    end_equity_idr    DECIMAL(15,2),
    total_pnl_idr     DECIMAL(15,2),
    total_trades      INTEGER DEFAULT 0,
    win_rate          DECIMAL(5,3),
    hard_stop_hit     BOOLEAN DEFAULT FALSE,
    best_pair         TEXT,
    worst_pair        TEXT,
    created_at        TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes
CREATE INDEX ON trade_history (pair_id, created_at DESC);
CREATE INDEX ON trade_history (bucket, win);
CREATE INDEX ON pair_memory (pair_id);
CREATE INDEX ON performance_snapshots (snapshot_at DESC);
CREATE INDEX ON daily_summary (trade_date DESC);

-- Maintenance: Auto-delete data >30 hari
CREATE OR REPLACE FUNCTION cleanup_old_data() RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM trade_history WHERE created_at < NOW() - INTERVAL '30 days';
    DELETE FROM performance_snapshots WHERE snapshot_at < NOW() - INTERVAL '30 days';
    DELETE FROM post_mortem_log WHERE recorded_at < NOW() - INTERVAL '14 days';
END;
$$;
