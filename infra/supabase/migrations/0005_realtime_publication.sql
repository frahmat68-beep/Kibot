do $$
begin
    if not exists (
        select 1 from pg_publication where pubname = 'supabase_realtime'
    ) then
        create publication supabase_realtime;
    end if;
end $$;

do $$
declare
    table_name text;
begin
    foreach table_name in array array[
        'devices',
        'bot_state',
        'engine_leases',
        'engine_heartbeats',
        'command_queue',
        'orders',
        'fills',
        'positions',
        'daily_equity',
        'risk_events',
        'logs',
        'cleanup_runs'
    ]
    loop
        if not exists (
            select 1
            from pg_publication_tables
            where pubname = 'supabase_realtime'
              and schemaname = 'public'
              and tablename = table_name
        ) then
            execute format('alter publication supabase_realtime add table public.%I', table_name);
        end if;
    end loop;
end $$;
