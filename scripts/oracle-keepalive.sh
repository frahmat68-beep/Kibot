#!/bin/bash

set -euo pipefail

CPU_LOAD="${CPU_LOAD:-15}"
VM_BYTES="${VM_BYTES:-10%}"
CPU_WORKERS="${CPU_WORKERS:-1}"
VM_WORKERS="${VM_WORKERS:-1}"

exec /usr/bin/stress-ng --cpu "$CPU_WORKERS" --cpu-load "$CPU_LOAD" --vm "$VM_WORKERS" --vm-bytes "$VM_BYTES"
