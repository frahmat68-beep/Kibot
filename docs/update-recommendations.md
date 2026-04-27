# Update Recommendations

KiCryp sekarang punya jalur advisory terpisah dari safety core:

- `weekly_learning_reviews`
  dipakai untuk ringkasan belajar mingguan dan adaptasi bounded
- `parameter_versions` dengan `scope = update_recommendation`
  dipakai untuk menyimpan rekomendasi update bot yang versioned
- `logs` kategori `LEARNING_HINT` dan `UPDATE_HINT`
  dipakai sebagai trigger ringan agar Codex bisa cepat membaca ada sinyal baru

## Cara cek

Jalankan:

```bash
python3 tools/check_update.py
```

Atau JSON:

```bash
python3 tools/check_update.py --json
```

## Prinsip

- advisory data tidak boleh mengubah lease, command queue, atau safety rule
- AI hanya support system, bukan penentu order
- rekomendasi update hanya memberi bukti dan saran, implementasinya tetap lewat review/patch Codex
