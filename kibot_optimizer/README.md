# KiBot Optimizer (Manager-Only Local Scenarios)

Folder ini khusus untuk optimisasi **KiBot sebagai otak manajer**, terpisah dari mesin eksekusi:
- `KiDax` = executor Indodax
- `Kinance` = executor Binance
- `KiBot` = manager/auditor/pengarah target

## Tujuan
- Uji local story-based scenario supaya KiBot dipakai maksimal.
- Pastikan KiBot:
  - membaca kondisi dua bot sekaligus,
  - menegur bila pace target meleset,
  - memberi command adaptif terpisah ke KiDax/Kinance,
  - mengeluarkan laporan ringkas ke dashboard (human report).

## File
- `scenarios.json`: kumpulan skenario cerita lokal.
- `run_local_kibot_scenarios.py`: runner + assertion otomatis.

## Jalankan
```bash
python3 kibot_optimizer/run_local_kibot_scenarios.py
```

Jika semua lulus, output akan menampilkan:
- status `PASS` per skenario
- command KiBot ke tiap bot
- manager report ringkas yang siap dipush ke app/web.
