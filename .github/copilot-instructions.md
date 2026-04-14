# KIBOT TRINITY — Copilot Instructions v6.0
# MATH-FIRST | MULTI-POSITION | FULL UNIVERSE | AI-CMS
# Last verified: 2026-04 | Jangan ubah tanpa konfirmasi owner

## PRIME DIRECTIVE

Filosofi: SURVIVAL FIRST. COMPOUNDING GRADUAL.
Motto: "TEKAN KERUGIAN, MAKSIMALKAN PROBABILITAS KEUNTUNGAN"

Wajib dipatuhi:
1. Exit protection SACRED — trailing stop & cut loss jalan di SEMUA state
2. Agresivitas NAIK hanya jika: 3 clean days + API healthy + instruksi eksplisit
3. Daily hard stop TIDAK bisa di-bypass
4. CONSERVATIVE = default state startup
5. Math = primary engine. AI = support + CMS discovery only
6. LIMIT default. MARKET hanya: emergency + breakout A+ (<150ms)
7. Max 5 posisi aktif sekaligus dengan kontrol penuh per posisi

---

## 1. ARSITEKTUR

```
KINANCE (Binance radar, port 8788)
  → UDP signal <500ms (Kategori A pairs)
KIBOT MANAGER (brain, port 9998)
  → UDP heartbeat 100ms
  → Portfolio manager (max 5 posisi)
  → AI-CMS (coin discovery, every 6h)
KIDAX (Indodax executor, port 8787)
  → REST API Indodax
  → Multi-order management
```

---

## 2. PAIR UNIVERSE — 3 KATEGORI

### Kategori A: LEAD-LAG (ada di Binance Spot → Kinance bisa monitor)
Strategi: entry berdasarkan Kinance signal + pump legitimacy score

```python
LEAD_LAG_PAIRS = {
    # === TIER 1 — Volume Tinggi, Likuiditas Baik ===
    "btc_idr":  "BTCUSDT",   "eth_idr":  "ETHUSDT",
    "xrp_idr":  "XRPUSDT",   "sol_idr":  "SOLUSDT",
    "doge_idr": "DOGEUSDT",  "bnb_idr":  "BNBUSDT",
    "pepe_idr": "PEPEUSDT",  "ada_idr":  "ADAUSDT",
    "shib_idr": "SHIBUSDT",  "xlm_idr":  "XLMUSDT",
    "trx_idr":  "TRXUSDT",   "hbar_idr": "HBARUSDT",
    "sui_idr":  "SUIUSDT",   "dot_idr":  "DOTUSDT",
    "pol_idr":  "POLUSDT",   "bonk_idr": "BONKUSDT",

    # === TIER 2 — Volume Medium, Lead-lag valid ===
    "fet_idr":    "FETUSDT",    "render_idr": "RENDERUSDT",
    "pengu_idr":  "PENGUUSDT",  "anime_idr":  "ANIMEUSDT",
    "trump_idr":  "TRUMPUSDT",  "zen_idr":    "ZENUSDT",
    "iotx_idr":   "IOTXUSDT",   "moodeng_idr":"MOODENGUSDT",
    "mon_idr":    "MONUSDT",    "vanry_idr":  "VANRYUSDT",
    "mog_idr":    "MOGUSDT",    "spx_idr":    "SPXUSDT",
    "link_idr":   "LINKUSDT",   "avax_idr":   "AVAXUSDT",
    "near_idr":   "NEARUSDT",   "apt_idr":    "APTUSDT",
    "arb_idr":    "ARBUSDT",    "op_idr":     "OPUSDT",
    "atom_idr":   "ATOMUSDT",   "ltc_idr":    "LTCUSDT",
    "uni_idr":    "UNIUSDT",    "floki_idr":  "FLOKIUSDT",
    "enj_idr":    "ENJUSDT",    "fun_idr":    "FUNUSDT",
    "dusk_idr":   "DUSKUSDT",   "matic_idr":  "MATICUSDT",

    # === TIER 3 — Volume rendah, lead-lag lemah ===
    "pixel_idr":  "PIXELUSDT",  "paxg_idr":  "PAXGUSDT",
    "bch_idr":    "BCHUSDT",    "etc_idr":   "ETCUSDT",
    "hype_idr":   None,  # Hyperliquid — tidak ada di Binance
    "zerebro_idr":None,  # Zerebro — tidak ada di Binance spot
}
```

