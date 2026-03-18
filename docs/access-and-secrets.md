# Access & Secrets

Dokumen ini fokus ke data yang masih perlu Anda siapkan supaya KiBot bisa jalan dengan aman, tanpa menulis secret ke repo.

## 1. Yang Sudah Cukup

Dari Anda, yang sudah cukup untuk lanjut implementasi:

- Supabase project URL
- Supabase publishable / anon key
- Indodax API key
- Indodax API secret

## 2. Yang Masih Dibutuhkan

Masih ada 4 hal yang perlu Anda isi di lokal:

1. `SUPABASE_USER_EMAIL`
2. `SUPABASE_USER_PASSWORD`
3. Android SDK path di `local.properties`
4. Passphrase E2EE untuk sync credential antar device

Kalau Anda tidak mau setup manual, jalankan script ini dari root project:

```bash
scripts/bootstrap_local.sh
scripts/setup_android_sdk.sh
scripts/generate_release_keystore.sh
scripts/check_local_setup.sh
```

Script di atas akan membuat file lokal yang di-ignore repo dan mengurangi setup manual ke titik minimum.

Untuk cek status auth owner Supabase secara eksplisit:

```bash
python3 scripts/check_supabase_auth.py
```

Output penting:

- `ready` = login owner dan control-plane sudah bisa dipakai
- `pending_confirmation` = inbox email masih harus dikonfirmasi
- `authenticated_but_control_plane_error` = login sudah bisa, tapi schema/RLS/control-plane masih belum siap

Untuk cek tabel control-plane yang masih kurang:

```bash
python3 scripts/check_supabase_control_plane.py
```

Kalau Anda sudah punya password database Supabase, isi juga:

```env
SUPABASE_DB_URL=postgresql://postgres:[PASSWORD-DB]@db.<project-ref>.supabase.co:5432/postgres
```

Lalu apply migration langsung dari terminal:

```bash
scripts/apply_supabase_migrations.sh
```

Tambahan opsional tapi sangat disarankan:

5. Android release keystore untuk channel update APK private
6. Bucket Supabase Storage private untuk rilis APK
7. `SUPABASE_DB_URL` kalau Anda ingin apply migration langsung dari terminal

## 3. Penting: Rotate Secret Yang Sudah Terlanjur Dibagikan

Karena Indodax API secret sudah pernah tertulis di chat, langkah paling aman adalah:

1. selesai setup dasar dulu
2. buat API key Indodax baru `view + trade only`
3. matikan / hapus key lama

Saya sengaja tidak menyimpan secret Anda ke file yang ke-track.

## 4. Cara Buat User Login Supabase

KiBot client sekarang login ke Supabase pakai email + password user private Anda sendiri.

Rekomendasi:

- satu akun email khusus bot
- password panjang dan random
- bukan email utama harian Anda

Langkah umum di dashboard Supabase:

1. Buka project Supabase Anda.
2. Masuk ke menu `Authentication`.
3. Pastikan password/email auth aktif.
4. Buat user email/password private untuk diri Anda.
5. Jika project Anda mewajibkan verifikasi email, verifikasi email itu dulu atau confirm user-nya dari dashboard.

Catatan:

