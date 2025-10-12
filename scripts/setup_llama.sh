#!/bin/bash

# Mobile LLM Battery Benchmark - llama.cpp Setup Script
# Downloads and configures llama.cpp for Android JNI integration

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
LLAMA_REPO="https://github.com/ggerganov/llama.cpp.git"
LLAMA_DIR="app/src/main/cpp/llama.cpp"
BRANCH="master"  # Use master branch for latest features

echo -e "${BLUE}Mobile LLM Battery Benchmark - llama.cpp Setup${NC}"
echo "=================================================="

# Check if git is available
if ! command -v git &> /dev/null; then
    echo -e "${RED}Error: git is not installed or not in PATH${NC}"
    echo "Please install git and try again."
    exit 1
fi

# Check if we're in the project root
if [ ! -f "settings.gradle.kts" ] || [ ! -d "app" ]; then
    echo -e "${RED}Error: Please run this script from the project root directory${NC}"
    echo "Expected to find settings.gradle.kts and app/ directory"
    exit 1
fi

# Check if llama.cpp directory already exists
if [ -d "$LLAMA_DIR" ]; then
    echo -e "${YELLOW}llama.cpp directory already exists at: $LLAMA_DIR${NC}"
    echo "Checking if it's a valid llama.cpp repository..."
    
    if [ -f "$LLAMA_DIR/CMakeLists.txt" ] && [ -d "$LLAMA_DIR/include" ]; then
        echo -e "${GREEN}✓ Valid llama.cpp repository found${NC}"
        echo "Skipping download. If you want to update, please delete the directory and run again."
        exit 0
    else
        echo -e "${YELLOW}Directory exists but doesn't appear to be a valid llama.cpp repository${NC}"
        echo "Removing invalid directory..."
        rm -rf "$LLAMA_DIR"
    fi
fi

# Create cpp directory if it doesn't exist
mkdir -p app/src/main/cpp

echo -e "${BLUE}Cloning llama.cpp repository...${NC}"
echo "Repository: $LLAMA_REPO"
echo "Branch: $BRANCH"
echo "Destination: $LLAMA_DIR"

# Clone the repository
if git clone --depth 1 --branch "$BRANCH" "$LLAMA_REPO" "$LLAMA_DIR"; then
    echo -e "${GREEN}✓ Successfully cloned llama.cpp${NC}"
else
    echo -e "${RED}Error: Failed to clone llama.cpp repository${NC}"
    echo "Please check your internet connection and try again."
    exit 1
fi

# Verify the clone was successful
if [ ! -f "$LLAMA_DIR/CMakeLists.txt" ]; then
    echo -e "${RED}Error: llama.cpp CMakeLists.txt not found after cloning${NC}"
    echo "The repository may be corrupted or incomplete."
    exit 1
fi

if [ ! -d "$LLAMA_DIR/include" ]; then
    echo -e "${RED}Error: llama.cpp include directory not found after cloning${NC}"
    echo "The repository may be corrupted or incomplete."
    exit 1
fi

echo -e "${GREEN}✓ llama.cpp setup completed successfully!${NC}"
echo ""
echo -e "${BLUE}Next steps:${NC}"
echo "1. Build the project: ./gradlew assembleDebug"
echo "2. Install on device: adb install app/build/outputs/apk/debug/app-debug.apk"
echo "3. Test the app with native llama.cpp integration"
echo ""
echo -e "${BLUE}Project structure:${NC}"
echo "├── app/src/main/cpp/"
echo "│   ├── CMakeLists.txt"
echo "│   ├── llama-wrapper.cpp"
echo "│   └── llama.cpp/          ← Newly added"
echo "│       ├── CMakeLists.txt"
echo "│       ├── include/"
echo "│       └── src/"
echo ""
echo -e "${GREEN}Setup complete! You can now build the project with native LLM support.${NC}"