### Kategori B: FUTURES-ONLY di Binance (ada futures, tidak ada spot)
Strategi: Kinance monitor futures price action sebagai signal proxy
Tidak ada lead-lag langsung, tapi futures momentum bisa dipakai

```python
FUTURES_PROXY_PAIRS = {
    # Binance Futures ada, tapi Spot tidak — pakai futures sebagai proxy
    "fartcoin_idr": "FARTCOINUSDT",  # Binance Futures saja
    # PIPPIN: Binance Futures ada tapi Spot tidak + volume Indodax #2!
    # JELLYJELLY: Sudah delisted dari Binance Futures
}
```

### Kategori C: INDODAX-ONLY (tidak ada di Binance manapun)
Strategi: Pure technical analysis — BB, volume, momentum, RSI
Tidak ada Kinance signal, hanya screener berbasis chart

```python
INDODAX_ONLY_PAIRS = [
    # HIGH VOLUME — prioritas utama
    "pippin_idr",      # Volume #2 di Indodax! ($7.56M) — WAJIB di-support
    "myx_idr",         # MYX Finance — volume bagus
    "jellyjelly_idr",  # JellyJelly — volume aktif
    "aster_idr",       # Aster — volume medium
    "islm_idr",        # Islamic Coin — volume medium
    "hype_idr",        # Hyperliquid — tidak ada di Binance sama sekali

    # MEDIUM VOLUME
    "gravity_idr", "trollsol_idr", "whitewhale_idr",
    "wealth_idr",  "mubarak_idr",  "fanc_idr",
    "nova_idr",    "mrs_idr",      "xpl_idr",

    # Dari AI-CMS Discovery (update otomatis)
    # ... diisi oleh AI discovery system
]
```

---

## 3. MULTI-POSITION PORTFOLIO MANAGER (max 5 sekaligus)

```python
PORTFOLIO_CONFIG = {
    "max_concurrent_positions": 5,
    "max_budget_per_position_pct": 0.20,  # Max 20% equity per posisi
    "min_budget_per_position_idr": 10_000, # Min Rp 10,000 per posisi

    # Alokasi per kategori
    "max_lead_lag_positions": 3,    # Max 3 posisi Kategori A
    "max_indodax_only_positions": 2, # Max 2 posisi Kategori C

    # Diversifikasi — jangan konsentrasi
    "max_same_sector_positions": 2,  # Max 2 koin dari sektor yang sama
    # Sektor: meme, defi, layer1, layer2, ai_token, gaming, local_idr
}

# Portfolio state — tracking semua posisi aktif
class PortfolioManager:
    def __init__(self):
        self.positions: dict[str, Position] = {}  # pair_id → Position
        self.daily_pnl_idr: float = 0.0
        self.daily_pnl_pct: float = 0.0

    def can_open_position(self, pair_id: str, category: str) -> tuple[bool, str]:
        """Check semua kondisi sebelum buka posisi baru."""
        total = len(self.positions)
        if total >= PORTFOLIO_CONFIG["max_concurrent_positions"]:
            return False, f"Max {PORTFOLIO_CONFIG['max_concurrent_positions']} posisi tercapai"

        if pair_id in self.positions:
            return False, f"Sudah ada posisi {pair_id}"

        lead_lag_count = sum(1 for p in self.positions.values()
                             if p.category == "LEAD_LAG")
        local_count = sum(1 for p in self.positions.values()
                          if p.category == "INDODAX_ONLY")

        if category == "LEAD_LAG" and lead_lag_count >= 3:
            return False, "Max lead-lag positions (3) tercapai"
        if category == "INDODAX_ONLY" and local_count >= 2:
            return False, "Max indodax-only positions (2) tercapai"

        # Cek sektor diversifikasi
        new_sector = get_pair_sector(pair_id)
        sector_count = sum(1 for p in self.positions.values()
                           if get_pair_sector(p.pair_id) == new_sector)
        if sector_count >= PORTFOLIO_CONFIG["max_same_sector_positions"]:
            return False, f"Sektor {new_sector} sudah penuh ({sector_count})"

        return True, "OK"

    def get_available_budget(self) -> float:
        """Hitung budget yang tersedia untuk posisi baru."""
        total_equity = get_total_equity()
        allocated = sum(p.budget_idr for p in self.positions.values())
        available = total_equity - allocated
        # Max per posisi = 20% equity
        max_per_position = total_equity * 0.20
        return min(available, max_per_position)

    def update_pnl(self):
        """Update PnL total dari semua posisi aktif + closed hari ini."""
        total_unrealized = sum(
            p.unrealized_pnl_idr for p in self.positions.values()
        )
        self.daily_pnl_idr = self.realized_pnl_today + total_unrealized
        total_equity = get_total_equity()
        self.daily_pnl_pct = self.daily_pnl_idr / max(total_equity, 1)
```

