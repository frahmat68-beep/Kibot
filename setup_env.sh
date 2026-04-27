#!/bin/bash
# KiBot Environment Setup
export PYTHONPATH=$PYTHONPATH:$(pwd)/core:$(pwd)/scanners:$(pwd)/tools
export KIBOT_RUNTIME_ROOT=$(pwd)
echo "✅ PYTHONPATH updated: $PYTHONPATH"
