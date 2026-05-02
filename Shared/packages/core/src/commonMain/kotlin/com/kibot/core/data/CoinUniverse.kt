package com.kibot.core.data

/**
 * UNIFIED COIN UNIVERSE — Single source of truth untuk semua pair.
 *
 * Setiap entry berisi:
 * - indodaxSymbol: format Indodax (e.g. "btc_idr")
 * - binanceSymbol: format Binance USDT pair (e.g. "BTCUSDT"), null jika tidak ada di Binance
 * - correlationGroup: untuk lead-lag grouping
 * - tier: A=high liquidity, B=medium, C=low/micro
 * - minVolumeIdr: filter minimum volume 24H di Indodax
 * - isLeadLagEnabled: apakah pair ini ikut lead-lag dari Binance
 *
 * ATURAN PENAMBAHAN PAIR BARU:
 * 1. Cek Indodax API: GET https://indodax.com/api/pairs
 * 2. Cek Binance: apakah ada XYZUSDT di Binance
 * 3. Jika ada: isLeadLagEnabled = true, binanceSymbol = "XYZUSDT"
 * 4. Jika tidak ada di Binance: isLeadLagEnabled = false, binanceSymbol = null
 * 5. Set tier berdasarkan volume 24H IDR
 */
data class CoinEntry(
    val indodaxSymbol: String,         // "btc_idr"
    val binanceSymbol: String?,        // "BTCUSDT" atau null
    val correlationGroup: CorrelationGroup,
    val tier: CoinTier,
    val minVolumeIdr: Long,            // minimum 24H volume IDR untuk entry
    val isLeadLagEnabled: Boolean,
    val maxSpreadPct: Double = 2.5,    // max spread yang acceptable
    val trailingStopPct: Double = 3.0, // trailing stop default
    val notes: String = ""
)

enum class CoinTier { TIER_A, TIER_B, TIER_C }

enum class CorrelationGroup {
    BTC_FAMILY,    // BTC, BCH, BSV, LTC — ikut BTC
    ETH_FAMILY,    // ETH, ETC, ARB — ikut ETH
    SOL_FAMILY,    // SOL, TROLLSOL — ikut SOL
    MEME_COIN,     // DOGE, SHIB, PEPE, FARTCOIN, JELLYJELLY, BONK, MOODENG
    AI_TOKEN,      // FET, RENDER, NEAR, INJ
    DEFI_TOKEN,    // UNI, AAVE, COMP, SUI, HYPE
    GAMING,        // AXS, SAND, MANA
    MICRO_CAP,     // volume < 5Bn IDR, pump-chase strategy
    STABLECOIN,    // USDT, USDC — tidak di-trade, referensi saja
    UNKNOWN
}

object CoinUniverse {

