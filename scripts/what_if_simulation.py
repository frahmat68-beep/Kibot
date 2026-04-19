
import time
import os
import json
from datetime import datetime, timezone

# Mock State
_daily_guard_state = {
    "hard_stopped": False,
    "entry_loss_count": {}
}
_ai_healthy = True
_gate_state = {"mode": "NORMAL"}
_last_entry = {}
_entry_loss_count = {}

def _can_enter(pair: str, mtype: str = "SIGNAL") -> tuple[bool, str]:
    global _ai_healthy, _gate_state, _last_entry, _entry_loss_count
    
    # 1. Hard Stop Check
    if bool(_daily_guard_state.get("hard_stopped")):
        return False, "hard_stop_active"

    # 2. AI Health Guard
    if not _ai_healthy:
        if _gate_state.get("mode") == "LEVEL_3":
            return False, "ai_offline_level_3_freeze"
        return False, "ai_offline"

    # 3. Quarantine Logic (Bug #3) - Avoid Averaging Down
    pair = pair.lower().strip()
    if pair:
        # Cooldown Check
        last_t = _last_entry.get(pair, 0.0)
        cooldown_min = 30
        if (time.time() - last_t) < (cooldown_min * 60):
            return False, "quarantine_cooldown"
        
        # Loss Blacklist Check
        loss_cnt = _entry_loss_count.get(pair, 0)
        max_loss = 2
        if loss_cnt >= max_loss:
            return False, "quarantine_loss_blacklist"

    return True, "ok"

def run_simulation():
    global _ai_healthy, _gate_state, _last_entry, _entry_loss_count, _daily_guard_state
    
    print("--- STARTING WHAT-IF SIMULATION ---")
    
    # Scenario 1: Fresh start
    res, reason = _can_enter("sol_idr")
    print(f"Scenario 1 (Fresh): {res} ({reason})")
    
    # Scenario 2: Just entered (Quarantine)
    _last_entry["sol_idr"] = time.time()
    res, reason = _can_enter("sol_idr")
    print(f"Scenario 2 (Quarantine): {res} ({reason})")
    
    # Scenario 3: AI Offline
    _ai_healthy = False
    res, reason = _can_enter("eth_idr")
    print(f"Scenario 3 (AI Offline): {res} ({reason})")
    
    # Scenario 4: AI Offline + LEVEL_3
    _gate_state["mode"] = "LEVEL_3"
    res, reason = _can_enter("eth_idr")
    print(f"Scenario 4 (FULL FREEZE): {res} ({reason})")
    
    # Scenario 5: Recovery + Blacklist
    _ai_healthy = True
    _entry_loss_count["btc_idr"] = 2
    res, reason = _can_enter("btc_idr")
    print(f"Scenario 5 (Blacklisted): {res} ({reason})")
    
    # Scenario 6: Hard Stop
    _daily_guard_state["hard_stopped"] = True
    res, reason = _can_enter("link_idr")
    print(f"Scenario 6 (Hard Stop): {res} ({reason})")

if __name__ == "__main__":
    run_simulation()
