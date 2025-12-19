#!/bin/bash
# Connect to phone via wireless ADB
# Run this after initial setup_wireless_adb.sh

cd "$(dirname "$0")"

# Try to get IP from saved config or prompt
CONFIG_FILE="./.wireless_adb_ip"

if [ -f "$CONFIG_FILE" ]; then
    IP=$(cat "$CONFIG_FILE")
    echo "Using saved IP: $IP"
else
    echo "Enter your phone's IP address:"
    read IP
    echo "$IP" > "$CONFIG_FILE"
fi

PORT=5555

echo "Connecting to $IP:$PORT..."
./android-sdk/platform-tools/adb connect $IP:$PORT

if ./android-sdk/platform-tools/adb devices | grep -q "$IP"; then
    echo "✅ Connected! You can now use adb commands wirelessly."
    echo ""
    echo "To view logs:"
    echo "  ./android-sdk/platform-tools/adb logcat -s BenchmarkService:E BenchmarkService:I"
else
    echo "⚠️  Connection failed. Make sure:"
    echo "   1. Phone and computer are on same WiFi network"
    echo "   2. You ran setup_wireless_adb.sh first (with USB connected)"
    echo "   3. Phone's IP address is correct"
fi

