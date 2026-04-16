-- KiBot Trinity v7.0 — Complete Schema
-- Project: vptlelbgyxwieyfdpuja.supabase.co

CREATE TABLE IF NOT EXISTS trade_history (
    id                BIGSERIAL PRIMARY KEY,
    trade_id          TEXT UNIQUE NOT NULL,
    pair_id           TEXT NOT NULL,
    bucket            TEXT NOT NULL CHECK (bucket IN ('A','B')),
    category          TEXT NOT NULL DEFAULT 'LEAD_LAG',
    entry_price       DECIMAL(20,8) NOT NULL,
    exit_price       DECIMAL(20,8),
    budget_idr        DECIMAL(15,2) NOT NULL,
    pnl_idr           DECIMAL(15,2),
    pnl_pct           DECIMAL(8,4),
    fee_idr           DECIMAL(10,2),
    order_type_entry  TEXT NOT NULL DEFAULT 'LIMIT',
    order_type_exit   TEXT,
    pump_phase        TEXT,
    conviction_score  DECIMAL(5,3),
    hold_minutes      INTEGER,
    win               BOOLEAN,
    exit_reason       TEXT,
    cascade_mode      TEXT DEFAULT 'GROWTH',
    entry_at          TIMESTAMPTZ DEFAULT NOW(),
    exit_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS pair_memory (
    id                BIGSERIAL PRIMARY KEY,
    pair_id           TEXT NOT NULL UNIQUE,
    bucket            TEXT NOT NULL DEFAULT 'B',
    total_trades      INTEGER DEFAULT 0,
    wins              INTEGER DEFAULT 0,
    losses            INTEGER DEFAULT 0,
    win_rate          DECIMAL(5,3) DEFAULT 0.5,
    avg_win_idr       DECIMAL(10,2) DEFAULT 0,
    avg_loss_idr      DECIMAL(10,2) DEFAULT 0,
    profit_factor     DECIMAL(6,3) DEFAULT 1.0,
    kelly_fraction    DECIMAL(6,4) DEFAULT 0.05,
    consecutive_losses INTEGER DEFAULT 0,
    cooldown_until    TIMESTAMPTZ,
    post_mortem_tags  TEXT[],
    updated_at        TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS performance_snapshots (
    id                BIGSERIAL PRIMARY KEY,
    snapshot_at       TIMESTAMPTZ DEFAULT NOW(),
    equity_idr        DECIMAL(15,2),
    bucket_a_idr      DECIMAL(15,2),
    bucket_b_idr      DECIMAL(15,2),
    daily_pnl_idr     DECIMAL(15,2),
    daily_pnl_pct     DECIMAL(8,4),
    cascade_mode      TEXT DEFAULT 'GROWTH',
    trades_today      INTEGER DEFAULT 0,
    win_rate_today    DECIMAL(5,3),
    ev_today_idr      DECIMAL(10,2),
    action_taken      TEXT
);

CREATE TABLE IF NOT EXISTS post_mortem_log (
    id                BIGSERIAL PRIMARY KEY,
    trade_id          TEXT REFERENCES trade_history(trade_id),
    pair_id           TEXT NOT NULL,
    bucket            TEXT NOT NULL,
    loss_idr          DECIMAL(15,2),
    exit_reason       TEXT,
    conviction_at_exit DECIMAL(5,3),
    classification    TEXT CHECK (classification IN ('TIMING','PEAK_ENTRY','STOP_LOSS','FAKE_PUMP','SPREAD_TRAP')),
    lesson            TEXT,
    recorded_at       TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS daily_summary (
    id                BIGSERIAL PRIMARY KEY,
    trade_date        DATE NOT NULL UNIQUE,
    start_equity_idr  DECIMAL(15,2),
    end_equity_idr    DECIMAL(15,2),
    total_pnl_idr     DECIMAL(15,2),
    total_trades      INTEGER DEFAULT 0,
    wins              INTEGER DEFAULT 0,
    losses            INTEGER DEFAULT 0,
    win_rate          DECIMAL(5,3),
    cascade_mode_end  TEXT,
    hard_stop_hit     BOOLEAN DEFAULT FALSE,
    created_at        TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_trade_history_pair ON trade_history(pair_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_trade_history_bucket ON trade_history(bucket, win);
CREATE INDEX IF NOT EXISTS idx_pair_memory_pair ON pair_memory(pair_id);
CREATE INDEX IF NOT EXISTS idx_snapshots_time ON performance_snapshots(snapshot_at DESC);
CREATE INDEX IF NOT EXISTS idx_daily_date ON daily_summary(trade_date DESC);
