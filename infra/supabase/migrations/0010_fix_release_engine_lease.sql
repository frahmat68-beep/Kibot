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
        state = 'RELEASED'::public.lease_state,
        expires_at = timezone('utc', now()),
        updated_at = timezone('utc', now()),
        conflict_detected = false
    where bot_id = p_bot_id
    returning *
    into v_lease;

    update public.bot_state
    set active_device_id = null,
        standby_device_id = p_requester_device_id,
        effective_state = case
            when desired_state = 'OFF' then 'STOPPED'::public.bot_effective_state
            else 'STARTING'::public.bot_effective_state
        end,
        sync_health = case
            when desired_state = 'OFF' then sync_health
            else 'DEGRADED'::public.sync_health
        end,
        safe_mode_reason = coalesce(p_reason, safe_mode_reason),
        updated_at = timezone('utc', now())
    where bot_id = p_bot_id;

    return v_lease;
end;
$$;
