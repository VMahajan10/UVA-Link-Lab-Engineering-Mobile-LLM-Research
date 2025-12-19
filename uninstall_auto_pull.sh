#!/bin/bash
# Uninstall auto-pull service

cd "$(dirname "$0")"

LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"
PLIST_NAME="com.llmbattery.autopull.plist"
PLIST_PATH="$LAUNCH_AGENTS_DIR/$PLIST_NAME"

echo "Uninstalling automatic results puller..."
echo ""

# Unload the service
if [ -f "$PLIST_PATH" ]; then
    launchctl unload "$PLIST_PATH" 2>/dev/null
    rm "$PLIST_PATH"
    echo "✅ Removed auto-pull service"
else
    echo "⚠️  Service not found (may already be uninstalled)"
fi

# Also stop any manually started instances
pkill -f "auto_pull_results.sh" 2>/dev/null
if [ $? -eq 0 ]; then
    echo "✅ Stopped running instances"
fi

echo ""
echo "✅ Auto-pull uninstalled"