    val ALL: List<CoinEntry> = listOf(

        // ═══ TIER A — HIGH LIQUIDITY (vol > 10 Bn IDR) ═══
        CoinEntry("btc_idr",  "BTCUSDT",   CorrelationGroup.BTC_FAMILY,  CoinTier.TIER_A, 10_000_000_000L, true,  1.0, 2.0),
        CoinEntry("eth_idr",  "ETHUSDT",   CorrelationGroup.ETH_FAMILY,  CoinTier.TIER_A, 10_000_000_000L, true,  1.2, 2.0),
        CoinEntry("sol_idr",  "SOLUSDT",   CorrelationGroup.SOL_FAMILY,  CoinTier.TIER_A,  5_000_000_000L, true,  1.5, 2.5),
        CoinEntry("xrp_idr",  "XRPUSDT",   CorrelationGroup.BTC_FAMILY,  CoinTier.TIER_A,  5_000_000_000L, true,  1.5, 2.5),
        CoinEntry("doge_idr", "DOGEUSDT",  CorrelationGroup.MEME_COIN,   CoinTier.TIER_A,  5_000_000_000L, true,  2.0, 3.0),
        CoinEntry("trx_idr",  "TRXUSDT",   CorrelationGroup.BTC_FAMILY,  CoinTier.TIER_A,  3_000_000_000L, true,  1.5, 2.5),
        CoinEntry("myx_idr",  null,        CorrelationGroup.MICRO_CAP,   CoinTier.TIER_A, 10_000_000_000L, false, 3.0, 5.0, "Indodax-only, high vol"),

        // ═══ TIER A MEME — volatile, high opportunity ═══
        CoinEntry("fartcoin_idr",  "FARTCOINUSDT", CorrelationGroup.MEME_COIN, CoinTier.TIER_A, 5_000_000_000L, true,  3.0, 4.0),
        CoinEntry("pepe_idr",      "PEPEUSDT",     CorrelationGroup.MEME_COIN, CoinTier.TIER_A, 3_000_000_000L, true,  2.5, 3.5),
        CoinEntry("shib_idr",      "SHIBUSDT",     CorrelationGroup.MEME_COIN, CoinTier.TIER_A, 3_000_000_000L, true,  2.0, 3.0),
        CoinEntry("bonk_idr",      "BONKUSDT",     CorrelationGroup.MEME_COIN, CoinTier.TIER_A, 2_000_000_000L, true,  3.0, 4.0),
        CoinEntry("jellyjelly_idr","JELLYJELLYUSDT",CorrelationGroup.MEME_COIN, CoinTier.TIER_B, 1_000_000_000L, true,  3.5, 5.0),
        CoinEntry("moodeng_idr",   "MOODENGUSDT",  CorrelationGroup.MEME_COIN, CoinTier.TIER_B, 300_000_000L,   true,  3.5, 5.0),

        // ═══ TIER B — MEDIUM LIQUIDITY (1–10 Bn IDR) ═══
        CoinEntry("bnb_idr",  "BNBUSDT",   CorrelationGroup.ETH_FAMILY,  CoinTier.TIER_B, 1_000_000_000L, true,  1.5, 2.5),
        CoinEntry("ada_idr",  "ADAUSDT",   CorrelationGroup.BTC_FAMILY,  CoinTier.TIER_B, 1_000_000_000L, true,  1.5, 2.5),
        CoinEntry("hype_idr", "HYPEUSDT",  CorrelationGroup.DEFI_TOKEN,  CoinTier.TIER_B, 1_000_000_000L, true,  2.0, 3.0),
        CoinEntry("sui_idr",  "SUIUSDT",   CorrelationGroup.DEFI_TOKEN,  CoinTier.TIER_B, 500_000_000L,   true,  2.0, 3.0),
        CoinEntry("link_idr", "LINKUSDT",  CorrelationGroup.AI_TOKEN,    CoinTier.TIER_B, 300_000_000L,   true,  1.5, 2.5),
        CoinEntry("sto_idr",  "STONEUSDT", CorrelationGroup.DEFI_TOKEN,  CoinTier.TIER_B, 1_000_000_000L, true,  2.5, 3.5),
        CoinEntry("render_idr","RENDERUSDT",CorrelationGroup.AI_TOKEN,   CoinTier.TIER_B, 300_000_000L,   true,  2.0, 3.0),
        CoinEntry("hbar_idr", "HBARUSDT",  CorrelationGroup.AI_TOKEN,    CoinTier.TIER_B, 300_000_000L,   true,  2.0, 3.0),
        CoinEntry("pol_idr",  "POLUSDT",   CorrelationGroup.ETH_FAMILY,  CoinTier.TIER_B, 400_000_000L,   true,  2.0, 3.0),
        CoinEntry("pippin_idr",null,       CorrelationGroup.MICRO_CAP,   CoinTier.TIER_B, 5_000_000_000L, false, 4.0, 6.0, "Indodax-only meme"),

        // ═══ TIER B MICRO — pump chase strategy ═══
        CoinEntry("br_idr",       null,          CorrelationGroup.MICRO_CAP, CoinTier.TIER_B, 1_000_000_000L, false, 4.0, 6.0),
        CoinEntry("trollsol_idr", "TROLLUSDT",   CorrelationGroup.SOL_FAMILY,CoinTier.TIER_B, 1_000_000_000L, true,  4.0, 6.0),
        CoinEntry("koma_idr",     null,          CorrelationGroup.MICRO_CAP, CoinTier.TIER_B, 1_000_000_000L, false, 4.0, 7.0),
        CoinEntry("ava_idr",      null,          CorrelationGroup.MICRO_CAP, CoinTier.TIER_B, 1_000_000_000L, false, 4.0, 7.0),
        CoinEntry("useless_idr",  null,          CorrelationGroup.MICRO_CAP, CoinTier.TIER_B, 500_000_000L,   false, 4.0, 7.0),
        CoinEntry("d_idr",        null,          CorrelationGroup.MICRO_CAP, CoinTier.TIER_B, 500_000_000L,   false, 4.0, 7.0),
        CoinEntry("cast_idr",     "CASTUSDT",    CorrelationGroup.DEFI_TOKEN,CoinTier.TIER_B, 500_000_000L,   true,  3.0, 5.0),
        CoinEntry("pengu_idr",    "PENGUUSDT",   CorrelationGroup.MEME_COIN, CoinTier.TIER_B, 1_000_000_000L, true,  3.5, 5.0),
        CoinEntry("whitewhale_idr",null,         CorrelationGroup.MICRO_CAP, CoinTier.TIER_B, 500_000_000L,   false, 4.0, 7.0),
        CoinEntry("zerebro_idr",  "ZEREBRUSDT",  CorrelationGroup.AI_TOKEN,  CoinTier.TIER_B, 500_000_000L,   true,  3.5, 5.0),
        CoinEntry("fun_idr",      "FUNUSDT",     CorrelationGroup.GAMING,    CoinTier.TIER_C, 1_000_000_000L, true,  3.0, 5.0),
        CoinEntry("drx_idr",      null,          CorrelationGroup.MICRO_CAP, CoinTier.TIER_C, 1_000_000_000L, false, 4.0, 7.0),
        CoinEntry("bsv_idr",      null,          CorrelationGroup.BTC_FAMILY,CoinTier.TIER_B, 500_000_000L,   false, 3.0, 5.0),

        // ═══ TIER C — LOW VOLUME / SPECULATIVE ═══
        CoinEntry("xr_idr",    null, CorrelationGroup.MICRO_CAP, CoinTier.TIER_C, 300_000_000L, false, 5.0, 8.0),
        CoinEntry("beta_idr",  null, CorrelationGroup.DEFI_TOKEN,CoinTier.TIER_C, 300_000_000L, false, 4.0, 7.0),
        CoinEntry("ever_idr",  null, CorrelationGroup.MICRO_CAP, CoinTier.TIER_C, 300_000_000L, false, 5.0, 8.0),

        // ═══ TIDAK DI-TRADE — referensi saja ═══
        CoinEntry("usdt_idr",  "USDTUSDT", CorrelationGroup.STABLECOIN, CoinTier.TIER_A, Long.MAX_VALUE, false),
        CoinEntry("usdc_idr",  "USDCUSDT", CorrelationGroup.STABLECOIN, CoinTier.TIER_A, Long.MAX_VALUE, false)
    )

    // Lookup maps
    val byIndodax: Map<String, CoinEntry> = ALL.associateBy { it.indodaxSymbol }
    val byBinance: Map<String, CoinEntry> = ALL.filter { it.binanceSymbol != null }
        .associateBy { it.binanceSymbol!! }
    val leadLagPairs: List<CoinEntry> = ALL.filter { it.isLeadLagEnabled }
    val tradeable: List<CoinEntry> = ALL.filter {
        it.tier != CoinTier.TIER_C && it.correlationGroup != CorrelationGroup.STABLECOIN
    }

    /** Cari entry dari sinyal Binance ke pair Indodax */
    fun binanceToIndodax(binanceSymbol: String): CoinEntry? = byBinance[binanceSymbol]

    /** Cari entry dari pair Indodax ke Binance symbol */
    fun indodaxToBinance(indodaxSymbol: String): String? =
        byIndodax[indodaxSymbol]?.binanceSymbol

    /** Filter pair yang memenuhi minimum volume */
    fun getActiveUniverse(minVolumeIdr: Long = 1_000_000_000L): List<CoinEntry> =
        tradeable.filter { it.minVolumeIdr <= minVolumeIdr }
}
