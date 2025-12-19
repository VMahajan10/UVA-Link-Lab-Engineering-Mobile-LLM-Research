#!/bin/bash
# Install auto-pull as a system service that runs automatically

cd "$(dirname "$0")"

LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"
PLIST_NAME="com.llmbattery.autopull.plist"
PLIST_PATH="$LAUNCH_AGENTS_DIR/$PLIST_NAME"

echo "Installing automatic results puller..."
echo ""

# Create LaunchAgents directory if it doesn't exist
mkdir -p "$LAUNCH_AGENTS_DIR"

# Copy plist file
cp "$PLIST_NAME" "$PLIST_PATH"
echo "✅ Installed LaunchAgent plist"

# Load the service
launchctl unload "$PLIST_PATH" 2>/dev/null
launchctl load "$PLIST_PATH"
echo "✅ Started auto-pull service"

echo ""
echo "✅ Auto-pull is now installed and running!"
echo ""
echo "It will:"
echo "  • Start automatically when you log in"
echo "  • Keep running in the background"
echo "  • Automatically pull results when benchmarks complete"
echo "  • Create merged CSV files"
echo "  • Open Finder when new results arrive"
echo ""
echo "To stop it: ./uninstall_auto_pull.sh"
echo "To check status: launchctl list | grep llmbattery"

