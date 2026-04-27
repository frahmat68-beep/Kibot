# Update Channel Laptop ke HP

Tujuan dokumen ini adalah menjelaskan cara update coding dari laptop ke HP dengan otomasi semaksimal mungkin, tapi tetap realistis dengan aturan Android normal.

## 1. Batasan Penting

Update **kode aplikasi Android** tidak bisa dipasang full-silent secara normal pada device Android pribadi biasa tanpa:

- root, atau
- mode device owner / MDM enterprise, atau
- distribusi khusus

Jadi desain paling aman dan realistis untuk KiCryp adalah:

- **config/state update**: otomatis penuh
- **APK/code update**: semi otomatis, user tinggal tap install

## 2. Yang Bisa Otomatis Penuh

Lewat Supabase, perubahan berikut bisa langsung sinkron ke HP:

- command bot
- risk flag
- state engine
- pair blacklist / allowlist
- feature flag
- status health
- safe mode reason
- command center state

Bagian ini tidak butuh reinstall app.

## 3. Desain Update APK Private

Flow yang saya rekomendasikan:

1. Laptop build APK signed.
2. Laptop hitung checksum SHA-256.
3. Laptop upload:
   - file APK
   - file manifest JSON
   - checksum
4. File diupload ke private bucket Supabase Storage.
5. HP polling ringan atau subscribe notifikasi update.
6. Jika versi lebih baru:
   - HP download manifest
   - verifikasi checksum
   - verifikasi signature APK
   - tampilkan notifikasi `Update tersedia`
7. Anda tap install.

## 4. Data Default Yang Akan Saya Pakai

Kalau Anda tidak mau banyak setting, saya akan pakai default:

- bucket: `kicryp-releases`
- channel: `stable-private`
- manifest path: `android/stable/latest.json`
- artifact name: `kicryp-android-latest.apk`

## 5. Kenapa Tidak Full Otomatis Install

Alasannya keamanan Android:

- sistem membatasi install APK diam-diam
- app biasa tidak boleh mengganti dirinya sendiri tanpa interaksi user
- ini justru bagus untuk mencegah malware atau update berbahaya

Jadi untuk app trading private, model `download + verify + tap install` adalah kompromi paling aman.

## 6. Opsi Developer Cepat

Saat development, kalau HP dan laptop satu jaringan:

- pakai ADB over Wi-Fi
- laptop build debug APK
- laptop install langsung ke HP

Ini cepat untuk testing, tapi bukan channel update produksi/private yang rapi.

## 7. Rencana Implementasi

Langkah teknis yang akan saya lanjutkan:

1. schema manifest update
2. uploader dari laptop
3. Android update checker
4. checksum verification
5. notifikasi update
6. install handoff ke Android package installer

V1 target:

- update private
- ringan
- aman
- tidak butuh Play Store

## 8. Script Yang Sudah Disiapkan

Untuk menyiapkan artifact release private dari laptop:

```bash
infra/config/generate_release_keystore.sh
infra/config/build_android_release.sh
```

Untuk memasang build terbaru ke HP yang sudah terhubung via USB atau ADB over Wi-Fi:

```bash
infra/config/install_android_release.sh
```

Output default:

- APK: `.dist/android/stable/kicryp-android-latest.apk`
- manifest: `.dist/android/stable/latest.json`

Upload ke private bucket Supabase bisa ditambahkan setelah auth owner Supabase aktif.
