import os
import sys

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
    
    snapshot = brain.think(["BTC", "ETH"])
    print(f"Brain snapshot keys: {list(snapshot.keys())}")
    print("✅ Brain advisory loop works.")

if __name__ == "__main__":
    test_stats()
    test_brain()
