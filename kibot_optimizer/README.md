# KiCryp Optimizer (Manager-Only Local Scenarios)

Folder ini khusus untuk optimisasi **KiCryp sebagai otak manajer**, terpisah dari mesin eksekusi:
- `KiDax` = executor Indodax
- `Kinance` = executor Binance
- `KiCryp` = manager/auditor/pengarah target

## Tujuan
- Uji local story-based scenario supaya KiCryp dipakai maksimal.
- Pastikan KiCryp:
  - membaca kondisi dua bot sekaligus,
  - menegur bila pace target meleset,
  - memberi command adaptif terpisah ke KiDax/Kinance,
  - mengeluarkan laporan ringkas ke dashboard (human report).

## File
- `scenarios.json`: kumpulan skenario cerita lokal.
- `run_local_kicryp_scenarios.py`: runner + assertion otomatis.

## Jalankan
```bash
python3 kicryp_optimizer/run_local_kicryp_scenarios.py
```

Jika semua lulus, output akan menampilkan:
- status `PASS` per skenario
- command KiCryp ke tiap bot
- manager report ringkas yang siap dipush ke app/web.