---

## 4. STRATEGI INDODAX-ONLY (tanpa lead-lag)

Koin seperti PIPPIN tidak ada di Binance → tidak ada Kinance signal.
Gunakan pure technical analysis + volume anomaly detection.

```python
def analyze_indodax_only(pair_id: str, ticker: dict, bb: dict) -> dict:
    """
    Analisis koin Indodax-only tanpa lead-lag signal.
    Return: entry recommendation berdasarkan pure technicals.
    """
    price     = float(ticker.get("last", 0))
    vol_1h    = float(ticker.get("vol_idr_1h", 0))
    vol_24h   = float(ticker.get("vol_idr", 0))
    high_24h  = float(ticker.get("high", price))
    low_24h   = float(ticker.get("low", price))

    score = 0.0
    reasons = []

    # === VOLUME ANOMALY ===
    # Koin indodax-only sering pump karena volume spike organik lokal
    avg_vol_per_hour = vol_24h / 24
    vol_ratio_1h = vol_1h / max(avg_vol_per_hour, 1)

    if vol_ratio_1h >= 5.0:
        score += 30
        reasons.append(f"VOLUME SPIKE EKSTREM: {vol_ratio_1h:.1f}x avg")
    elif vol_ratio_1h >= 3.0:
        score += 22
        reasons.append(f"Volume spike kuat: {vol_ratio_1h:.1f}x avg")
    elif vol_ratio_1h >= 2.0:
        score += 12
        reasons.append(f"Volume spike moderate: {vol_ratio_1h:.1f}x avg")
    else:
        score += 0
        reasons.append(f"Volume normal: {vol_ratio_1h:.1f}x avg")

    # Volume minimum untuk masuk
    if vol_24h < 100_000_000:  # < 100M IDR
        score -= 25
        reasons.append(f"Volume terlalu kecil: Rp{vol_24h/1e9:.2f}B")

    # === BB POSITION ===
    if bb:
        bb_pct = (price - bb["lower"]) / max(bb["upper"] - bb["lower"], 0.001)
        if bb_pct < 0.40:
            score += 25
            reasons.append(f"Di bawah BB middle ({bb_pct:.0%}) — banyak ruang")
        elif bb_pct < 0.65:
            score += 16
            reasons.append(f"BB mid zone ({bb_pct:.0%})")
        elif bb_pct > 0.92:
            score -= 15
            reasons.append(f"OVERBOUGHT BB ({bb_pct:.0%})")

    # === PUMP PHASE ===
    pos_in_range = (price - low_24h) / max(high_24h - low_24h, 0.001)
    if pos_in_range < 0.35:
        score += 20
        reasons.append(f"EARLY phase ({pos_in_range:.0%} dari range)")
    elif pos_in_range < 0.60:
        score += 14
        reasons.append(f"MID phase ({pos_in_range:.0%} dari range)")
    elif pos_in_range > 0.88:
        score -= 18
        reasons.append(f"LATE/PEAK ({pos_in_range:.0%}) — risiko tinggi")

    # === SPREAD CHECK (indodax-only biasanya spread lebar) ===
    bid = float(ticker.get("buy", price * 0.99))
    ask = float(ticker.get("sell", price * 1.01))
    spread_pct = (ask - bid) / max(bid, 0.001)

    if spread_pct > 0.05:  # > 5% spread
        score -= 20
        reasons.append(f"SPREAD LEBAR: {spread_pct:.1%} — exit sulit")
    elif spread_pct > 0.02:
        score -= 8
        reasons.append(f"Spread medium: {spread_pct:.1%}")

    # === MINIMUM SCORE UNTUK INDODAX-ONLY ===
    # Lebih ketat dari lead-lag karena tidak ada konfirmasi Binance
    min_score = 55  # Lebih tinggi dari lead-lag (45)

    recommendation = "ENTER" if score >= min_score else "SKIP"
    if pos_in_range > 0.88:
        recommendation = "SKIP"  # Override — terlambat

    return {
        "pair_id": pair_id,
        "category": "INDODAX_ONLY",
        "score": round(score, 1),
        "recommendation": recommendation,
        "phase": ("EARLY" if pos_in_range < 0.35
                  else "MID" if pos_in_range < 0.60
                  else "LATE" if pos_in_range < 0.88 else "PEAK"),
        "vol_ratio": round(vol_ratio_1h, 2),
        "bb_position": bb_pct if bb else None,
        "spread_pct": round(spread_pct, 4),
        "reasoning": " | ".join(reasons[:4]),
    }
```

