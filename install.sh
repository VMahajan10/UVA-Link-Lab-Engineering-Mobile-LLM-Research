#!/bin/bash
# Build and install app via USB

cd "$(dirname "$0")"

echo "Building app..."
./gradlew assembleDebug

if [ $? -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi

echo ""
echo "Finding USB device..."
# Get USB device (not the wireless one)
USB_DEVICE=$(./android-sdk/platform-tools/adb devices | grep "device$" | grep -v ":" | awk '{print $1}' | head -1)

if [ -z "$USB_DEVICE" ]; then
    echo "❌ No USB device found. Make sure phone is connected via USB cable."
    exit 1
fi

echo "Installing to USB device: $USB_DEVICE"
./android-sdk/platform-tools/adb -s "$USB_DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ App installed successfully!"
else
    echo ""
    echo "❌ Installation failed."
fi

