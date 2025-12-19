#!/bin/bash
# Monitor BenchmarkService debug logs

echo "Clearing logcat buffer..."
cd "$(dirname "$0")"
./android-sdk/platform-tools/adb logcat -c

echo "Starting logcat monitoring for BenchmarkService..."
echo "Press Ctrl+C to stop"
echo ""
echo "Waiting for benchmark to start..."
echo ""

# Use quotes to prevent zsh glob expansion
./android-sdk/platform-tools/adb logcat -s BenchmarkService:I BenchmarkService:E DataLogger:I

