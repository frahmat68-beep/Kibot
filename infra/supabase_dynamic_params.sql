-- Trinity Bot Dynamic Configuration Table
-- EXECUTE THIS IN SUPABASE SQL EDITOR

CREATE TABLE IF NOT EXISTS dynamic_params (
    id SERIAL PRIMARY KEY,
    param_key VARCHAR(100) UNIQUE NOT NULL,
    param_value JSONB NOT NULL,
    updated_by VARCHAR(50) DEFAULT 'manual',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    description TEXT
);

-- Insert default values
INSERT INTO dynamic_params (param_key, param_value, description) VALUES
    ('trailing_stop_pct', '{"value": 1.5}', 'Trailing stop percentage'),
    ('volatility_threshold', '{"value": 8.0}', 'Max volatility threshold for stable bucket'),
    ('cooldown_minutes', '{"value": 5}', 'Cooldown after trade execution'),
    ('fomo_guard_micro', '{"value": 35.0}', 'FOMO guard for micro-cap coins (<50 IDR)'),
    ('fomo_guard_mid', '{"value": 22.0}', 'FOMO guard for mid-cap coins (50-500 IDR)'),
    ('fomo_guard_big', '{"value": 15.0}', 'FOMO guard for big-cap coins (>500 IDR)'),
    ('ai_approval_min_score', '{"value": 0.62}', 'Minimum AI approval score'),
    ('ai_approval_min_net_pct', '{"value": 0.08}', 'Minimum expected net profit %')
ON CONFLICT (param_key) DO NOTHING;

-- Create index for fast lookups
CREATE INDEX IF NOT EXISTS idx_dynamic_params_key ON dynamic_params(param_key);

-- Create function to auto-update timestamp
CREATE OR REPLACE FUNCTION update_dynamic_params_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger to auto-update timestamp on changes
DROP TRIGGER IF EXISTS trigger_update_dynamic_params_timestamp ON dynamic_params;
CREATE TRIGGER trigger_update_dynamic_params_timestamp
    BEFORE UPDATE ON dynamic_params
    FOR EACH ROW
    EXECUTE FUNCTION update_dynamic_params_timestamp();