- Supabase docs menyebut password/email auth aktif secara default, dan pada hosted project verifikasi email biasanya aktif secara default juga.
- Sumber: [Password-based Auth](https://supabase.com/docs/guides/auth/passwords), [Users](https://supabase.com/docs/guides/auth/users)

Setelah itu isi lokal:

```env
SUPABASE_USER_EMAIL=alamat-email-private-anda
SUPABASE_USER_PASSWORD=password-random-panjang
```

Kalau Anda ingin dibantu otomatis dari terminal setelah `.env` lokal dibuat, gunakan:

```bash
python3 scripts/setup_supabase_owner.py alamat-email-anda@example.com
```

Script ini akan mencoba signup owner ke Supabase memakai password yang sudah tersimpan di `.env`, lalu memberi tahu apakah email confirmation masih diperlukan.

## 5. Cara Isi File `.env` Lokal

Jangan ubah `.env.example` untuk secret asli. Buat file `.env` di root project.

Contoh minimal:

```env
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your-anon-key
SUPABASE_DB_URL=postgresql://postgres:[PASSWORD-DB]@db.your-project.supabase.co:5432/postgres
SUPABASE_USER_EMAIL=your-private-email@example.com
SUPABASE_USER_PASSWORD=your-long-random-password
INDODAX_API_KEY=your-view-trade-key
INDODAX_API_SECRET=your-secret
BOT_DEFAULT_LEASE_TTL_SECONDS=30
```

Untuk Mac engine, Anda juga bisa buat:

`apps/mac-engine/.env`

Isi minimal:

```env
MAC_ENGINE_PORT=8787
BOT_ID=main
DEVICE_ID=macbook-main
DEVICE_DISPLAY_NAME=MacBook Pro 2020
BOT_POLL_INTERVAL_MS=5000
```

## 6. Cara Setup Android SDK di Laptop

Supaya module Android bisa di-build, project butuh `local.properties`.

Langkah:

1. Install Android Studio.
2. Saat pertama buka, install Android SDK.
3. Copy [local.properties.example](/Users/kiki/Documents/Web%20Develop/KiBot/local.properties.example) jadi `local.properties`.
4. Ganti `sdk.dir` ke path SDK Anda.

Contoh umum di macOS:

```properties
sdk.dir=/Users/nama-anda/Library/Android/sdk
```

Sumber Android resmi: [Configure your build](https://developer.android.com/build)

## 7. Cara Buat Passphrase E2EE

Passphrase ini dipakai untuk mengenkripsi bundle credential sebelum disimpan sebagai ciphertext di Supabase.

Rekomendasi:

- minimal 5 kata acak atau 20+ karakter random
- simpan di password manager
- jangan taruh di repo
- jangan sama dengan password email/Supabase

Contoh format yang aman:

```text
kopi-radar-kipas-teluk-awan-jarum
```

Untuk V1, yang paling aman:

- passphrase Anda simpan sendiri
- Android dan Mac memasukkan passphrase saat pairing awal / unlock sensitif
- Supabase hanya menyimpan ciphertext

## 8. Android Release Keystore

Ini belum wajib untuk coding inti, tapi wajib kalau Anda ingin jalur update APK private yang rapi.

Kalau belum punya, buat sekali di Mac:

```bash
keytool -genkeypair \
  -v \
  -keystore kibot-release.jks \
  -alias kibot-release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 3650
```

Lalu simpan:

- file `kibot-release.jks`
- alias
- password keystore
- password key

Semua simpan di password manager / folder private, jangan commit.

Atau biarkan script yang mengurus:

```bash
scripts/generate_release_keystore.sh
```

## 9. Supabase Storage Untuk APK Update

Kalau mau jalur update private laptop -> HP:

1. buka menu `Storage` di dashboard Supabase
2. buat bucket private, misalnya `kibot-releases`
3. nanti laptop upload `apk + manifest + checksum`
4. Android cek manifest dan tawarkan install update

Supabase docs menyebut bucket bisa dibuat dari dashboard di halaman Storage.
Sumber: [Storage Quickstart](https://supabase.com/docs/guides/storage/quickstart)

## 10. Cara Masukkan APK Ke HP

Paling gampang lewat USB:

```bash
scripts/build_android_release.sh
scripts/install_android_release.sh
```

Kalau mau tanpa kabel setelah pairing awal:

```bash
scripts/connect_android_wifi.sh <IP-HP>
scripts/install_android_release.sh
```

## 11. Checklist Yang Perlu Anda Isi Sekarang

Paling minimum supaya saya bisa lanjut tanpa nunggu data lain:

1. jalankan `scripts/bootstrap_local.sh`
2. buat atau daftarkan user email/password Supabase private
3. jalankan `scripts/setup_android_sdk.sh`
4. jalankan `scripts/generate_release_keystore.sh`

Sisanya bisa saya lanjutkan dengan default yang aman.
