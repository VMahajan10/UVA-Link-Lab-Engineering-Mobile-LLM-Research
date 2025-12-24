#!/bin/bash
# Show live debug logs for the benchmark

cd "$(dirname "$0")"

DEVICE="192.168.1.174:5555"

echo "=== Benchmark Debug Logs ==="
echo "Device: $DEVICE"
echo "Press Ctrl+C to stop"
echo ""
echo "Clearing logcat buffer..."
./android-sdk/platform-tools/adb -s "$DEVICE" logcat -c

echo "Starting live log monitoring..."
echo ""

./android-sdk/platform-tools/adb -s "$DEVICE" logcat -v time | grep -E "BenchmarkService|DataLogger"

