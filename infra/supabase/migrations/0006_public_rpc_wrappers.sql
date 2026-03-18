create or replace function public.rpc_register_device(
    p_device_id text,
    p_display_name text,
    p_platform public.device_platform,
    p_role public.device_role
)
returns public.devices
language sql
security definer
set search_path = public
as $$
    select private.register_device(p_device_id, p_display_name, p_platform, p_role);
$$;

create or replace function public.rpc_acquire_engine_lease(
    p_bot_id text,
    p_requester_device_id text,
    p_ttl_seconds integer default 30
)
returns public.engine_leases
language sql
security definer
set search_path = public
as $$
    select private.acquire_engine_lease(p_bot_id, p_requester_device_id, p_ttl_seconds);
$$;

create or replace function public.rpc_release_engine_lease(
    p_bot_id text,
    p_requester_device_id text,
    p_term bigint,
    p_reason text default null
)
returns public.engine_leases
language sql
security definer
set search_path = public
as $$
    select private.release_engine_lease(p_bot_id, p_requester_device_id, p_term, p_reason);
$$;

create or replace function public.rpc_append_heartbeat(
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
language sql
security definer
set search_path = public
as $$
    select private.append_heartbeat(
        p_bot_id,
        p_device_id,
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
        p_warnings
    );
$$;

create or replace function public.rpc_enqueue_command(
    p_bot_id text,
    p_created_by_device_id text,
    p_command_type public.command_type,
    p_target_device_id text default null,
    p_payload jsonb default null,
    p_expires_at timestamptz default null
)
returns public.command_queue
language sql
security definer
set search_path = public
as $$
    select private.enqueue_command(
        p_bot_id,
        p_created_by_device_id,
        p_command_type,
        p_target_device_id,
        p_payload,
        p_expires_at
    );
$$;

create or replace function public.rpc_reserve_execution_action(
    p_bot_id text,
    p_device_id text,
    p_term bigint,
    p_order_intent_id text,
    p_action_type text,
    p_ttl_seconds integer default 20,
    p_metadata jsonb default '{}'::jsonb
)
returns public.execution_actions
language sql
security definer
set search_path = public
as $$
    select private.reserve_execution_action(
        p_bot_id,
        p_device_id,
        p_term,
        p_order_intent_id,
        p_action_type,
        p_ttl_seconds,
        p_metadata
    );
$$;

create or replace function public.rpc_complete_execution_action(
    p_action_id uuid,
    p_device_id text,
    p_status text
)
returns public.execution_actions
language sql
security definer
set search_path = public
as $$
    select private.complete_execution_action(p_action_id, p_device_id, p_status);
$$;

create or replace function public.rpc_mark_conflict_and_safe_mode(
    p_bot_id text,
    p_reason text
)
returns void
language sql
security definer
set search_path = public
as $$
    select private.mark_conflict_and_safe_mode(p_bot_id, p_reason);
$$;

create or replace function public.rpc_cleanup_operational_data(
    p_bot_id text,
    p_retention_days integer default 90
)
returns public.cleanup_runs
language sql
security definer
set search_path = public
as $$
    select private.cleanup_operational_data(p_bot_id, p_retention_days);
$$;

grant execute on function public.rpc_register_device(text, text, public.device_platform, public.device_role) to authenticated;
grant execute on function public.rpc_acquire_engine_lease(text, text, integer) to authenticated;
grant execute on function public.rpc_release_engine_lease(text, text, bigint, text) to authenticated;
grant execute on function public.rpc_append_heartbeat(
    text, text, bigint, boolean, public.bot_desired_state, public.bot_effective_state, public.sync_health,
    public.health_status, boolean, boolean, boolean, integer, boolean, boolean, bigint, text, jsonb
) to authenticated;
grant execute on function public.rpc_enqueue_command(text, text, public.command_type, text, jsonb, timestamptz) to authenticated;
grant execute on function public.rpc_reserve_execution_action(text, text, bigint, text, text, integer, jsonb) to authenticated;
grant execute on function public.rpc_complete_execution_action(uuid, text, text) to authenticated;
grant execute on function public.rpc_mark_conflict_and_safe_mode(text, text) to authenticated;
grant execute on function public.rpc_cleanup_operational_data(text, integer) to authenticated;
