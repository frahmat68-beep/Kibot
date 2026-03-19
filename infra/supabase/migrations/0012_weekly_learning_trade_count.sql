alter table public.weekly_learning_reviews
    add column if not exists trade_count integer not null default 0;
