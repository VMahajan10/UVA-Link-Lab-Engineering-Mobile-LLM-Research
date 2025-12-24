#!/bin/bash
# Pull the latest benchmark CSV file from device

cd "$(dirname "$0")"

RESULTS_DIR="./results"
mkdir -p "$RESULTS_DIR"

echo "Checking for connected devices..."
DEVICES=$(./android-sdk/platform-tools/adb devices | grep "device$" | awk '{print $1}')

if [ -z "$DEVICES" ]; then
    echo "❌ No device connected!"
    echo ""
    echo "Please connect your device via USB or run:"
    echo "  ./connect_wireless.sh"
    exit 1
fi

# Use first available device
DEVICE=$(echo "$DEVICES" | head -1)
echo "Using device: $DEVICE"
echo ""

echo "Finding latest CSV file on device..."
LATEST_FILE=$(./android-sdk/platform-tools/adb -s "$DEVICE" shell "ls -t /sdcard/Download/llm_benchmark_*.csv 2>/dev/null" | head -1 | tr -d '\r')

if [ -z "$LATEST_FILE" ]; then
    echo "⚠️  No CSV files found on device."
    echo "   Make sure you've run a benchmark and it has completed."
    exit 1
fi

echo "Latest file: $LATEST_FILE"
echo ""

FILE_NAME=$(basename "$LATEST_FILE")
echo "Pulling: $FILE_NAME"
./android-sdk/platform-tools/adb -s "$DEVICE" pull "$LATEST_FILE" "$RESULTS_DIR/$FILE_NAME"

if [ -f "$RESULTS_DIR/$FILE_NAME" ]; then
    echo ""
    echo "✅ Successfully pulled to:"
    echo "   $(pwd)/$RESULTS_DIR/$FILE_NAME"
    echo ""
    echo "Opening results folder in Finder..."
    open "$RESULTS_DIR"
else
    echo "❌ Failed to pull file."
    exit 1
fi

