#!/bin/bash
# Pull benchmark results from phone to local results folder

cd "$(dirname "$0")"

RESULTS_DIR="./results"
mkdir -p "$RESULTS_DIR"

echo "Pulling benchmark results from phone..."
echo ""

# Get list of CSV files from phone
CSV_FILES=$(./android-sdk/platform-tools/adb shell "ls /sdcard/Download/llm_benchmark_*.csv 2>/dev/null" | tr -d '\r')

if [ -z "$CSV_FILES" ]; then
    echo "⚠️  No CSV files found on phone."
    echo "   Make sure you've run a benchmark and it has completed."
    exit 1
fi

# Pull each file
PULLED_COUNT=0
for file in $CSV_FILES; do
    # Remove /sdcard prefix and pull
    local_file=$(basename "$file")
    echo "Pulling: $local_file"
    ./android-sdk/platform-tools/adb -s "$DEVICE" pull "$file" "$RESULTS_DIR/$local_file" 2>/dev/null
    if [ -f "$RESULTS_DIR/$local_file" ]; then
        PULLED_COUNT=$((PULLED_COUNT + 1))
    fi
done

if [ "$PULLED_COUNT" -gt 0 ]; then
    echo ""
    echo "✅ Successfully pulled $PULLED_COUNT CSV file(s) to:"
    echo "   $(pwd)/$RESULTS_DIR"
    echo ""
    echo "Files:"
    ls -lh "$RESULTS_DIR"/*.csv 2>/dev/null | awk '{print "   " $9 " (" $5 ")"}'
    echo ""
    echo "Creating merged CSV files with before/after stats..."
    python3 "$(dirname "$0")/merge_results.py" --all
    
    echo ""
    echo "Opening results folder in Finder..."
    open "$RESULTS_DIR"
else
    echo "⚠️  Failed to pull files."
fi

