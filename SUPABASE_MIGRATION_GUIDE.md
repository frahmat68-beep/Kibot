# SUPABASE MIGRATION GUIDE (NEW INSTANCE)

## Problem
Old Supabase instance (`txdvrhkxkylnlxnkpmfl`) mengalami masalah **egress limit** yang membuat bot sering terblokir saat push logs dan telemetry.

## Solution
Bot sekarang sudah **degraded mode ready** — bisa trading meskipun Supabase down. Namun untuk performance optimal, migrate ke instance baru:

---

## NEW SUPABASE CREDENTIALS

```bash
# Project URL
SUPABASE_URL=https://vptlelbgyxwieyfdpuja.supabase.co

# Publishable Key (Anon Key)
SUPABASE_ANON_KEY=sb_publishable_Mz_JQcIowddTSbtLC0uhdQ_HfzeC7Qh

# Password
SUPABASE_PASSWORD=NW5wYN8h98C9C29x

# Direct Connection (PostgreSQL)
SUPABASE_DB_URL=postgresql://postgres:NW5wYN8h98C9C29x@db.vptlelbgyxwieyfdpuja.supabase.co:5432/postgres

# Pooler Connection (recommended for serverless)
SUPABASE_POOLER_URL=postgresql://postgres.vptlelbgyxwieyfdpuja:NW5wYN8h98C9C29x@aws-1-ap-southeast-1.pooler.supabase.com:6543/postgres
```

---

## MIGRATION STEPS

### 1. Update `.env` File (Local Machine)
```bash
cd /Users/kiki/Documents/Web\ Develop/KiBot
nano .env
```

Replace old credentials dengan yang baru di atas.

### 2. Update Server Configuration (Oracle Cloud)
SSH ke server dan update environment variables:

```bash
# SSH to Oracle Cloud
ssh -i ~/.ssh/oracle_cloud_key ubuntu@YOUR_ORACLE_IP

# Edit systemd service files
sudo nano /etc/systemd/system/kibot-manager.service
sudo nano /etc/systemd/system/kidax-engine.service
sudo nano /etc/systemd/system/kinance-engine.service

# Update Environment variables di bagian [Service]
Environment="SUPABASE_URL=https://vptlelbgyxwieyfdpuja.supabase.co"
Environment="SUPABASE_ANON_KEY=sb_publishable_Mz_JQcIowddTSbtLC0uhdQ_HfzeC7Qh"

# Reload systemd dan restart services
sudo systemctl daemon-reload
sudo systemctl restart kibot-manager
sudo systemctl restart kidax-engine
sudo systemctl restart kinance-engine

# Verify services running
sudo systemctl status kibot-manager
sudo systemctl status kidax-engine
sudo systemctl status kinance-engine
```

### 3. Migrate Database Schema (CRITICAL!)
Gunakan Supabase CLI atau manual SQL migration:

```bash
# Option A: Using Supabase CLI
supabase login
supabase link --project-ref vptlelbgyxwieyfdpuja
supabase db push

# Option B: Manual SQL (jika sudah punya schema dump)
psql "postgresql://postgres:NW5wYN8h98C9C29x@db.vptlelbgyxwieyfdpuja.supabase.co:5432/postgres" < schema.sql
```

### 4. Test Connection
```bash
# Test dari server Oracle Cloud
curl -H "apikey: sb_publishable_Mz_JQcIowddTSbtLC0uhdQ_HfzeC7Qh" \
  https://vptlelbgyxwieyfdpuja.supabase.co/rest/v1/
```

Expected: HTTP 200 atau 401 (Auth error) = connection OK

---

## DEGRADED MODE (Fallback)

Jika Supabase unreachable, bot TIDAK AKAN BLOKIR trading! Sekarang ada fallback mechanism:

1. **Entry/Exit execution:** Tetap berjalan normal
2. **Logs:** Fallback ke local file (`logs/kibot-manager.log`, `logs/kidax-daemon.log`)
3. **Telemetry:** Buffered di memory (flush saat reconnect)
4. **Health checks:** Tetap monitor exchange reachability, ignore Supabase status

**Log Location (Degraded Mode):**
- `/var/log/kibot/kibot-manager.log`
- `/var/log/kibot/kidax-engine.log`
- `/var/log/kibot/kinance-engine.log`

---

## ROLLBACK (If Needed)

Jika new instance bermasalah, rollback ke old instance:

```bash
# Restore old credentials di .env
SUPABASE_URL=https://txdvrhkxkylnlxnkpmfl.supabase.co
SUPABASE_DB_URL=postgresql://postgres:vAB1WoVeeqDDIv4l@db.txdvrhkxkylnlxnkpmfl.supabase.co:5432/postgres
SUPABASE_POOLER_URL=postgresql://postgres.txdvrhkxkylnlxnkpmfl:vAB1WoVeeqDDIv4l@aws-1-ap-southeast-1.pooler.supabase.com:6543/postgres

# Restart services
sudo systemctl restart kibot-manager kidax-engine kinance-engine
```

---

## VERIFICATION CHECKLIST

✅ `.env` file updated  
✅ Systemd services updated  
✅ Schema migrated to new instance  
✅ Services restarted successfully  
✅ Health check menunjukkan `supabaseReachable: true`  
✅ Logs muncul di Supabase dashboard  
✅ Trading execution normal  

---

## NOTES

- **Old instance akan tetap aktif** sampai migration selesai
- **Degraded mode** memastikan trading tetap jalan meskipun migrasi gagal
- **Local logs** selalu available sebagai backup
- **No data loss** — semua critical data tetap di local state before sync

---

**Created:** 2025-01-10  
**Author:** KiBot Trinity Engineering Team  
**Status:** READY FOR DEPLOYMENT
