-- File: infra/supabase/migrations/003_trade_history.sql

-- Trade history dengan TTL 30 hari
CREATE TABLE IF NOT EXISTS trade_history (
    id           BIGSERIAL PRIMARY KEY,
    pair_id      TEXT NOT NULL,
    entry_price  DECIMAL(20,8),
    exit_price   DECIMAL(20,8),
    budget_idr   DECIMAL(15,2),
    pnl_idr      DECIMAL(15,2),
    pnl_pct      DECIMAL(8,4),
    order_type   TEXT,          -- LIMIT or MARKET
    pump_phase   TEXT,          -- EARLY/MID/LATE
    pump_score   DECIMAL(5,1),
    hold_minutes INTEGER,
    win          BOOLEAN,
    entry_at     TIMESTAMPTZ DEFAULT NOW(),
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

-- Auto-delete setelah 30 hari (via trigger)
CREATE OR REPLACE FUNCTION cleanup_old_trades()
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM trade_history 
    WHERE created_at < NOW() - INTERVAL '30 days';
END;
$$;

-- Pair memory dengan TTL 30 hari
CREATE TABLE IF NOT EXISTS pair_memory_history (
    id           BIGSERIAL PRIMARY KEY,
    pair_id      TEXT NOT NULL,
    win_rate_30d DECIMAL(5,3),
    avg_hold_min INTEGER,
    avg_pnl_idr  DECIMAL(15,2),
    best_hour_wib INTEGER,
    total_trades INTEGER,
    updated_at   TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(pair_id)
);

-- Performance review snapshots (30 menit)
CREATE TABLE IF NOT EXISTS performance_snapshots (
    id         BIGSERIAL PRIMARY KEY,
    snapshot_at TIMESTAMPTZ DEFAULT NOW(),
    pnl_pct    DECIMAL(8,4),
    win_rate   DECIMAL(5,3),
    ev_per_trade DECIMAL(10,2),
    action     TEXT,
    threshold_multiplier DECIMAL(4,2),
    trades_today INTEGER
);

-- Index untuk query cepat
CREATE INDEX ON trade_history (pair_id, created_at DESC);
CREATE INDEX ON trade_history (created_at DESC);
CREATE INDEX ON performance_snapshots (snapshot_at DESC);
