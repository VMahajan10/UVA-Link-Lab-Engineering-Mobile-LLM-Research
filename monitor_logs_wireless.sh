#!/bin/bash
# Monitor logs wirelessly (after wireless ADB is set up)

cd "$(dirname "$0")"

# Connect wirelessly first
./connect_wireless.sh

echo ""
echo "Starting logcat monitoring..."
echo "Press Ctrl+C to stop"
echo ""

./android-sdk/platform-tools/adb logcat -s BenchmarkService:E BenchmarkService:I DataLogger:I

