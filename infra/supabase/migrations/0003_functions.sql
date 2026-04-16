create or replace function private.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = timezone('utc', now());
    return new;
end;
$$;

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (user_id, email, display_name)
    values (new.id, new.email, split_part(coalesce(new.email, 'user'), '@', 1))
    on conflict (user_id) do nothing;

    insert into public.bots (bot_id, user_id, display_name)
    values ('main', new.id, 'KiCryp Main')
    on conflict (bot_id) do nothing;

    insert into public.bot_state (bot_id)
    values ('main')
    on conflict (bot_id) do nothing;

    insert into public.engine_leases (bot_id)
    values ('main')
    on conflict (bot_id) do nothing;

    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
after insert on auth.users
for each row execute procedure public.handle_new_user();

drop trigger if exists trg_profiles_touch on public.profiles;
create trigger trg_profiles_touch before update on public.profiles
for each row execute procedure private.touch_updated_at();

drop trigger if exists trg_bots_touch on public.bots;
create trigger trg_bots_touch before update on public.bots
for each row execute procedure private.touch_updated_at();

drop trigger if exists trg_devices_touch on public.devices;
create trigger trg_devices_touch before update on public.devices
for each row execute procedure private.touch_updated_at();

drop trigger if exists trg_bot_state_touch on public.bot_state;
create trigger trg_bot_state_touch before update on public.bot_state
for each row execute procedure private.touch_updated_at();

drop trigger if exists trg_engine_leases_touch on public.engine_leases;
create trigger trg_engine_leases_touch before update on public.engine_leases
for each row execute procedure private.touch_updated_at();

drop trigger if exists trg_command_queue_touch on public.command_queue;
create trigger trg_command_queue_touch before update on public.command_queue
for each row execute procedure private.touch_updated_at();

drop trigger if exists trg_api_credentials_touch on public.api_credentials_encrypted;
create trigger trg_api_credentials_touch before update on public.api_credentials_encrypted
for each row execute procedure private.touch_updated_at();

drop trigger if exists trg_orders_touch on public.orders;
create trigger trg_orders_touch before update on public.orders
for each row execute procedure private.touch_updated_at();

drop trigger if exists trg_positions_touch on public.positions;
create trigger trg_positions_touch before update on public.positions
for each row execute procedure private.touch_updated_at();

drop trigger if exists trg_daily_equity_touch on public.daily_equity;
create trigger trg_daily_equity_touch before update on public.daily_equity
for each row execute procedure private.touch_updated_at();

drop trigger if exists trg_daily_trade_summary_touch on public.daily_trade_summary;
create trigger trg_daily_trade_summary_touch before update on public.daily_trade_summary
for each row execute procedure private.touch_updated_at();

