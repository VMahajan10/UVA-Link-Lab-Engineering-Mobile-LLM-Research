#!/bin/bash
# Background service that automatically pulls benchmark results
# Run this once and it will continuously monitor for new files
# Works with wireless ADB - no USB connection needed during benchmark

cd "$(dirname "$0")"

RESULTS_DIR="./results"
mkdir -p "$RESULTS_DIR"

# File to track last pull time
LAST_PULL_FILE="$RESULTS_DIR/.last_pull"

# Wireless ADB IP (if configured)
WIRELESS_IP_FILE=".wireless_adb_ip"
WIRELESS_IP=""
if [ -f "$WIRELESS_IP_FILE" ]; then
    WIRELESS_IP=$(cat "$WIRELESS_IP_FILE" | tr -d '\r\n')
fi

echo "🚀 Starting automatic results puller..."
echo "   This will check for new files every 30 seconds"
if [ ! -z "$WIRELESS_IP" ]; then
    echo "   Wireless ADB IP: $WIRELESS_IP"
fi
echo "   Press Ctrl+C to stop"
echo ""

# Function to ensure device is connected
ensure_connected() {
    # Try wireless first if IP is configured
    if [ ! -z "$WIRELESS_IP" ]; then
        if ! ./android-sdk/platform-tools/adb devices | grep -q "$WIRELESS_IP"; then
            ./android-sdk/platform-tools/adb connect "$WIRELESS_IP:5555" > /dev/null 2>&1
            sleep 1
        fi
    fi
    
    # Check if any device is connected
    ./android-sdk/platform-tools/adb devices | grep -q "device$"
}

# Function to get device ID
get_device() {
    if [ ! -z "$WIRELESS_IP" ]; then
        # Prefer wireless device
        DEVICE=$(./android-sdk/platform-tools/adb devices | grep "$WIRELESS_IP" | grep "device$" | awk '{print $1}')
        if [ ! -z "$DEVICE" ]; then
            echo "$DEVICE"
            return
        fi
    fi
    # Fallback to any connected device
    ./android-sdk/platform-tools/adb devices | grep "device$" | awk '{print $1}' | head -1
}

# Initialize last pull time
if [ ! -f "$LAST_PULL_FILE" ]; then
    echo "0" > "$LAST_PULL_FILE"
fi

LAST_PULL=$(cat "$LAST_PULL_FILE")

while true; do
    # Ensure device is connected
    if ensure_connected; then
        DEVICE=$(get_device)
        if [ -z "$DEVICE" ]; then
            echo "[$(date '+%H:%M:%S')] ⚠️  No device available (will retry in 30s)"
            sleep 30
            continue
        fi
        
        # Get list of CSV files on phone
        PHONE_FILES=$(./android-sdk/platform-tools/adb -s "$DEVICE" shell "ls -t /sdcard/Download/llm_benchmark_*.csv 2>/dev/null" | tr -d '\r')
        
        if [ ! -z "$PHONE_FILES" ]; then
            NEW_FILES_FOUND=false
            
            for file_path in $PHONE_FILES; do
                FILE_NAME=$(basename "$file_path")
                
                # Check if we already have this file
                if [ ! -f "$RESULTS_DIR/$FILE_NAME" ]; then
                    NEW_FILES_FOUND=true
                    echo "[$(date '+%H:%M:%S')] 📥 New file detected: $FILE_NAME"
                    ./android-sdk/platform-tools/adb -s "$DEVICE" pull "$file_path" "$RESULTS_DIR/$FILE_NAME" 2>/dev/null
                    
                    if [ -f "$RESULTS_DIR/$FILE_NAME" ]; then
                        echo "   ✅ Pulled successfully"
                    fi
                fi
            done
            
            # If new files were found, create merged files and open Finder
            if [ "$NEW_FILES_FOUND" = true ]; then
                echo "[$(date '+%H:%M:%S')] 🔄 Creating merged CSV files..."
                python3 merge_results.py --all > /dev/null 2>&1
                echo "[$(date '+%H:%M:%S')] ✅ Results ready in Finder"
                open "$RESULTS_DIR"
            fi
            
            # Update last pull time
            echo "$(date +%s)" > "$LAST_PULL_FILE"
        fi
    else
        echo "[$(date '+%H:%M:%S')] ⚠️  Phone not connected (will retry in 30s)"
        # Try to reconnect if wireless IP is configured
        if [ ! -z "$WIRELESS_IP" ]; then
            ./android-sdk/platform-tools/adb connect "$WIRELESS_IP:5555" > /dev/null 2>&1
        fi
    fi
    
    # Wait 30 seconds before checking again
    sleep 30
done

