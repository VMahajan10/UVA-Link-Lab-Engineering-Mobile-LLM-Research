cmake_minimum_required(VERSION 3.22.1)
project(llama-jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find Android log library
find_library(log-lib log)

# Add pre-built libllama.so
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libllama.so
)

# Create our JNI wrapper library
add_library(llama-jni SHARED
    llama-wrapper.cpp
)

# Include directories
target_include_directories(llama-jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/common
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp
)

# Link libraries
target_link_libraries(llama-jni
    llama
    ${log-lib}
)
