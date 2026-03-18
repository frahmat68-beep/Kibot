alter table public.parameter_versions enable row level security;
alter table public.mode_metrics enable row level security;
alter table public.weekly_learning_reviews enable row level security;
alter table public.no_trade_reviews enable row level security;

create policy "parameter_versions_owner" on public.parameter_versions
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = parameter_versions.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = parameter_versions.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "mode_metrics_owner" on public.mode_metrics
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = mode_metrics.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = mode_metrics.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "weekly_learning_reviews_owner" on public.weekly_learning_reviews
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = weekly_learning_reviews.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = weekly_learning_reviews.bot_id
          and b.user_id = auth.uid()
    )
);

create policy "no_trade_reviews_owner" on public.no_trade_reviews
for all using (
    exists (
        select 1 from public.bots b
        where b.bot_id = no_trade_reviews.bot_id
          and b.user_id = auth.uid()
    )
)
with check (
    exists (
        select 1 from public.bots b
        where b.bot_id = no_trade_reviews.bot_id
          and b.user_id = auth.uid()
    )
);
