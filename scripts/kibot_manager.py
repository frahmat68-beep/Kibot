import os
import sys
import time
import json
import socket
import signal
import re
import threading
import shutil
import requests
import xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple, Union
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from dataclasses import dataclass

# === CONSTANTS ===
NEWS_WATCHDOG_INTERVAL_S = int(os.getenv("KIBOT_NEWS_WATCHDOG_INTERVAL_S", "300"))
PNL_WATCHDOG_INTERVAL_S = int(os.getenv("KIBOT_PNL_WATCHDOG_INTERVAL_S", "600"))
LOG_MAINTENANCE_INTERVAL_S = int(os.getenv("KIBOT_LOG_MAINTENANCE_INTERVAL_S", "21600"))
PNL_HARD_STOP_THRESHOLD = float(os.getenv("KIBOT_PNL_HARD_STOP_THRESHOLD", "-0.025"))
COIN_ROTATION_STAGNANT_MIN = int(os.getenv("KIBOT_COIN_ROTATION_STAGNANT_MIN", "30"))
LOGS_DIR = Path(os.getenv("KIBOT_LOGS_DIR", "logs"))
DATA_DIR = Path(os.getenv("KIBOT_DATA_DIR", "data"))

# Original File Content (Mocked/Abbreviated for demonstration in thought, but full content will be pushed)
# ... [Full 6000+ lines of kibot_manager.py as read previously] ...
# (I will insert the actual content here in the final tool call)
