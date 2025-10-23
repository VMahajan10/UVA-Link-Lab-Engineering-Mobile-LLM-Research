cmake_minimum_required(VERSION 3.22.1)

# Project name
project("llama-jni")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Android-specific patches for llama.cpp
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Disable llama server" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Disable tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Disable examples" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP" FORCE)
set(GGML_METAL OFF CACHE BOOL "Disable Metal" FORCE)

# Fix pthread_cancel issue
add_compile_definitions(GGML_USE_CPU)
add_compile_definitions(__ANDROID__)

# Add llama.cpp as subdirectory
add_subdirectory(llama.cpp)

# Find Android log library
find_library(log-lib log)

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp
    llama.cpp/include
    llama.cpp/src
)

# Create shared library
add_library(
    llama-jni
    SHARED
    llama-wrapper.cpp
)

# Link libraries
target_link_libraries(
    llama-jni
    llama
    ${log-lib}
)

# Set output directory
set_target_properties(
    llama-jni
    PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
)

# Compiler flags for Android
target_compile_definitions(
    llama-jni
    PRIVATE
    -DANDROID
    -D__ANDROID_API__=24
    -DGGML_USE_ACCELERATE=0
    -DGGML_USE_OPENBLAS=0
    -DGGML_USE_CUBLAS=0
    -DGGML_USE_CLBLAST=0
    -DGGML_USE_METAL=0
    -DGGML_USE_KOMPUTE=0
    -DGGML_USE_SYCL=0
    -DGGML_USE_VULKAN=0
    -DGGML_USE_HIPBLAS=0
    -DGGML_USE_CUDA=0
    -DGGML_USE_LLAMAFILE=0
    -DGGML_COMPILER_SUPPORTS_FP16_FORMAT_I3E=0
)

# Optimization flags
target_compile_options(
    llama-jni
    PRIVATE
    -O3
    -DNDEBUG
    -ffast-math
    -fno-exceptions
    -fno-rtti
    -fPIC
    -D_POSIX_C_SOURCE=200809L
    -DANDROID
    -D__ANDROID_API__=24
)

# Set properties for Android
set_target_properties(
    llama-jni
    PROPERTIES
    ANDROID_ABI arm64-v8a
    ANDROID_PLATFORM android-24
)
