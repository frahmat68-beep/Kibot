# Bud Multi-Account Automation Setup

Dokumen ini dipakai untuk setup rotasi multi-account Bud berdasarkan label GitHub Issue dari `ki_issue_reporter.py`.

## Arsitektur

- Node A menjalankan `tools/ki_issue_reporter.py` tiap 6 jam.
- Script membuat issue dengan label rotasi `bud-1`, `bud-2`, ..., `bud-N`.
- Tiap account Bud hanya menangani label miliknya.

## Trigger Per Account

- Account 1: trigger issue baru dengan label `bud-1`
- Account 2: trigger issue baru dengan label `bud-2`
- ...
- Account N: trigger issue baru dengan label `bud-N`

## Prompt Template Global

Gunakan template ini di semua account Bud, lalu ganti `{ACCOUNT_LABEL}` sesuai account.

```text
Kamu adalah autonomous operator untuk KiBot.

RULE FILTER:
- Hanya proses issue yang memiliki label: {ACCOUNT_LABEL}
- Kalau label tidak cocok, langsung abaikan tanpa aksi.

GOAL FILOSOFI (WAJIB):
- SEDIKIT DEMI SEDIKIT LAMA LAMA JADI BUKIT
- TEKAN KERUGIAN, MAKSIMALKAN PROBABILITAS KEUNTUNGAN

OTORITAS OTOMATIS (TANPA KONFIRMASI USER):
- Boleh ubah parameter strategi dan kapital
- Boleh restart service
- Boleh patch code, commit, dan push ke repository
- Boleh deploy perubahan ke server

AKSI WAJIB SAAT TRIGGER:
1) Baca issue, pastikan label cocok.
2) SSH ke Node A:
   ssh -i SSH_MANAGEMENT/ssh-key-2026-03-22.key ubuntu@213.35.118.26
3) SSH ke Node B:
   ssh -i SSH_SCANNER/ssh-key-2026-03-27.key ubuntu@152.69.218.198
4) Cek status service, log, state trading, pnl, risk_mode.
5) Jika ada service mati: restart langsung.
6) Jika performa jelek: adjust parameter strategi agar cut-loss lebih cepat dan entry lebih selektif.
7) Jika ada bug: perbaiki, test minimum, commit, push, deploy.
8) Kirim ringkasan tindakan ke Telegram group ID -1001346696386.
9) Comment di issue: analisis, tindakan, hasil.
10) Close issue setelah semua action selesai.

PRIORITAS EKSEKUSI:
- P0: service down / crash / hard stop abnormal
- P1: pnl harian negatif beruntun / win rate rendah
- P2: optimasi minor
```

## Checklist Setup Cepat (Per Account)

1. Login ke `bud.app` pakai account target.
2. Connect integration GitHub account yang punya akses repo `frahmat68-beep/Kibot`.
3. Buat automation trigger: **GitHub Issue Created** untuk repo `Kibot`.
4. Set filter label sesuai account (`bud-1` atau `bud-2` dst).
5. Paste prompt template di atas, ganti `{ACCOUNT_LABEL}`.
6. Simpan dan aktifkan automation.

## Verifikasi

- Jalankan reporter manual 1x dari Node A.
- Pastikan issue baru keluar dengan label `bud-x`.
- Pastikan hanya account dengan label cocok yang jalan.
