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
echo "Installing app via USB..."
./android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ App installed successfully!"
else
    echo ""
    echo "❌ Installation failed. Make sure phone is connected via USB."
fi