---

## 5. AI-CMS COIN DISCOVERY SYSTEM

AI bertugas mencari koin baru yang listing di Indodax atau yang sedang ramai.
Math sistem yang memutuskan apakah layak masuk — AI hanya informan.

```python
# === AI-CMS: COIN DISCOVERY ENGINE ===
# Berjalan setiap 6 jam

DISCOVERY_PROMPT = """
Kamu adalah crypto market analyst untuk trading bot Indonesia.
Tugas: Identifikasi koin yang SEDANG atau AKAN SEGERA pump di Indodax.

Cari dan analisis:
1. Koin baru listing di Indodax dalam 7 hari terakhir
2. Koin yang sedang ramai di Twitter/X Indonesia dengan tag #Indodax
3. Koin yang volume Indodax naik > 200% dari kemarin
4. Koin yang ada di Binance dan harganya mulai naik (lead-lag candidate)
5. Meme coin atau AI token yang viral di Solana ecosystem

Untuk setiap koin yang ditemukan, berikan:
- symbol (contoh: "PIPPIN")
- indodax_pair (contoh: "pippin_idr" — HANYA jika ada di Indodax)
- binance_pair (contoh: "PIPPINUSDT" — HANYA jika ada di Binance spot)
- category: "LEAD_LAG" / "INDODAX_ONLY" / "FUTURES_ONLY"
- reason: kenapa menarik (max 30 kata)
- urgency: "NOW" / "WATCH" / "MONITOR"

Format respons HANYA JSON array, tidak ada teks lain:
[{"symbol":"XXX","indodax_pair":"xxx_idr","binance_pair":null,"category":"INDODAX_ONLY","reason":"...","urgency":"WATCH"}]

Batasan:
- Jangan rekomendasikan koin yang tidak ada di Indodax
- Jangan rekomendasikan koin dengan volume < 50M IDR/hari
- Jangan rekomendasikan koin yang sudah pump > 80% dalam 24 jam
- Selalu berikan data faktual, bukan spekulasi
"""

async def run_ai_coin_discovery():
    """
    Jalankan AI untuk cari koin baru/sedang ramai.
    Math sistem yang decide apakah worth masuk.
    """
    import json

    # Coba semua AI provider (fallback chain)
    discovery_result = None
    for provider in ["groq", "openrouter", "cohere", "gemini"]:
        try:
            raw = await call_ai_provider(provider, DISCOVERY_PROMPT)
            # Parse JSON dari response
            # Strip markdown kalau ada
            clean = raw.strip()
            if "```json" in clean:
                clean = clean.split("```json")[1].split("```")[0].strip()
            elif "```" in clean:
                clean = clean.split("```")[1].split("```")[0].strip()

            coins = json.loads(clean)
            if isinstance(coins, list) and len(coins) > 0:
                discovery_result = coins
                logger.info(f"[AI-CMS] {provider} found {len(coins)} new candidates")
                break
        except Exception as e:
            logger.debug(f"[AI-CMS] {provider} failed: {e}")
            continue

    if not discovery_result:
        logger.info("[AI-CMS] No discovery result — using existing pair list")
        return

    # Validasi setiap koin yang ditemukan AI
    validated_new = []
    for coin in discovery_result:
        pair_id = coin.get("indodax_pair", "")
        if not pair_id or not pair_id.endswith("_idr"):
            continue

        # Cek apakah ada di Indodax live
        ticker = await fetch_indodax_ticker(pair_id)
        if not ticker:
            logger.debug(f"[AI-CMS] {pair_id} not found on Indodax — skip")
            continue

        vol_24h = float(ticker.get("vol_idr", 0))
        if vol_24h < 50_000_000:
            logger.debug(f"[AI-CMS] {pair_id} volume too low: {vol_24h/1e6:.0f}M")
            continue

        category = coin.get("category", "INDODAX_ONLY")
        binance_pair = coin.get("binance_pair")

        # Update pair mapping jika kategori lead-lag
        if category == "LEAD_LAG" and binance_pair:
            if pair_id not in LEAD_LAG_PAIRS:
                LEAD_LAG_PAIRS[pair_id] = binance_pair
                logger.info(f"[AI-CMS] NEW lead-lag pair: {pair_id} → {binance_pair}")

        # Update local pairs
        if category == "INDODAX_ONLY":
            if pair_id not in INDODAX_ONLY_PAIRS:
                INDODAX_ONLY_PAIRS.append(pair_id)
                logger.info(f"[AI-CMS] NEW local pair: {pair_id}")

        validated_new.append({
            "pair_id": pair_id,
            "category": category,
            "reason": coin.get("reason", "AI discovered"),
            "urgency": coin.get("urgency", "WATCH"),
            "vol_24h": vol_24h,
        })

    if validated_new:
        # Telegram report
        msg = f"🤖 [AI-CMS] {len(validated_new)} koin baru ditemukan:\n"
        for c in validated_new[:5]:
            urgency_emoji = "🔥" if c["urgency"] == "NOW" else "👀"
            msg += (f"{urgency_emoji} {c['pair_id'].replace('_idr','').upper()}: "
                    f"{c['reason']}\n")
        self._send_telegram(msg)

        # Jika ada koin dengan urgency NOW → langsung masuk queue screening
        urgent = [c for c in validated_new if c["urgency"] == "NOW"]
        if urgent:
            logger.info(f"[AI-CMS] {len(urgent)} URGENT coins → priority queue")
            for c in urgent:
                _priority_scan_queue.add(c["pair_id"])
