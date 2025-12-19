#!/bin/bash
# Start the automatic results puller in the background

cd "$(dirname "$0")"

# Check if already running
if pgrep -f "auto_pull_results.sh" > /dev/null; then
    echo "⚠️  Auto-pull is already running!"
    echo "   To stop it: ./stop_auto_pull.sh"
    exit 1
fi

echo "Starting automatic results puller in background..."
echo ""

# Start in background and redirect output to log file
nohup ./auto_pull_results.sh > auto_pull.log 2>&1 &

# Save PID
echo $! > .auto_pull_pid

echo "✅ Auto-pull started! (PID: $(cat .auto_pull_pid))"
echo ""
echo "It will:"
echo "  • Check for new files every 30 seconds"
echo "  • Automatically pull them to results/ folder"
echo "  • Create merged CSV files"
echo "  • Open Finder when new files arrive"
echo ""
echo "To stop: ./stop_auto_pull.sh"
echo "To view logs: tail -f auto_pull.log"

