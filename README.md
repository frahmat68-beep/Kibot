# KiBot

Private dual-engine spot trading bot for Indodax with Android as the default primary engine, MacBook as standby/backup, and Supabase as the shared control plane.

## Status

This repository currently contains the expanded Phase 2 foundation plus the first Phase 3 runtime wiring:

- Android app skeleton with Compose UI, foreground service, Room cache, and security hooks.
- Mac engine daemon with local web dashboard, command dispatch, lease polling, and safe takeover loop.
- Shared Kotlin modules for trading domain models, pair scoring, market regime analysis, bot modes, risk ladder, profit protection, weekly learning, reconciliation, and lease handling.
- Shared Supabase control-plane client for auth, RPC lease flow, command queue, and snapshot polling.
- Supabase migrations, RPC-oriented control-plane schema, strategy intelligence tables, and strict RLS baseline.
- Initial failover tests, setup docs, and failure-mode notes.

## Modules

- `apps/android`: Android control app and primary runtime engine.
- `apps/mac-engine`: macOS JVM daemon and local web dashboard.
- `packages/shared-models`: Shared DTOs, enums, and serializable payloads.
- `packages/core`: Shared business logic for lease, risk, strategy, health, and reconciliation.
- `packages/control-plane`: Shared Supabase auth, polling, RPC, and control-plane gateway implementation.
- `packages/indodax-client`: Indodax REST adapter, signed private requests, and fill/order reconciliation inputs.
- `packages/test-kit`: Fake exchange and test helpers for scenario testing.
- `infra/supabase`: SQL migrations, RLS, seeds, and cleanup jobs.
- `docs`: Architecture, setup, safety, and rollout notes.

## Design Priorities

- Single active trading engine at any given time.
- Fencing-token lease protocol to block split-brain and stale writers.
- Safe automatic failover with mandatory reconciliation before new entries.
- Conservative, rule-based spot trading only.
- Hard daily loss stop, strong security, and rolling 90-day retention.

## Quick Start

1. Install JDK 21 and Android SDK.
2. Run `scripts/bootstrap_local.sh` to create local ignored secrets scaffolding.
3. Run `scripts/setup_android_sdk.sh` to install Android CLI tools and create `local.properties`.
4. Run `scripts/generate_release_keystore.sh` to create the private Android signing key.
5. If needed, create the Supabase owner account with `scripts/setup_supabase_owner.py your-email@example.com`.
6. Run `scripts/check_local_setup.sh` to verify the local machine is ready.
7. If Supabase owner is still pending, run `python3 scripts/check_supabase_auth.py` for the exact auth state.
8. Run `python3 scripts/check_supabase_control_plane.py` to see whether the required tables already exist.
9. If you already have the Supabase DB password locally, run `scripts/apply_supabase_migrations.sh`.
10. Run `./gradlew :packages:control-plane:jvmTest :packages:indodax-client:jvmTest :packages:test-kit:test :apps:mac-engine:test`.
11. Open the project in Android Studio / IntelliJ.
12. Run the mac engine from the repo root with `scripts/run_mac_engine.sh`.
13. Run Android app separately.

For a private signed APK artifact, run `scripts/build_android_release.sh` after the setup above.
To install the latest APK to a connected phone, run `scripts/install_android_release.sh`.
For ADB over Wi-Fi after one USB pairing, run `scripts/connect_android_wifi.sh <IP-HP>`.

See [setup.md](/Users/kiki/Documents/Web%20Develop/KiBot/docs/setup.md), [access-and-secrets.md](/Users/kiki/Documents/Web%20Develop/KiBot/docs/access-and-secrets.md), [update-channel.md](/Users/kiki/Documents/Web%20Develop/KiBot/docs/update-channel.md), and [trading-intelligence.md](/Users/kiki/Documents/Web%20Develop/KiBot/docs/trading-intelligence.md) for the current design and setup flow.
