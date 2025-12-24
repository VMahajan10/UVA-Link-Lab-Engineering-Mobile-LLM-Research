#!/bin/bash
# Setup automatic CSV file pulling after benchmarks complete
# This allows you to run benchmarks without USB connection

cd "$(dirname "$0")"

echo "🔧 Setting up automatic CSV file pulling..."
echo ""

# Step 1: Check if wireless ADB is configured
WIRELESS_IP_FILE=".wireless_adb_ip"
if [ ! -f "$WIRELESS_IP_FILE" ]; then
    echo "📱 Step 1: Setting up wireless ADB connection..."
    echo "   Please connect your phone via USB now..."
    echo ""
    read -p "Press Enter when USB is connected..."
    
    if ! ./android-sdk/platform-tools/adb devices | grep -q "device$"; then
        echo "❌ No device detected via USB!"
        echo "   Please connect your phone and try again."
        exit 1
    fi
    
    echo "   Setting up wireless ADB..."
    ./setup_wireless_adb.sh
    
    if [ ! -f "$WIRELESS_IP_FILE" ]; then
        echo "❌ Failed to set up wireless ADB"
        exit 1
    fi
    
    WIRELESS_IP=$(cat "$WIRELESS_IP_FILE")
    echo "   ✅ Wireless ADB configured: $WIRELESS_IP"
    echo ""
    echo "   You can now disconnect USB!"
    echo ""
else
    WIRELESS_IP=$(cat "$WIRELESS_IP_FILE")
    echo "✅ Wireless ADB already configured: $WIRELESS_IP"
    echo ""
fi

# Step 2: Test wireless connection
echo "📡 Step 2: Testing wireless connection..."
./android-sdk/platform-tools/adb connect "$WIRELESS_IP:5555" > /dev/null 2>&1
sleep 1

if ./android-sdk/platform-tools/adb devices | grep -q "$WIRELESS_IP"; then
    echo "   ✅ Wireless connection successful!"
else
    echo "   ⚠️  Could not connect wirelessly"
    echo "   Make sure your phone and computer are on the same WiFi network"
    echo "   You may need to reconnect USB and run ./setup_wireless_adb.sh again"
    exit 1
fi

echo ""

# Step 3: Install auto-pull service
echo "🚀 Step 3: Installing auto-pull service..."
./install_auto_pull.sh

echo ""
echo "✅ Setup complete!"
echo ""
echo "📋 How it works:"
echo "   1. Start a benchmark on your phone (no USB needed)"
echo "   2. The auto-pull service runs in the background"
echo "   3. When benchmark completes, CSV files automatically appear in results/ folder"
echo "   4. Finder will open automatically when new files arrive"
echo ""
echo "💡 Tips:"
echo "   • Keep your computer and phone on the same WiFi network"
echo "   • The service runs automatically in the background"
echo "   • Check logs: tail -f auto_pull.log"
echo "   • Stop service: ./uninstall_auto_pull.sh"
echo ""

