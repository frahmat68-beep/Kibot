# 🔥 KIBOT TRINITY — MICRO-CAP OPTIMIZATION

**Last Updated:** 2026-04-02  
**Status:** Server Audit Complete, Code Optimized, Ready for Deploy

---

## ✅ COMPLETED FIXES

### Server Configuration
- [x] Stop conflicting services (kibot-engine on Indodax, kidax-engine on Binance)
- [x] Fix UDP target IP (10.0.0.84 → 152.69.218.198)
- [x] Fix Groq model (llama-3.3-70b → llama-3.1-8b-instant)
- [x] Memory optimizations (JVM heap reduction, swap clear)

### Code Optimizations
- [x] ChartAnalyzer micro-cap thresholds (spread 1.4%→2.5%, slippage 1.6%→2.8%)
- [x] PairSelectionPolicy micro-cap aggressive (volume 10M→5M IDR)
- [x] RiskConfig tighter loss limits (5%→3% daily, Rp15K→Rp10K hard limit)
- [x] Dynamic trailing stop micro-cap boost (up to 7% for <Rp50 coins)
- [x] Expanded correlation families (meme, ai, defi, gaming, microcap)
- [x] Updated architecture.md to Trinity docs

---

## 📋 REMAINING TASKS

### Critical
- [ ] Deploy code changes to servers
- [ ] Restart all 3 services and verify UDP connectivity
- [ ] Monitor first hour of trading

### Enhancement
- [ ] Add UDP ACK protocol for signal reliability
- [ ] Implement faster post-mortem learning
- [ ] Fix DRX decimal precision in IndodaxClient
- [ ] Add BTC/ETH pump-dump correlation alerts

---

## 🚀 DEPLOY COMMANDS

```bash
# Local: Build fat JAR
./gradlew :apps:mac-engine:shadowJar

# Indodax server
scp build/libs/mac-engine-all.jar ubuntu@213.35.118.26:/home/ubuntu/KiDax/
ssh ubuntu@213.35.118.26 'sudo systemctl restart kidax-engine kibot-manager'

# Binance server
scp build/libs/mac-engine-all.jar ubuntu@152.69.218.198:/home/ubuntu/Kinance/
ssh ubuntu@152.69.218.198 'sudo systemctl restart kinance-engine'

# Verify
ssh ubuntu@213.35.118.26 'journalctl -u kidax-engine -f'
```

---

## 📊 MICRO-CAP SETTINGS SUMMARY

| Parameter | Old | New | Reason |
|-----------|-----|-----|--------|
| minHealthyDailyVolumeIdr | 140M | 50M | Allow small cap coins |
| absoluteMaxSpreadPct | 1.4% | 2.5% | Wider for illiquid |
| absoluteMaxSlippagePct | 1.6% | 2.8% | Wider for illiquid |
| hardDailyLossLimitPct | 5% | 3% | Tighter protection |
| targetMinPositionBudgetIdr | 28K | 15K | Smaller positions |
| trailingStop (<Rp50) | 5% | 7% | Ultra micro-cap boost |

