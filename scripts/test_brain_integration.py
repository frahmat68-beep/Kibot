import os
import sys
from unittest.mock import patch

# Add scripts to path
sys.path.append(os.path.join(os.getcwd(), 'scripts'))

def test_stats():
    from ki_stats import calculate_z_score
    prices = [100, 102, 101, 105, 110, 108, 115, 120, 118, 125, 
              130, 135, 132, 140, 145, 150, 155, 160, 165, 250] # Gigantic spike
    z = calculate_z_score(prices)
    print(f"Test Z-Score (Last Price 250): {z:.2f}")
    if z > 2.0:
        print("✅ Z-Score detection works.")
    else:
        print("❌ Z-Score detection failed.")

def test_brain():
    from ki_brain import BrainManager
    brain = BrainManager()

    def fake_get_json(url, params=None):
        if "binance.com" in url:
            return {"quoteVolume": "1234567.89"}
        if "indodax.com/api/pairs" in url:
            return [{"traded_currency": "btc", "base_currency": "idr", "ticker_id": "btc_idr"}]
        if "coingecko.com" in url:
            return {"coins": [{"id": "bitcoin"}]}
        return {}

    with (
        patch.object(brain, "_get_json", side_effect=fake_get_json),
        patch.object(brain, "_status_code", return_value=200),
        patch.object(brain, "_get_finnhub_crypto_news", return_value=[
            {"headline": "Bitcoin rally gains strength", "summary": "BTC breakout extends", "related": "BTC,ETH"},
            {"headline": "Altcoins recover after selloff", "summary": "market stabilizes", "related": "BTC,SOL"},
        ]),
        patch.object(brain, "_get_tavily_market_brief", return_value={"answer": "Market constructive with selective risk appetite.", "results": []}),
        patch.object(brain, "_get_tavily_symbol_brief", return_value={"answer": "BTC remains liquid with manageable event risk.", "results": []}),
        patch.object(brain, "_get_serper_market_brief", return_value={}),
        patch.object(brain, "_get_serper_symbol_brief", return_value={}),
    ):
        print("\n--- Testing Brain Market Intel ---")
        symbol = "BTC"
        intel = brain.get_market_intel(symbol)

        if intel.get('binance'):
            print("✅ Binance intel successful.")
        if intel.get('indodax_pairs'):
            print("✅ Indodax pair intel successful.")
        if intel.get('coingecko_search'):
            print("✅ CoinGecko intel successful.")

        print("\n--- Testing Mindset Vetting ---")
        approved, reason = brain.vet_signal("BTC", 0.9)
        print(f"Mindset Approval: {approved}")
        print(f"Reason: {reason}")

        snapshot = brain.think(
            ["BTC", "ETH"],
            context={
                "daily_pnl_pct": -0.0045,
                "equity_idr": 125000,
                "free_cash_idr": 62000,
                "capital_profile": {
                    "mode": "BUILDUP",
                    "reason": "small_balance_build_up",
                    "max_position_idr": 12000,
                    "risk_pct_per_trade": 0.15,
                },
            },
        )
        print(f"Brain snapshot keys: {list(snapshot.keys())}")
        print(f"Provider status: {snapshot.get('provider_status')}")
        print(f"Daily target: {snapshot.get('daily_target')}")
        print(f"Market pulse: {snapshot.get('market_pulse')}")
        print(f"Strategy next: {snapshot.get('daily_target', {}).get('strategy_next')}")
        print("✅ Brain advisory loop works.")

if __name__ == "__main__":
    test_stats()
    test_brain()
