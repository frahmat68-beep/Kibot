#!/bin/bash
echo "Building KiBot core package..."
./gradlew :packages:core:build -x test --no-daemon 2>&1 | tee build-output.txt | tail -100
echo ""
echo "Build Status: $?"
grep -E "(BUILD|FAILED|ERROR)" build-output.txt | tail -5