```

---

## 6. ENTRY GATE UNTUK MULTI-POSITION

```python
def _process_signal_multipos(self, msg: dict):
    """
    Entry gate untuk multi-position system.
    Versi upgrade dari _process_signal() single position.
    """
    pair_id  = msg.get("pairId", msg.get("pair_id", ""))
    category = classify_pair(pair_id)  # "LEAD_LAG" / "FUTURES_PROXY" / "INDODAX_ONLY"

    # === GATE 0: PORTFOLIO CAPACITY ===
    can_open, reason = portfolio_manager.can_open_position(pair_id, category)
    if not can_open:
        logger.debug(f"[GATE0] {pair_id}: {reason}")
        return

    # === GATE 1: PNL STATE ===
    pnl_state = portfolio_manager.get_pnl_state()
    if pnl_state == "FULL_STOP":
        return
    if pnl_state == "HARD_STOP":
        if not _is_one_shot_eligible(msg, msg.get("score", 0)):
            return

    # === GATE 2: HARD STOP DISK ===
    if _is_hard_stop_active() and not msg.get("one_shot_mode"):
        return

    # === GATE 3: CAPITAL MINIMUM ===
    available_budget = portfolio_manager.get_available_budget()
    if available_budget < 10_000:
        logger.info(f"[GATE3] Budget tersedia Rp{available_budget:,.0f} < min")
        return

    # === GATE 4: CATEGORY-SPECIFIC CHECK ===
    if category == "LEAD_LAG":
        # Cek Kinance signal TTL
        signal_age_ms = msg.get("signalAgeMs", 999)
        if signal_age_ms > 500:
            return
        # Cek Binance pair tersedia
        binance_pair = LEAD_LAG_PAIRS.get(pair_id)
        if not binance_pair:
            return

    elif category == "INDODAX_ONLY":
        # Tidak ada Kinance signal — jalankan pure technical
        ticker = await fetch_indodax_ticker(pair_id)
        bb     = calculate_bollinger_bands(pair_id)
        analysis = analyze_indodax_only(pair_id, ticker, bb)
        if analysis["recommendation"] != "ENTER":
            logger.debug(f"[GATE4] {pair_id} technical: {analysis['recommendation']}")
            return
        msg["pump_analysis"] = analysis

    # === GATE 5: PUMP LEGITIMACY SCORE ===
    pump_score = msg.get("pump_score", 0)
    min_score = 55 if category == "INDODAX_ONLY" else 45
    if pump_score < min_score:
        logger.debug(f"[GATE5] {pair_id} score {pump_score} < {min_score}")
        return

    # === GATE 6: WHAT-IF EV ===
    # Sesuaikan fee berdasarkan order type
    use_market = (category == "LEAD_LAG" and
                  msg.get("signalAgeMs", 999) < 150 and
                  pump_score >= 70)
    budget_idr = min(available_budget, portfolio_manager.get_max_position_size())
    ev_result  = simulate_what_if(pair_id, budget_idr,
                                  use_market=use_market)
    if ev_result.recommendation == "SKIP":
        return
    if ev_result.recommendation == "REDUCE_SIZE":
        budget_idr *= 0.60

    # === GATE 7: LEARNING ===
    blocked, reason = should_block_pair(pair_id)
    if blocked:
        return

    # === GATE 8: AI VETO (SOFT) ===
    # AI hanya warning, tidak hard block

    # === EXECUTE ===
    order_type = "MARKET" if use_market else "LIMIT"
    logger.info(
        f"[MULTIPOS] ENTER {pair_id} ({category}) "
        f"{order_type} Rp{budget_idr:,.0f} "
        f"[{len(portfolio_manager.positions)+1}/5]"
    )
    # Submit order...
