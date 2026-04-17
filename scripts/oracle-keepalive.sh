#!/bin/bash

set -euo pipefail

# Light keepalive for Oracle free tier.
# Keep CPU usage low while avoiding idle suspension.
CPU_LOAD="${CPU_LOAD:-2}"    # Reduced from 5 to 2
CPU_WORKERS="${CPU_WORKERS:-1}"
SLEEP_SEC="${SLEEP_SEC:-20}"  # Increased from 10 to 20
RUN_SEC="${RUN_SEC:-3}"      # Reduced from 5 to 3

while true; do
  /usr/bin/stress-ng --cpu "$CPU_WORKERS" --cpu-load "$CPU_LOAD" --timeout "${RUN_SEC}s"
  sleep "$SLEEP_SEC"
done
