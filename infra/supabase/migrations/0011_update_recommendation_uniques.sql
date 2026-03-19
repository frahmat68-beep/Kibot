create unique index if not exists idx_parameter_versions_bot_scope_tag
    on public.parameter_versions(bot_id, scope, version_tag);
