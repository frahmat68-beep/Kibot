# Trading Intelligence

Dokumen ini merangkum otak bot KiCryp yang ditambahkan pada FASE 2.

## Pilar Utama

- `Safety first`: bot tidak boleh entry kalau state, lease, data, atau health belum bersih.
- `Growth-oriented`: bot tetap mencari peluang yang layak, bukan sekadar diam terus.
- `Explainable`: semua mode, score, dan adaptasi mingguan punya alasan yang bisa dibaca.
- `Bounded learning`: yang adaptif hanya parameter ringan. Hard safety rules tidak berubah.

## Universe Scanner

Scanner dibagi 2 tahap:

1. Scan lebar semua pair untuk volume, spread, slippage, stabilitas order book, dan aktivitas market.
2. Shortlist pair sehat saja untuk diberi ranking lebih detail.

Scanner tidak memakai harga nominal murah sebagai alasan utama. Prioritas utamanya:

- likuiditas
- spread
- slippage
- kualitas order book
- kualitas fill
- trend sehat
- expectancy historis

## Pair Scoring

Setiap pair sekarang dinilai dengan skor berikut:

- liquidity score
- spread score
- slippage score
- stability score
- volume consistency score
- volatility quality score
- trend quality score
- historical expectancy score
- recent health score
- fill quality score
- holdability score

Hasil akhirnya dibagi:

- `TIER_A`: pair default allowed
- `TIER_B`: pair conditional
- `TIER_C`: pair forbidden

## Market Brain

Engine sekarang punya market regime analyzer untuk mengelompokkan market menjadi:

- `HEALTHY_UPTREND`
- `HEALTHY_SIDEWAYS`
- `HIGH_VOLATILITY_UNCLEAR`
- `BREAKDOWN_PANIC`

Regime ini ikut menentukan bias tactical vs swing, mode bot, dan agresivitas modal.

## Bot Modes

Bot punya 4 mode utama:

- `SAFE`
- `DEFENSIVE`
- `GROWTH`
- `ATTACK`

Mode dipilih dari gabungan:

- market opportunity score
- bot health score
- performance momentum score
- edge confidence
- risk ladder
- profit protection

## Risk Ladder

Risk engine sekarang memakai eskalasi bertahap:

- `WARNING`
- `REDUCE_SIZE`
- `DEFENSIVE_MODE`
- `RESTRICTED_NEW_ENTRIES`
- `STOP_NEW_ENTRIES`
- `HARD_STOP`

Hard emergency ceiling tetap `25%` dari modal awal harian.

## Profit Protection

Profit protection melacak:

- high watermark equity
- giveback setelah profit
- weekly profit guard

Kalau bot sudah profit lalu mulai giveback terlalu besar, agresivitas dan size akan diturunkan otomatis.

## Weekly Learning Loop

Review mingguan sekarang merangkum:

- pair terbaik / terburuk
- setup terbaik / terburuk
- jam terbaik / terburuk
- false entry rate
- no-trade quality
- tactical expectancy
- swing expectancy
- utilization modal
- missed opportunity rate

Output review mingguan dapat memberi:

- whitelist pair ringan
- blacklist sementara
- penyesuaian jam aktif
- penyesuaian bias tactical/swing
- adjustment kecil untuk aggression dan size

Semua perubahan tetap kecil, bertahap, dan tidak boleh menyentuh fondasi keselamatan.
