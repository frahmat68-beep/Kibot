#!/usr/bin/env python3
"""
KiBot Trinity - State File Validator & Recovery
================================================
Ensures state.json consistency with backup/recovery capabilities.

Features:
- JSON schema validation
- Automatic backup rotation (keep 3 most recent)
- Corruption detection and auto-recovery
- Fallback to hardcoded defaults
- Atomic write operations for safety
"""

import json
import logging
from pathlib import Path
from typing import Dict, Any, Optional, List
from datetime import datetime
import hashlib
import shutil

logger = logging.getLogger(__name__)


# Default state structure (used as fallback)
DEFAULT_STATE = {
    # Trading control flags
    "trading_paused": False,
    "emergency_mode": False,
    "halted": False,
    "emergency_standby_mode": False,
    
    # Network resilience
    "api_failure_count": 0,
    "backoff_delay_seconds": 0,
    "last_api_failure_timestamp": None,
    
    # Position tracking
    "managed_positions": [],
    "open_orders": [],
    
    # Capital tracking
    "total_capital_idr": 0.0,
    "free_capital_idr": 0.0,
    "deployed_capital_idr": 0.0,
    
    # Performance metrics
    "daily_pnl_idr": 0.0,
    "total_return_pct": 0.0,
    "win_rate_pct": 0.0,
    "total_trades": 0,
    "winning_trades": 0,
    
    # Metadata
    "last_updated": None,
    "version": "1.0",
}

# Required fields that MUST exist
REQUIRED_FIELDS = {
    "trading_paused",
    "emergency_mode",
    "halted",
    "emergency_standby_mode",
    "api_failure_count",
}

# Type hints for validation
FIELD_TYPES = {
    "trading_paused": bool,
    "emergency_mode": bool,
    "halted": bool,
    "emergency_standby_mode": bool,
    "api_failure_count": int,
    "backoff_delay_seconds": (int, float),
    "total_capital_idr": (int, float),
    "free_capital_idr": (int, float),
    "deployed_capital_idr": (int, float),
    "daily_pnl_idr": (int, float),
    "total_return_pct": (int, float),
    "win_rate_pct": (int, float),
    "total_trades": int,
    "winning_trades": int,
}


