# KiBot Polymarket Engine

## Purpose

Polymarket di KiBot sekarang punya 3 fungsi:

1. executor prediction market
2. research layer untuk sovereign brain
3. cross-market alpha source untuk pair Indodax

## Data Sources

- Gamma API:
  - market discovery
  - liquidity
  - volume
  - spread
  - rewards metadata
- Data API:
  - positions
  - activity
  - value
- CLOB / SDK:
  - order execution

## Output State

`/api/state` Batam sekarang memuat:

- `top_opportunities`
- `maker_candidates`
- `alpha_candidates`
- `cross_market_bias`
- `wallet_summary`
- `ops_alerts`

## How KiBot Uses It

### Maker / Rebate

Market yang:

- spread rapat
- liquidity tinggi
- fee-enabled
- reward profile layak

akan naik ke `maker_candidates`.

### Alpha to Indodax

Market yang menyebut aset crypto dan punya conviction cukup akan dipetakan ke:

- asset
- direction
- mapped pair
- alpha score

hasil agregasinya muncul di `cross_market_bias`.

Brain bisa memakai bias ini untuk menaikkan atau menurunkan perhatian ke pair Indodax.

## Important Limit

Ini belum menggantikan fair-value model penuh. Artinya:

- cross-market bias masih heuristic
- sizing final tetap wajib lewat sovereign plan + hard guard
- jangan perlakukan every Polymarket move sebagai sinyal entry langsung
