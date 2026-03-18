create or replace function private.publish_runtime_intelligence(
    p_bot_id text,
    p_device_id text,
    p_term bigint,
    p_current_pair text default null,
    p_operating_mode public.bot_mode default 'GROWTH',
    p_edge_confidence public.edge_confidence default 'MEDIUM',
    p_aggression_score numeric default 0.50,
    p_risk_ladder_level public.risk_ladder_level default 'NORMAL',
    p_profit_protection_status public.profit_protection_status default 'INACTIVE',
    p_market_regime public.market_regime default 'HIGH_VOLATILITY_UNCLEAR',
    p_distrust_labels jsonb default '[]'::jsonb,
    p_active_candidate_pairs jsonb default '[]'::jsonb,
    p_market_opportunity_score numeric default 0,
    p_bot_health_score numeric default 0,
    p_performance_momentum_score numeric default 0,
    p_safe_mode_reason text default null
)
returns public.bot_state
language plpgsql
security definer
set search_path = public
as $$
declare
    v_now timestamptz := timezone('utc', now());
    v_lease public.engine_leases;
    v_state public.bot_state;
begin
    perform private.assert_device_access(p_device_id);

    select *
    into v_lease
    from public.engine_leases
    where bot_id = p_bot_id
    for update;

    if v_lease.holder_device_id is distinct from p_device_id then
        raise exception 'Only the active master engine may publish runtime intelligence';
    end if;

    if v_lease.current_term <> p_term then
        raise exception 'Runtime intelligence term mismatch';
    end if;

    if v_lease.conflict_detected or v_lease.expires_at <= v_now then
        raise exception 'Lease is conflicted or expired';
    end if;

    update public.bot_state
    set current_pair = p_current_pair,
        operating_mode = p_operating_mode,
        edge_confidence = p_edge_confidence,
        aggression_score = p_aggression_score,
        risk_ladder_level = p_risk_ladder_level,
        profit_protection_status = p_profit_protection_status,
        market_regime = p_market_regime,
        distrust_labels = coalesce(p_distrust_labels, '[]'::jsonb),
        active_candidate_pairs = coalesce(p_active_candidate_pairs, '[]'::jsonb),
        safe_mode_reason = case
            when p_safe_mode_reason is not null then p_safe_mode_reason
            when effective_state = 'SAFE_MODE' then safe_mode_reason
            else null
        end,
        updated_at = v_now
    where bot_id = p_bot_id
    returning *
    into v_state;

    insert into public.mode_metrics (
        bot_id,
        device_id,
        operating_mode,
        market_regime,
        edge_confidence,
        risk_ladder_level,
        profit_protection_status,
        market_opportunity_score,
        bot_health_score,
        performance_momentum_score,
        aggression_score,
        created_at
    ) values (
        p_bot_id,
        p_device_id,
        p_operating_mode,
        p_market_regime,
        p_edge_confidence,
        p_risk_ladder_level,
        p_profit_protection_status,
        coalesce(p_market_opportunity_score, 0),
        coalesce(p_bot_health_score, 0),
        coalesce(p_performance_momentum_score, 0),
        coalesce(p_aggression_score, 0),
        v_now
    );

    return v_state;
end;
$$;

create or replace function public.rpc_publish_runtime_intelligence(
    p_bot_id text,
    p_device_id text,
    p_term bigint,
    p_current_pair text default null,
    p_operating_mode public.bot_mode default 'GROWTH',
    p_edge_confidence public.edge_confidence default 'MEDIUM',
    p_aggression_score numeric default 0.50,
    p_risk_ladder_level public.risk_ladder_level default 'NORMAL',
    p_profit_protection_status public.profit_protection_status default 'INACTIVE',
    p_market_regime public.market_regime default 'HIGH_VOLATILITY_UNCLEAR',
    p_distrust_labels jsonb default '[]'::jsonb,
    p_active_candidate_pairs jsonb default '[]'::jsonb,
    p_market_opportunity_score numeric default 0,
    p_bot_health_score numeric default 0,
    p_performance_momentum_score numeric default 0,
    p_safe_mode_reason text default null
)
returns public.bot_state
language sql
security definer
set search_path = public
as $$
    select private.publish_runtime_intelligence(
        p_bot_id,
        p_device_id,
        p_term,
        p_current_pair,
        p_operating_mode,
        p_edge_confidence,
        p_aggression_score,
        p_risk_ladder_level,
        p_profit_protection_status,
        p_market_regime,
        p_distrust_labels,
        p_active_candidate_pairs,
        p_market_opportunity_score,
        p_bot_health_score,
        p_performance_momentum_score,
        p_safe_mode_reason
    );
$$;

grant execute on function public.rpc_publish_runtime_intelligence(
    text, text, bigint, text, public.bot_mode, public.edge_confidence, numeric,
    public.risk_ladder_level, public.profit_protection_status, public.market_regime,
    jsonb, jsonb, numeric, numeric, numeric, text
) to authenticated;
