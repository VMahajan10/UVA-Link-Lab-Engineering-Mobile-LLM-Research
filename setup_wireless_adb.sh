#!/bin/bash
# Setup wireless ADB connection
# Note: Phone must be connected via USB initially to set this up

cd "$(dirname "$0")"

echo "Setting up wireless ADB..."
echo ""
echo "Step 1: Make sure your phone is connected via USB"
echo "Press Enter when ready..."
read

# Get device IP address
echo "Getting device IP address..."
IP=$(./android-sdk/platform-tools/adb shell "ip addr show wlan0 | grep 'inet ' | awk '{print \$2}' | cut -d/ -f1" | tr -d '\r')

if [ -z "$IP" ]; then
    echo "⚠️  Could not get IP address. Make sure WiFi is enabled on phone."
    exit 1
fi

echo "Device IP: $IP"
echo ""

# Get port (default is 5555)
PORT=5555

echo "Step 2: Enabling ADB over TCP/IP on device..."
./android-sdk/platform-tools/adb tcpip $PORT

echo ""
echo "Step 3: Connecting to device over WiFi..."
./android-sdk/platform-tools/adb connect $IP:$PORT

echo ""
echo "✅ Wireless ADB setup complete!"
echo ""
echo "You can now disconnect USB and use:"
echo "  ./android-sdk/platform-tools/adb connect $IP:$PORT"
echo ""
echo "To disconnect later:"
echo "  ./android-sdk/platform-tools/adb disconnect $IP:$PORT"

