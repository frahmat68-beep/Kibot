create extension if not exists pgcrypto;

create schema if not exists private;

do $$
begin
    if not exists (select 1 from pg_type where typname = 'device_platform') then
        create type public.device_platform as enum ('ANDROID', 'MACOS');
    end if;
    if not exists (select 1 from pg_type where typname = 'device_role') then
        create type public.device_role as enum ('PRIMARY', 'STANDBY');
    end if;
    if not exists (select 1 from pg_type where typname = 'bot_desired_state') then
        create type public.bot_desired_state as enum ('OFF', 'ON');
    end if;
    if not exists (select 1 from pg_type where typname = 'bot_effective_state') then
        create type public.bot_effective_state as enum ('STOPPED', 'STARTING', 'RUNNING', 'DEGRADED', 'SAFE_MODE');
    end if;
    if not exists (select 1 from pg_type where typname = 'sync_health') then
        create type public.sync_health as enum ('HEALTHY', 'DEGRADED', 'BROKEN');
    end if;
    if not exists (select 1 from pg_type where typname = 'health_status') then
        create type public.health_status as enum ('HEALTHY', 'WARNING', 'CRITICAL');
    end if;
    if not exists (select 1 from pg_type where typname = 'lease_state') then
        create type public.lease_state as enum ('RELEASED', 'HELD', 'EXPIRED', 'CONFLICT');
    end if;
    if not exists (select 1 from pg_type where typname = 'command_type') then
        create type public.command_type as enum (
            'START_BOT',
            'STOP_BOT',
            'REQUEST_TAKEOVER',
            'FORCE_SAFE_TAKEOVER',
            'RELEASE_CONTROL',
            'SYNC_NOW',
            'FORCE_STANDBY',
            'RESUME_FROM_SAFE_MODE'
        );
    end if;
    if not exists (select 1 from pg_type where typname = 'command_status') then
        create type public.command_status as enum ('QUEUED', 'ACKED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED');
    end if;
    if not exists (select 1 from pg_type where typname = 'risk_event_type') then
        create type public.risk_event_type as enum (
            'DAILY_LOSS_LIMIT_REACHED',
            'DAILY_REBASE_PENDING',
            'POSITION_LIMIT_REACHED',
            'MIN_ORDER_NOTIONAL_REJECTED',
            'SPREAD_TOO_WIDE',
            'SLIPPAGE_TOO_HIGH',
            'HEALTH_BLOCK',
            'SPLIT_BRAIN_DETECTED',
            'RECONCILIATION_REQUIRED',
            'CREDENTIALS_LOCKED'
        );
    end if;
    if not exists (select 1 from pg_type where typname = 'order_side') then
        create type public.order_side as enum ('BUY', 'SELL');
    end if;
    if not exists (select 1 from pg_type where typname = 'order_type') then
        create type public.order_type as enum ('LIMIT', 'MARKET');
    end if;
    if not exists (select 1 from pg_type where typname = 'order_status') then
        create type public.order_status as enum (
            'CREATED',
            'SUBMITTING',
            'OPEN',
            'PARTIALLY_FILLED',
            'FILLED',
            'CANCEL_REQUESTED',
            'CANCELED',
            'REJECTED',
            'UNKNOWN'
        );
    end if;
    if not exists (select 1 from pg_type where typname = 'position_state') then
        create type public.position_state as enum ('OPENING', 'OPEN', 'CLOSING', 'CLOSED');
    end if;
    if not exists (select 1 from pg_type where typname = 'log_level') then
        create type public.log_level as enum ('DEBUG', 'INFO', 'WARN', 'ERROR');
    end if;
end $$;

