#!/bin/bash

set -euo pipefail

# Light keepalive for Oracle free tier.
# Keep CPU usage low while avoiding idle suspension.
CPU_LOAD="${CPU_LOAD:-5}"
CPU_WORKERS="${CPU_WORKERS:-1}"
SLEEP_SEC="${SLEEP_SEC:-10}"
RUN_SEC="${RUN_SEC:-5}"

while true; do
  /usr/bin/stress-ng --cpu "$CPU_WORKERS" --cpu-load "$CPU_LOAD" --timeout "${RUN_SEC}s"
  sleep "$SLEEP_SEC"
done