create or replace function private.assert_device_access(p_device_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Unauthenticated';
    end if;

    if not exists (
        select 1
        from public.devices d
        where d.device_id = p_device_id
          and d.user_id = auth.uid()
          and d.is_revoked = false
    ) then
        raise exception 'Device is not registered or has been revoked';
    end if;
end;
$$;

create or replace function private.register_device(
    p_device_id text,
    p_display_name text,
    p_platform public.device_platform,
    p_role public.device_role
)
returns public.devices
language plpgsql
security definer
set search_path = public
as $$
declare
    v_row public.devices;
begin
    if auth.uid() is null then
        raise exception 'Unauthenticated';
    end if;

    insert into public.devices (
        device_id,
        user_id,
        display_name,
        platform,
        role,
        is_revoked,
        last_seen_at
    )
    values (
        p_device_id,
        auth.uid(),
        p_display_name,
        p_platform,
        p_role,
        false,
        timezone('utc', now())
    )
    on conflict (device_id) do update
        set display_name = excluded.display_name,
            role = excluded.role,
            platform = excluded.platform,
            is_revoked = false,
            last_seen_at = timezone('utc', now());

    select *
    into v_row
    from public.devices
    where device_id = p_device_id;

    return v_row;
end;
$$;

create or replace function private.acquire_engine_lease(
    p_bot_id text,
    p_requester_device_id text,
    p_ttl_seconds integer default 30
)
returns public.engine_leases
language plpgsql
security definer
set search_path = public
as $$
declare
    v_now timestamptz := timezone('utc', now());
    v_lease public.engine_leases;
begin
    perform private.assert_device_access(p_requester_device_id);

    if not exists (
        select 1 from public.bots b
        where b.bot_id = p_bot_id
          and b.user_id = auth.uid()
    ) then
        raise exception 'Bot not found';
    end if;

    insert into public.engine_leases (bot_id)
    values (p_bot_id)
    on conflict (bot_id) do nothing;

    select *
    into v_lease
    from public.engine_leases
    where bot_id = p_bot_id
    for update;

    if v_lease.state = 'HELD'
       and v_lease.holder_device_id is distinct from p_requester_device_id
       and v_lease.expires_at is not null
       and v_lease.expires_at > v_now then
        raise exception 'Lease is still held by another device';
    end if;

    update public.engine_leases
    set holder_device_id = p_requester_device_id,
        current_term = coalesce(v_lease.current_term, 0) + 1,
        state = 'HELD',
        expires_at = v_now + make_interval(secs => p_ttl_seconds),
        last_heartbeat_at = v_now,
        conflict_detected = false
    where bot_id = p_bot_id
    returning *
    into v_lease;

    update public.bot_state
    set active_device_id = p_requester_device_id,
        current_term = v_lease.current_term,
        effective_state = case when desired_state = 'ON' then 'STARTING' else effective_state end,
        last_heartbeat_at = v_now
    where bot_id = p_bot_id;

    return v_lease;
end;
$$;

create or replace function private.release_engine_lease(
    p_bot_id text,
    p_requester_device_id text,
    p_term bigint,
    p_reason text default null
)
returns public.engine_leases
language plpgsql
security definer
set search_path = public
as $$
declare
    v_lease public.engine_leases;
begin
    perform private.assert_device_access(p_requester_device_id);

    select *
    into v_lease
    from public.engine_leases
    where bot_id = p_bot_id
    for update;

    if v_lease.holder_device_id is distinct from p_requester_device_id then
        raise exception 'Only the current holder can release the lease';
    end if;

    if v_lease.current_term <> p_term then
        raise exception 'Lease term mismatch';
    end if;

    update public.engine_leases
    set holder_device_id = null,
        state = 'RELEASED',
        expires_at = timezone('utc', now()),
        updated_at = timezone('utc', now())
    where bot_id = p_bot_id
    returning *
    into v_lease;

    update public.bot_state
    set effective_state = case
            when desired_state = 'OFF' then 'STOPPED'
            else 'DEGRADED'
        end,
        safe_mode_reason = coalesce(p_reason, safe_mode_reason)
    where bot_id = p_bot_id;

    return v_lease;
end;
$$;

create or replace function private.append_heartbeat(
    p_bot_id text,
    p_device_id text,
    p_term bigint,
    p_is_master boolean,
    p_desired_state public.bot_desired_state,
    p_effective_state public.bot_effective_state,
    p_sync_health public.sync_health,
    p_health_status public.health_status,
    p_websocket_healthy boolean,
    p_exchange_reachable boolean,
    p_supabase_reachable boolean,
    p_battery_percent integer default null,
    p_charging boolean default null,
    p_network_metered boolean default null,
    p_heartbeat_lag_ms bigint default null,
    p_last_error text default null,
    p_warnings jsonb default '[]'::jsonb
)
returns public.engine_heartbeats
language plpgsql
security definer
set search_path = public
as $$
declare
    v_now timestamptz := timezone('utc', now());
    v_row public.engine_heartbeats;
begin
    perform private.assert_device_access(p_device_id);

    insert into public.engine_heartbeats (
        bot_id,
        device_id,
        observed_at,
        term,
        is_master,
        desired_state,
        effective_state,
        sync_health,
        health_status,
        websocket_healthy,
        exchange_reachable,
        supabase_reachable,
        battery_percent,
        charging,
        network_metered,
        heartbeat_lag_ms,
        last_error,
        warnings
    ) values (
        p_bot_id,
        p_device_id,
        v_now,
        p_term,
        p_is_master,
        p_desired_state,
        p_effective_state,
        p_sync_health,
        p_health_status,
        p_websocket_healthy,
        p_exchange_reachable,
        p_supabase_reachable,
        p_battery_percent,
        p_charging,
        p_network_metered,
        p_heartbeat_lag_ms,
        p_last_error,
        coalesce(p_warnings, '[]'::jsonb)
    )
    returning *
    into v_row;

    update public.devices
    set last_seen_at = v_now
    where device_id = p_device_id;

    if p_is_master then
        update public.engine_leases
        set last_heartbeat_at = v_now,
            expires_at = v_now + interval '30 seconds',
            state = case
                when conflict_detected then 'CONFLICT'::public.lease_state
                else 'HELD'::public.lease_state
            end
        where bot_id = p_bot_id
          and holder_device_id = p_device_id
          and current_term = p_term;

        update public.bot_state
        set effective_state = p_effective_state,
            sync_health = p_sync_health,
            last_heartbeat_at = v_now
        where bot_id = p_bot_id;
    end if;

    return v_row;
end;
$$;

create or replace function private.enqueue_command(
    p_bot_id text,
    p_created_by_device_id text,
    p_command_type public.command_type,
    p_target_device_id text default null,
    p_payload jsonb default null,
    p_expires_at timestamptz default null
)
returns public.command_queue
language plpgsql
security definer
set search_path = public
as $$
declare
    v_row public.command_queue;
begin
    perform private.assert_device_access(p_created_by_device_id);

    insert into public.command_queue (
        bot_id,
        created_by_device_id,
        target_device_id,
        command_type,
        payload,
        expires_at
    ) values (
        p_bot_id,
        p_created_by_device_id,
        p_target_device_id,
        p_command_type,
        p_payload,
        p_expires_at
    )
    returning *
    into v_row;

    return v_row;
end;
$$;

create or replace function private.reserve_execution_action(
    p_bot_id text,
    p_device_id text,
    p_term bigint,
    p_order_intent_id text,
    p_action_type text,
    p_ttl_seconds integer default 20,
    p_metadata jsonb default '{}'::jsonb
)
returns public.execution_actions
language plpgsql
security definer
set search_path = public
as $$
declare
    v_now timestamptz := timezone('utc', now());
    v_lease public.engine_leases;
    v_action public.execution_actions;
begin
    perform private.assert_device_access(p_device_id);

    select *
    into v_lease
    from public.engine_leases
    where bot_id = p_bot_id
    for update;

    if v_lease.holder_device_id is distinct from p_device_id then
        raise exception 'Only the active lease holder may reserve execution actions';
    end if;

    if v_lease.current_term <> p_term then
        raise exception 'Execution term mismatch';
    end if;

    if v_lease.conflict_detected or v_lease.expires_at <= v_now then
        raise exception 'Lease is conflicted or expired';
    end if;

    insert into public.execution_actions (
        bot_id,
        device_id,
        term,
        order_intent_id,
        action_type,
        status,
        metadata,
        expires_at
    ) values (
        p_bot_id,
        p_device_id,
        p_term,
        p_order_intent_id,
        p_action_type,
        'RESERVED',
        p_metadata,
        v_now + make_interval(secs => p_ttl_seconds)
    )
    returning *
    into v_action;

    return v_action;
end;
$$;

create or replace function private.complete_execution_action(
    p_action_id uuid,
    p_device_id text,
    p_status text
)
returns public.execution_actions
language plpgsql
security definer
set search_path = public
as $$
declare
    v_action public.execution_actions;
begin
    perform private.assert_device_access(p_device_id);

    if p_status not in ('SUBMITTED', 'RECONCILED', 'EXPIRED', 'FAILED') then
        raise exception 'Invalid execution action completion status';
    end if;

    update public.execution_actions
    set status = p_status,
        completed_at = timezone('utc', now())
    where action_id = p_action_id
      and device_id = p_device_id
    returning *
    into v_action;

    return v_action;
end;
$$;

create or replace function private.mark_conflict_and_safe_mode(
    p_bot_id text,
    p_reason text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Unauthenticated';
    end if;

    update public.engine_leases
    set conflict_detected = true,
        state = 'CONFLICT',
        expires_at = timezone('utc', now())
    where bot_id = p_bot_id;

    update public.bot_state
    set effective_state = 'SAFE_MODE',
        sync_health = 'BROKEN',
        safe_mode_reason = p_reason
    where bot_id = p_bot_id;
end;
$$;

create or replace function private.cleanup_operational_data(
    p_bot_id text,
    p_retention_days integer default 90
)
returns public.cleanup_runs
language plpgsql
security definer
set search_path = public
as $$
declare
    v_cutoff timestamptz := timezone('utc', now()) - make_interval(days => p_retention_days);
    v_run public.cleanup_runs;
    v_deleted_logs integer := 0;
    v_deleted_heartbeats integer := 0;
    v_deleted_snapshots integer := 0;
    v_deleted_metrics integer := 0;
    v_deleted_commands integer := 0;
    v_deleted_actions integer := 0;
begin
    if auth.uid() is null then
        raise exception 'Unauthenticated';
    end if;

    insert into public.cleanup_runs (bot_id, status, retention_days)
    values (p_bot_id, 'RUNNING', p_retention_days)
    returning *
    into v_run;

    delete from public.logs
    where bot_id = p_bot_id
      and created_at < v_cutoff;
    get diagnostics v_deleted_logs = row_count;

    delete from public.engine_heartbeats
    where bot_id = p_bot_id
      and observed_at < v_cutoff;
    get diagnostics v_deleted_heartbeats = row_count;

    delete from public.market_snapshots
    where bot_id = p_bot_id
      and captured_at < v_cutoff;
    get diagnostics v_deleted_snapshots = row_count;

    delete from public.strategy_metrics
    where bot_id = p_bot_id
      and created_at < v_cutoff;
    get diagnostics v_deleted_metrics = row_count;

    delete from public.command_queue
    where bot_id = p_bot_id
      and created_at < v_cutoff
      and status in ('SUCCEEDED', 'FAILED', 'EXPIRED');
    get diagnostics v_deleted_commands = row_count;

    delete from public.execution_actions
    where bot_id = p_bot_id
      and created_at < v_cutoff
      and status in ('RECONCILED', 'EXPIRED', 'FAILED');
    get diagnostics v_deleted_actions = row_count;

    update public.cleanup_runs
    set status = 'SUCCEEDED',
        finished_at = timezone('utc', now()),
        deleted_rows = jsonb_build_object(
            'logs', v_deleted_logs,
            'heartbeats', v_deleted_heartbeats,
            'market_snapshots', v_deleted_snapshots,
            'strategy_metrics', v_deleted_metrics,
            'command_queue', v_deleted_commands,
            'execution_actions', v_deleted_actions
        )
    where cleanup_run_id = v_run.cleanup_run_id
    returning *
    into v_run;

    return v_run;
exception
    when others then
        update public.cleanup_runs
        set status = 'FAILED',
            finished_at = timezone('utc', now()),
            error_message = sqlerrm
        where cleanup_run_id = v_run.cleanup_run_id
        returning *
        into v_run;
        return v_run;
end;
$$;

grant execute on function private.register_device(text, text, public.device_platform, public.device_role) to authenticated;
grant execute on function private.acquire_engine_lease(text, text, integer) to authenticated;
grant execute on function private.release_engine_lease(text, text, bigint, text) to authenticated;
grant execute on function private.append_heartbeat(
    text, text, bigint, boolean, public.bot_desired_state, public.bot_effective_state, public.sync_health,
    public.health_status, boolean, boolean, boolean, integer, boolean, boolean, bigint, text, jsonb
) to authenticated;
grant execute on function private.enqueue_command(text, text, public.command_type, text, jsonb, timestamptz) to authenticated;
grant execute on function private.reserve_execution_action(text, text, bigint, text, text, integer, jsonb) to authenticated;
grant execute on function private.complete_execution_action(uuid, text, text) to authenticated;
grant execute on function private.mark_conflict_and_safe_mode(text, text) to authenticated;
grant execute on function private.cleanup_operational_data(text, integer) to authenticated;