```

---

## 7. PORTFOLIO PNL MONITORING (30 detik)

```python
def _check_portfolio_pnl(self):
    """
    Monitor semua posisi aktif setiap 30 detik.
    Update trailing stop, detect peak, trigger exit jika perlu.
    """
    portfolio_manager.update_pnl()
    pnl_pct = portfolio_manager.daily_pnl_pct

    # === PNL STATE MACHINE ===
    if pnl_pct <= -0.02 and not _is_hard_stop_active():
        _trigger_hard_stop(f"portfolio pnl={pnl_pct:.3f}")
        return

    # === UPDATE SETIAP POSISI AKTIF ===
    for pair_id, position in list(portfolio_manager.positions.items()):
        try:
            current_price = fetch_current_price(pair_id)
            market_data   = fetch_market_data(pair_id)

            # Update trailing stop
            new_stop = calculate_dynamic_trailing(position, current_price, market_data)
            if new_stop != position.stop_price:
                position.stop_price = new_stop
                logger.debug(f"[TRAIL] {pair_id} stop → {new_stop:.6f}")

            # Peak detection
            should_exit, exit_reason = detect_peak_or_stop(position, current_price, market_data)
            if should_exit:
                logger.info(f"[EXIT] {pair_id}: {exit_reason}")
                await execute_exit(pair_id, position, exit_reason)

            # Partial TP
            if position.profit_pct >= 0.015 and not position.partial_tp_done:
                await execute_partial_exit(pair_id, position, size_pct=0.40)

        except Exception as e:
            logger.error(f"[PORTFOLIO] {pair_id} error: {e}")

    # === LOG PORTFOLIO STATUS ===
    if time.time() - _last_portfolio_log > 300:  # setiap 5 menit
        _last_portfolio_log = time.time()
        active = len(portfolio_manager.positions)
        if active > 0:
            pos_summary = ", ".join(
                f"{p.replace('_idr','').upper()}({v.profit_pct:+.1%})"
                for p, v in portfolio_manager.positions.items()
            )
            logger.info(
                f"[PORTFOLIO] {active}/5 aktif | "
                f"PnL harian: {pnl_pct:+.2%} | {pos_summary}"
            )
