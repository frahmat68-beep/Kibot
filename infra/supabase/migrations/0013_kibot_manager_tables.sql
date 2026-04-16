create table if not exists public.app_config (
    id integer primary key,
    machine_config jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now()
);

create table if not exists public.king_dashboard (
    id integer primary key,
    total_balance_idr double precision not null default 0,
    current_ping_ms bigint,
    active_live_pairs jsonb not null default '[]'::jsonb,
    latest_manager_log text,
    udp_ping_ms bigint,
    kidax_ping_ms bigint,
    kinance_ping_ms bigint,
    target_progress_pct double precision,
    kidax_balance_idr double precision,
    kinance_balance_idr double precision,
    kidax_pnl_today_pct double precision,
    kinance_pnl_today_pct double precision,
    kidax_pair_active text,
    kinance_pair_active text,
    updated_at timestamptz not null default now()
);

insert into public.app_config (id, machine_config)
values (1, '{}'::jsonb)
on conflict (id) do nothing;

insert into public.king_dashboard (id, latest_manager_log)
values (1, 'KiBot manager siap. Menunggu evaluasi jam berikutnya.')
on conflict (id) do nothing;

grant select, insert, update on table public.app_config to anon, authenticated, service_role;
grant select, insert, update on table public.king_dashboard to anon, authenticated, service_role;

alter table public.app_config disable row level security;
alter table public.king_dashboard disable row level security;

do $$
begin
    if exists (
        select 1
        from pg_publication
        where pubname = 'supabase_realtime'
    ) then
        if not exists (
            select 1
            from pg_publication_tables
            where pubname = 'supabase_realtime'
              and schemaname = 'public'
              and tablename = 'king_dashboard'
        ) then
            execute 'alter publication supabase_realtime add table public.king_dashboard';
        end if;
    end if;
end
$$;
