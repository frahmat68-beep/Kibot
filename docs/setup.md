# Setup Guide

## 1. Tooling

- Install JDK 21.
- Install Android Studio with Android SDK 35.
- Install Supabase CLI if you want to run migrations from terminal.

## 2. Secrets

Create your local environment values from the root `.env.example`.

Do not commit:

- Supabase DB password
- Supabase service role key
- Indodax API key or secret
- User passphrase for encrypted credential bundle

## 3. Supabase

1. Create a private user in Supabase Auth with email/password.
2. Build one SQL bundle if you want a single file for SQL Editor / terminal:

```bash
scripts/build_supabase_sql_bundle.sh
```

3. Apply migrations in `infra/supabase/migrations` in order, or use:

```bash
scripts/apply_supabase_migrations.sh
```

4. Verify the control-plane tables:

```bash
python3 scripts/check_supabase_control_plane.py
```

5. Verify the auth trigger created:
   - `profiles`
   - `bots` with `bot_id = 'main'`
   - `bot_state`
   - `engine_leases`
6. Register each device through the app flow before using live mode.

## 4. Android

1. Open the repo in Android Studio.
2. Let Gradle sync.
3. Grant notifications.
4. Disable battery optimization for the app on the Android device.
5. Keep the device charging when Android is the active engine.
6. Build the private APK:

```bash
scripts/build_android_release.sh
```

7. Install to a connected phone:

```bash
scripts/install_android_release.sh
```

8. Or connect ADB over Wi-Fi after one USB pairing:

```bash
scripts/connect_android_wifi.sh <IP-HP>
```

## 5. Mac Engine

1. Run the mac engine from the repo root:

```bash
scripts/run_mac_engine.sh
```

2. Open `http://localhost:8787`.
3. Keep the Mac awake if it is expected to take over automatically.

## 6. Safe First Run

1. Leave bot state `OFF`.
2. Pair Android and Mac devices.
3. Validate Supabase connectivity.
4. Validate encrypted credential sync.
5. Validate heartbeat and lease transitions without live orders.
6. Enable live mode only after command sync, heartbeat, and reconciliation are all healthy.

## 7. Current Phase Notes

Phase 3 foundation is now partially wired: the Mac daemon can authenticate to Supabase, poll state, process commands, and run safe takeover logic, while the Android repository has the control-plane hooks but still defaults to local fallback until its device/runtime config path is completed.
