#!/bin/bash
# Background service that automatically pulls benchmark results
# Run this once and it will continuously monitor for new files

cd "$(dirname "$0")"

RESULTS_DIR="./results"
mkdir -p "$RESULTS_DIR"

# File to track last pull time
LAST_PULL_FILE="$RESULTS_DIR/.last_pull"

echo "🚀 Starting automatic results puller..."
echo "   This will check for new files every 30 seconds"
echo "   Press Ctrl+C to stop"
echo ""

# Initialize last pull time
if [ ! -f "$LAST_PULL_FILE" ]; then
    echo "0" > "$LAST_PULL_FILE"
fi

LAST_PULL=$(cat "$LAST_PULL_FILE")

while true; do
    # Check if phone is connected (wireless or USB)
    if ./android-sdk/platform-tools/adb devices | grep -q "device$"; then
        # Get list of CSV files on phone
        PHONE_FILES=$(./android-sdk/platform-tools/adb shell "ls /sdcard/Download/llm_benchmark_*.csv 2>/dev/null" | tr -d '\r')
        
        if [ ! -z "$PHONE_FILES" ]; then
            NEW_FILES_FOUND=false
            
            for file_path in $PHONE_FILES; do
                FILE_NAME=$(basename "$file_path")
                
                # Check if we already have this file
                if [ ! -f "$RESULTS_DIR/$FILE_NAME" ]; then
                    NEW_FILES_FOUND=true
                    echo "[$(date '+%H:%M:%S')] 📥 New file detected: $FILE_NAME"
                    ./android-sdk/platform-tools/adb pull "$file_path" "$RESULTS_DIR/$FILE_NAME" 2>/dev/null
                    
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
    fi
    
    # Wait 30 seconds before checking again
    sleep 30
done

