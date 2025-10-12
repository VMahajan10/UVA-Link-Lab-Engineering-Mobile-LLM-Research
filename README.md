# Mobile LLM Battery Benchmark

## Project Overview

**Purpose**: Measure battery consumption of quantized Large Language Models (LLMs) running locally on Android devices to understand the trade-offs between model quantization and power efficiency.

**Target Device**: Samsung A16 5G (ARM64 architecture)

**Testing Scope**: 
- 2-bit, 3-bit, 4-bit quantized models
- Battery life impact analysis
- Inference time measurements
- Response quality assessment

**Metrics Tracked**:
- Battery consumption per query
- Average inference time
- Memory usage patterns
- Model loading time
- Response quality scores

## Architecture

- **Language**: Kotlin + C++ (JNI)
- **LLM Runtime**: llama.cpp
- **Model Format**: GGUF (from irish-quant on Hugging Face)
- **Build System**: Gradle (no Android Studio required)
- **UI Framework**: Material Design 3 with ViewBinding
- **Background Processing**: WorkManager for scheduled queries
- **Data Storage**: CSV export for analysis

## Project Structure

```
mobile-llm-battery-benchmark/
├── app/
│   ├── src/main/
│   │   ├── java/com/research/llmbattery/
│   │   │   ├── MainActivity.kt              # Main UI and benchmark control
│   │   │   ├── LLMService.kt               # JNI interface to llama.cpp
│   │   │   ├── BatteryMonitor.kt           # Battery consumption tracking
│   │   │   ├── DataLogger.kt               # Results logging and CSV export
│   │   │   ├── QueryScheduler.kt           # WorkManager for periodic queries
│   │   │   └── models/                     # Data models
│   │   │       ├── QueryResult.kt
│   │   │       ├── BatteryMetrics.kt
│   │   │       └── ModelConfig.kt
│   │   ├── res/                            # Android resources
│   │   │   ├── layout/
│   │   │   ├── values/
│   │   │   └── drawable/
│   │   ├── assets/models/                  # GGUF model files
│   │   └── AndroidManifest.xml
│   ├── src/main/cpp/                       # Native C++ code (future)
│   │   └── CMakeLists.txt
│   └── build.gradle.kts
├── android-sdk/                            # Local Android SDK
├── build.gradle.kts                        # Root build configuration
├── settings.gradle.kts                     # Project settings
├── gradle.properties                       # Gradle properties
└── README.md
```

## Prerequisites

- **Java Development Kit**: JDK 17 or higher
- **Android SDK**: API level 24+ (Android 7.0)
- **Device**: Samsung A16 5G or compatible ARM64 device
- **Storage**: 2-4GB for model files
- **RAM**: 4GB+ recommended

## Setup Instructions

### 1. Clone the Repository
```bash
git clone <repository-url>
cd mobile-llm-battery-benchmark
```

### 2. Set Environment Variables
```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
```

### 3. Build the Project
```bash
./gradlew assembleDebug
```

### 4. Install on Device
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

### Running the Benchmark

1. **Launch the App**: Open "LLM Battery Benchmark" on your device
2. **Select Model**: Choose from available quantized models
3. **Set Interval**: Configure query frequency (1 or 5 minutes)
4. **Start Benchmark**: Tap "Start Benchmark" to begin testing
5. **Monitor Progress**: View real-time battery and performance metrics
6. **Export Results**: Tap "Export Results" to save data as CSV

### Available Models

The app supports GGUF format models from irish-quant:
- **2-bit quantized**: Maximum compression, fastest inference
- **3-bit quantized**: Balanced compression and quality
- **4-bit quantized**: Higher quality, moderate compression

### Benchmark Process

1. **Model Loading**: Loads selected GGUF model into memory
2. **Query Execution**: Runs predefined test queries at specified intervals
3. **Metrics Collection**: Records battery level, inference time, memory usage
4. **Data Logging**: Saves results to internal storage
5. **Export**: Generates CSV files for analysis

## Configuration

### Model Configuration
- Models are placed in `app/src/main/assets/models/`
- Supported format: GGUF (.gguf files)
- Recommended models: irish-quant quantized variants

### Benchmark Settings
- **Query Interval**: 1 minute (intensive) or 5 minutes (moderate)
- **Test Queries**: 20 predefined diverse prompts
- **Battery Threshold**: Minimum 15% battery required
- **Power Save Mode**: Automatically pauses during power saving

## Data Analysis

### Exported Data
The app exports CSV files containing:
- Timestamp of each query
- Query text and response
- Inference time (milliseconds)
- Battery level before/after
- Memory usage
- Model information

### Analysis Tools
Use the exported CSV data with:
- Python pandas for data analysis
- Jupyter notebooks for visualization
- Excel/Google Sheets for basic analysis

## Development

### Building from Source
```bash
# Clean build
./gradlew clean

# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

### Adding New Models
1. Download GGUF model files
2. Place in `app/src/main/assets/models/`
3. Rebuild and install app
4. Models will appear in the selection spinner

### Native Development
The project is configured for C++ development:
- CMake configuration in `app/src/main/cpp/`
- JNI interface in `LLMService.kt`
- Native library: `libllama-jni.so`

## Troubleshooting

### Common Issues

**App won't start**:
- Check device compatibility (ARM64 required)
- Verify Android version (7.0+)
- Check available storage space

**Models not loading**:
- Ensure GGUF format
- Check file size (not corrupted)
- Verify model is in assets/models/

**Battery monitoring issues**:
- Grant battery optimization permissions
- Disable battery optimization for the app
- Check device battery health

### Debug Mode
Enable debug logging:
```bash
adb logcat -s "LLMBatteryBenchmark" "MainActivity" "LLMService"
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test on target device
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- **llama.cpp**: Native LLM inference engine
- **irish-quant**: Quantized model repository
- **Android WorkManager**: Background task scheduling
- **Material Design**: UI components and guidelines

## Contact

For questions or issues, please open an issue on GitHub or contact the development team.

---

**Note**: This project is designed for research purposes. Battery consumption may vary significantly based on device specifications, model complexity, and usage patterns.
