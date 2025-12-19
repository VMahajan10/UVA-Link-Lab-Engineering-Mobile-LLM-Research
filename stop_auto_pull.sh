#!/bin/bash
# Stop the automatic results puller

cd "$(dirname "$0")"

if [ -f .auto_pull_pid ]; then
    PID=$(cat .auto_pull_pid)
    if kill -0 "$PID" 2>/dev/null; then
        kill "$PID"
        rm .auto_pull_pid
        echo "✅ Auto-pull stopped"
    else
        echo "⚠️  Auto-pull process not found (may have already stopped)"
        rm .auto_pull_pid
    fi
else
    # Try to find and kill by name
    pkill -f "auto_pull_results.sh"
    if [ $? -eq 0 ]; then
        echo "✅ Auto-pull stopped"
    else
        echo "⚠️  Auto-pull is not running"
    fi
fi