class StateValidator:
    """Validates and recovers state files"""
    
    def __init__(
        self,
        state_file: Path = Path("state/state.json"),
        backup_dir: Path = Path("state/backups"),
        max_backups: int = 3,
    ):
        """
        Initialize state validator.
        
        Args:
            state_file: Path to main state file
            backup_dir: Directory for backups
            max_backups: Maximum number of backups to keep
        """
        self.state_file = state_file
        self.backup_dir = backup_dir
        self.max_backups = max_backups
        
        # Ensure directories exist
        self.state_file.parent.mkdir(parents=True, exist_ok=True)
        self.backup_dir.mkdir(parents=True, exist_ok=True)
    
    def load_state(self) -> Dict[str, Any]:
        """
        Load and validate state file with automatic recovery.
        
        Returns:
            Valid state dict (either loaded, recovered, or defaults)
        """
        # Try loading main state file
        if self.state_file.exists():
            try:
                state = self._load_json_safe(self.state_file)
                if self._validate_state(state):
                    logger.info("✅ State file valid")
                    return state
                else:
                    logger.warning("⚠️ State file invalid schema, attempting recovery...")
            except (json.JSONDecodeError, IOError) as e:
                logger.warning(f"⚠️ State file corrupted: {e}, attempting recovery...")
        
        # Try recovering from backup
        recovered = self._recover_from_backup()
        if recovered is not None:
            logger.info("✅ Recovered from backup")
            return recovered
        
        # Fallback to defaults
        logger.warning("⚠️ No valid backups, using defaults")
        return dict(DEFAULT_STATE)
    
    def save_state(self, state: Dict[str, Any]) -> bool:
        """
        Save state with automatic backup and atomic write.
        
        Args:
            state: State dictionary to save
        
        Returns:
            True if saved successfully, False otherwise
        """
        # Validate before save
        if not self._validate_state(state):
            logger.error("❌ State validation failed, refusing to save")
            return False
        
        try:
            # Create backup of existing state
            if self.state_file.exists():
                self._create_backup()
            
            # Atomic write: write to temp file, then rename
            temp_file = self.state_file.with_suffix('.tmp')
            with open(temp_file, 'w') as f:
                json.dump(state, f, indent=2)
            
            # Atomic rename
            temp_file.replace(self.state_file)
            logger.info("✅ State saved successfully")
            return True
        
        except Exception as e:
            logger.error(f"❌ Failed to save state: {e}")
            return False
    
    def _load_json_safe(self, path: Path) -> Dict[str, Any]:
        """Load JSON with proper error handling"""
        with open(path, 'r') as f:
            return json.load(f)
    
    def _validate_state(self, state: Dict[str, Any]) -> bool:
        """
        Validate state structure and types.
        
        Returns:
            True if state is valid, False otherwise
        """
        # Check required fields exist
        for field in REQUIRED_FIELDS:
            if field not in state:
                logger.warning(f"  Missing required field: {field}")
                return False
        
        # Validate field types
        for field, expected_type in FIELD_TYPES.items():
            if field not in state:
                continue
            
            value = state[field]
            if not isinstance(value, expected_type):
                logger.warning(f"  Type mismatch for {field}: expected {expected_type}, got {type(value)}")
                return False
        
        return True
    
    def _create_backup(self) -> Path:
        """Create timestamped backup of current state file"""
        timestamp = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
        backup_file = self.backup_dir / f"state_{timestamp}.json"
        
        shutil.copy2(self.state_file, backup_file)
        logger.info(f"✅ Backup created: {backup_file.name}")
        
        # Clean old backups
        self._cleanup_old_backups()
        
        return backup_file
    
    def _cleanup_old_backups(self) -> None:
        """Keep only max_backups most recent backups"""
        backups = sorted(
            self.backup_dir.glob("state_*.json"),
            key=lambda p: p.stat().st_mtime,
            reverse=True  # newest first
        )
        
        # Delete old backups
        for backup in backups[self.max_backups:]:
            try:
                backup.unlink()
                logger.info(f"Deleted old backup: {backup.name}")
            except Exception as e:
                logger.warning(f"Failed to delete backup {backup.name}: {e}")
    
    def _recover_from_backup(self) -> Optional[Dict[str, Any]]:
        """
        Attempt to recover state from most recent backup.
        
        Returns:
            Valid state dict or None if recovery failed
        """
        backups = sorted(
            self.backup_dir.glob("state_*.json"),
            key=lambda p: p.stat().st_mtime,
            reverse=True  # newest first
        )
        
        for backup_file in backups:
            try:
                logger.info(f"Trying to recover from: {backup_file.name}")
                state = self._load_json_safe(backup_file)
                if self._validate_state(state):
                    # Restore this backup as main state
                    self._restore_backup(backup_file)
                    return state
            except Exception as e:
                logger.warning(f"Failed to recover from {backup_file.name}: {e}")
        
        return None
    
    def _restore_backup(self, backup_file: Path) -> None:
        """Restore backup as main state file"""
        try:
            shutil.copy2(backup_file, self.state_file)
            logger.info(f"✅ Restored from backup: {backup_file.name}")
        except Exception as e:
            logger.error(f"Failed to restore backup: {e}")
    
    def get_checksum(self, state: Dict[str, Any]) -> str:
        """Calculate SHA256 checksum of state"""
        state_json = json.dumps(state, sort_keys=True)
        return hashlib.sha256(state_json.encode()).hexdigest()[:8]
    
    def auto_fix(self, state: Dict[str, Any]) -> Dict[str, Any]:
        """
        Attempt to fix common state issues.
        
        Returns:
            Fixed state dictionary
        """
        fixed = dict(state)
        
        # Add missing required fields
        for field in REQUIRED_FIELDS:
            if field not in fixed:
                fixed[field] = DEFAULT_STATE.get(field, False)
                logger.info(f"Added missing field: {field}")
        
        # Fix type mismatches
        for field, expected_type in FIELD_TYPES.items():
            if field not in fixed:
                continue
            
            value = fixed[field]
            if not isinstance(value, expected_type):
                try:
                    if expected_type == bool:
                        fixed[field] = bool(value)
                    elif expected_type == int:
                        fixed[field] = int(value)
                    elif expected_type == float:
                        fixed[field] = float(value)
                    logger.info(f"Fixed type for field: {field}")
                except:
                    fixed[field] = DEFAULT_STATE.get(field, None)
                    logger.warning(f"Couldn't fix {field}, using default")
        
        # Ensure timestamps are strings or None
        if fixed.get("last_updated") and not isinstance(fixed["last_updated"], (str, type(None))):
            fixed["last_updated"] = None
        
        # Ensure counts are non-negative
        for field in ["api_failure_count", "total_trades", "winning_trades"]:
            if field in fixed and fixed[field] < 0:
                fixed[field] = 0
                logger.info(f"Fixed negative value for {field}")
        
        return fixed


# Convenience functions
def validate_state_file(state_file: Path = Path("state/state.json")) -> bool:
    """Quick validation check"""
    validator = StateValidator(state_file)
    return validator._validate_state(validator.load_state())


def recover_state_file(state_file: Path = Path("state/state.json")) -> Dict[str, Any]:
    """Load with automatic recovery"""
    validator = StateValidator(state_file)
    return validator.load_state()


def safe_save_state(state: Dict[str, Any], state_file: Path = Path("state/state.json")) -> bool:
    """Save with validation"""
    validator = StateValidator(state_file)
    return validator.save_state(state)


if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )
    
    # Test state validation
    validator = StateValidator()
    
    print("Testing state validation:")
    print("=" * 60)
    
    # Load state
    state = validator.load_state()
    print(f"✅ Loaded state: {len(state)} fields")
    
    # Modify and save
    state["daily_pnl_idr"] = 1500.0
    state["total_trades"] = 12
    validator.save_state(state)
    
    # Verify
    reloaded = validator.load_state()
    print(f"✅ Verified: {reloaded['daily_pnl_idr']} IDR PnL")
    print(f"✅ Checksum: {validator.get_checksum(reloaded)}")
