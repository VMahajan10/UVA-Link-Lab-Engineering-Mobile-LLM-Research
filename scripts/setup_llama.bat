@echo off
REM Mobile LLM Battery Benchmark - llama.cpp Setup Script (Windows)
REM Downloads and configures llama.cpp for Android JNI integration

setlocal enabledelayedexpansion

REM Configuration
set LLAMA_REPO=https://github.com/ggerganov/llama.cpp.git
set LLAMA_DIR=app\src\main\cpp\llama.cpp
set BRANCH=master

echo Mobile LLM Battery Benchmark - llama.cpp Setup
echo ==================================================

REM Check if git is available
git --version >nul 2>&1
if errorlevel 1 (
    echo Error: git is not installed or not in PATH
    echo Please install git and try again.
    echo Download from: https://git-scm.com/download/win
    pause
    exit /b 1
)

REM Check if we're in the project root
if not exist "settings.gradle.kts" (
    echo Error: Please run this script from the project root directory
    echo Expected to find settings.gradle.kts file
    pause
    exit /b 1
)

if not exist "app" (
    echo Error: Please run this script from the project root directory
    echo Expected to find app/ directory
    pause
    exit /b 1
)

REM Check if llama.cpp directory already exists
if exist "%LLAMA_DIR%" (
    echo llama.cpp directory already exists at: %LLAMA_DIR%
    echo Checking if it's a valid llama.cpp repository...
    
    if exist "%LLAMA_DIR%\CMakeLists.txt" if exist "%LLAMA_DIR%\include" (
        echo ✓ Valid llama.cpp repository found
        echo Skipping download. If you want to update, please delete the directory and run again.
        pause
        exit /b 0
    ) else (
        echo Directory exists but doesn't appear to be a valid llama.cpp repository
        echo Removing invalid directory...
        rmdir /s /q "%LLAMA_DIR%"
    )
)

REM Create cpp directory if it doesn't exist
if not exist "app\src\main\cpp" (
    mkdir "app\src\main\cpp"
)

echo Cloning llama.cpp repository...
echo Repository: %LLAMA_REPO%
echo Branch: %BRANCH%
echo Destination: %LLAMA_DIR%

REM Clone the repository
git clone --depth 1 --branch %BRANCH% %LLAMA_REPO% %LLAMA_DIR%
if errorlevel 1 (
    echo Error: Failed to clone llama.cpp repository
    echo Please check your internet connection and try again.
    pause
    exit /b 1
)

echo ✓ Successfully cloned llama.cpp

REM Verify the clone was successful
if not exist "%LLAMA_DIR%\CMakeLists.txt" (
    echo Error: llama.cpp CMakeLists.txt not found after cloning
    echo The repository may be corrupted or incomplete.
    pause
    exit /b 1
)

if not exist "%LLAMA_DIR%\include" (
    echo Error: llama.cpp include directory not found after cloning
    echo The repository may be corrupted or incomplete.
    pause
    exit /b 1
)

echo ✓ llama.cpp setup completed successfully!
echo.
echo Next steps:
echo 1. Build the project: gradlew.bat assembleDebug
echo 2. Install on device: adb install app\build\outputs\apk\debug\app-debug.apk
echo 3. Test the app with native llama.cpp integration
echo.
echo Project structure:
echo ├── app\src\main\cpp\
echo │   ├── CMakeLists.txt
echo │   ├── llama-wrapper.cpp
echo │   └── llama.cpp\          ← Newly added
echo │       ├── CMakeLists.txt
echo │       ├── include\
echo │       └── src\
echo.
echo Setup complete! You can now build the project with native LLM support.
pause