```

---

## 8. 30-MINUTE MATH REVIEW (per-pair + total)

```python
def run_30min_math_review():
    """Pure math review — tidak butuh AI."""
    global _score_multiplier

    # Total portfolio stats
    total_trades = len(_trades_session)
    wins   = [t for t in _trades_session if t["pnl"] > 0]
    losses = [t for t in _trades_session if t["pnl"] <= 0]

    if total_trades > 0:
        win_rate = len(wins) / total_trades
        avg_win  = sum(t["pnl"] for t in wins) / max(len(wins), 1)
        avg_loss = abs(sum(t["pnl"] for t in losses)) / max(len(losses), 1)
        ev       = (win_rate * avg_win) - ((1 - win_rate) * avg_loss)
        pf       = (sum(t["pnl"] for t in wins) /
                    max(abs(sum(t["pnl"] for t in losses)), 1))
    else:
        win_rate = 0.5; ev = 0; pf = 1.0

    # Stats per kategori
    leadlag_trades = [t for t in _trades_session if t.get("category") == "LEAD_LAG"]
    local_trades   = [t for t in _trades_session if t.get("category") == "INDODAX_ONLY"]
    ll_wr = (sum(1 for t in leadlag_trades if t["pnl"] > 0) /
             max(len(leadlag_trades), 1))
    lo_wr = (sum(1 for t in local_trades if t["pnl"] > 0) /
             max(len(local_trades), 1))

    # Portfolio saat ini
    pnl_pct = portfolio_manager.daily_pnl_pct
    equity  = get_total_equity()
    active  = len(portfolio_manager.positions)

    # Auto-adjust threshold
    if ev <= 0 and total_trades >= 3:
        _score_multiplier = min(_score_multiplier * 1.15, 1.5)
        action = "TIGHTEN"
    elif win_rate >= 0.65 and ev > 0:
        _score_multiplier = max(_score_multiplier * 0.97, 1.0)
        action = "OPTIMAL"
    else:
        action = "CONTINUE"

    # Telegram report
    from datetime import datetime, timedelta
    now_wib = (datetime.utcnow() + timedelta(hours=7)).strftime("%H:%M")
    pnl_e = "🟢" if pnl_pct >= 0 else "🔴"

    report = (
        f"📊 [{now_wib} WIB] Portfolio Review\n"
        f"{pnl_e} PnL: {pnl_pct:+.2%} | Modal: Rp{equity:,.0f}\n"
        f"🎯 Posisi: {active}/5 aktif\n"
        f"📈 {total_trades} trade WR={win_rate:.0%} EV=Rp{ev:+.0f}\n"
        f"🔗 Lead-lag WR={ll_wr:.0%} | Local WR={lo_wr:.0%}\n"
        f"🔧 Threshold x{_score_multiplier:.2f} | {action}"
    )
    _send_telegram(report)
```

---

## 9. GUARDRAILS (12 rules, tidak berubah)

1.  NO PANIC SELL ON UDP TIMEOUT
2.  ADAPTIVE TRAILING — koin <Rp500: 5-7%, koin >Rp100k: 2%
3.  RATIONAL QUARANTINE — max 15 menit per pair
4.  STRICT TTL — signal >500ms = STALE (lead-lag), indodax-only: tidak ada TTL
5.  SOFT AI-AUDIT — warning only
6.  DAILY HARD STOP — PnL <=-2% = persist disk, reset 00:00 WIB
7.  ONE_SHOT OVERRIDE — 1x setelah HARD_STOP, score>=8
8.  LIMIT ORDER DEFAULT — MARKET hanya emergency + breakout A+
9.  PUMP SCREEN WAJIB — legitimacy check untuk SEMUA pair
10. PERIODIC CHECK — PnL 30s, full review 30min, AI-CMS 6jam
11. MINIMUM CAPITAL — <30k: suspend, <10k: stop
12. NO AVERAGE DOWN — jangan tambah posisi yang sedang rugi

---

## 10. SYSTEM STATUS

### ✅ Implemented (sebelum session ini)
- Daily hard stop, PnL state machine, LIMIT-first order
- pair_memory learning, EXECUTION_FILLED
- Oracle keepalive, health watchdog

### 🔄 Implement This Session
- Multi-position portfolio manager (max 5)
- Complete LEAD_LAG_PAIRS mapping (44 pairs)
- INDODAX_ONLY strategy (pure technical)
- FUTURES_PROXY pairs (FARTCOIN, dll)
- AI-CMS coin discovery (every 6h)
- Binance pair mapping bug fix (XLMIDR → XLMUSDT)
- Real Bollinger Band dari candle data
- 30-min math review per kategori
- Data lifecycle server 3 hari → Supabase 30 hari
