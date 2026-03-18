alter table public.profiles enable row level security;
alter table public.bots enable row level security;
alter table public.devices enable row level security;
alter table public.bot_state enable row level security;
alter table public.engine_leases enable row level security;
alter table public.engine_heartbeats enable row level security;
alter table public.command_queue enable row level security;
alter table public.api_credentials_encrypted enable row level security;
alter table public.execution_actions enable row level security;
alter table public.orders enable row level security;
alter table public.fills enable row level security;
alter table public.positions enable row level security;
alter table public.daily_equity enable row level security;
alter table public.risk_events enable row level security;
alter table public.logs enable row level security;
alter table public.strategy_metrics enable row level security;
alter table public.market_snapshots enable row level security;
alter table public.cleanup_runs enable row level security;
alter table public.daily_trade_summary enable row level security;

create policy "profiles_owner" on public.profiles
for all using (auth.uid() = user_id)
with check (auth.uid() = user_id);

create policy "bots_owner" on public.bots
for all using (auth.uid() = user_id)
with check (auth.uid() = user_id);

create policy "devices_owner" on public.devices
for all using (auth.uid() = user_id)
with check (auth.uid() = user_id);

create policy "bot_state_owner" on public.bot_state
for select using (
    exists (
        select 1 from public.bots b
        where b.bot_id = bot_state.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "bot_state_update_owner" on public.bot_state
for update using (
    exists (
        select 1 from public.bots b
        where b.bot_id = bot_state.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = bot_state.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "engine_leases_owner" on public.engine_leases
for select using (
    exists (
        select 1 from public.bots b
        where b.bot_id = engine_leases.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "engine_heartbeats_owner" on public.engine_heartbeats
for select using (
    exists (
        select 1 from public.bots b
        where b.bot_id = engine_heartbeats.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "command_queue_owner" on public.command_queue
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = command_queue.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = command_queue.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "api_credentials_owner" on public.api_credentials_encrypted
for all using (auth.uid() = user_id)
with check (auth.uid() = user_id);

create policy "execution_actions_owner" on public.execution_actions
for select using (
    exists (
        select 1 from public.bots b
        where b.bot_id = execution_actions.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "orders_owner" on public.orders
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = orders.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = orders.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "fills_owner" on public.fills
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = fills.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = fills.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "positions_owner" on public.positions
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = positions.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = positions.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "daily_equity_owner" on public.daily_equity
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = daily_equity.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = daily_equity.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "risk_events_owner" on public.risk_events
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = risk_events.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = risk_events.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "logs_owner" on public.logs
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = logs.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = logs.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "strategy_metrics_owner" on public.strategy_metrics
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = strategy_metrics.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = strategy_metrics.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "market_snapshots_owner" on public.market_snapshots
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = market_snapshots.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = market_snapshots.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "cleanup_runs_owner" on public.cleanup_runs
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = cleanup_runs.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = cleanup_runs.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "daily_trade_summary_owner" on public.daily_trade_summary
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = daily_trade_summary.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = daily_trade_summary.bot_id
          and b.user_id = auth.uid()
    )
);

