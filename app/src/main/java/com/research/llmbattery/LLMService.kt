package com.research.llmbattery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * LLMService class that interfaces with llama.cpp via JNI for LLM inference.
 * Handles model loading, inference execution, and memory management for
 * battery benchmarking applications.
 * 
 * Features:
 * - GGUF model loading from assets/models/ directory
 * - Native llama.cpp integration via JNI
 * - Async inference execution with coroutines
 * - Memory usage tracking and inference time measurement
 * - Thread-safe operations with proper state management
 * - Comprehensive error handling and logging
 */
class LLMService(private val context: Context) {
    
    companion object {
        private const val TAG = "LLMService"
        private const val NATIVE_LIBRARY_NAME = "llama-jni"
        private const val MODELS_DIR = "models"
        private const val ASSETS_MODELS_PATH = "models/"
        
        // Model file extensions
        private const val GGUF_EXTENSION = ".gguf"
        
        // Default model configuration
        private const val DEFAULT_QUANTIZATION = "4-bit"
        
        // Flag to track if native library is available
        private var isNativeLibraryAvailable = false
        
        // Initialize native library
        init {
            try {
                System.loadLibrary(NATIVE_LIBRARY_NAME)
                isNativeLibraryAvailable = true
                Log.i(TAG, "Successfully loaded native library: $NATIVE_LIBRARY_NAME")
            } catch (e: UnsatisfiedLinkError) {
                isNativeLibraryAvailable = false
                Log.w(TAG, "Native library not available: $NATIVE_LIBRARY_NAME - running in mock mode", e)
            }
        }
        
        /**
         * Gets the list of available model files in the assets/models/ directory.
         * 
         * @param context Application context
         * @return List of model file names
         */
        fun getAvailableModels(context: Context): List<String> {
            return try {
                val assets = context.assets.list(ASSETS_MODELS_PATH)
                assets?.filter { it.endsWith(GGUF_EXTENSION) }?.toList() ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error listing available models", e)
                emptyList()
            }
        }
        
        /**
         * Checks if a model file exists in the assets directory.
         * 
         * @param context Application context
         * @param modelName Name of the model file
         * @return True if model exists, false otherwise
         */
        fun isModelAvailable(context: Context, modelName: String): Boolean {
            return try {
                val modelPath = "$ASSETS_MODELS_PATH$modelName"
                context.assets.open(modelPath).use { true }
            } catch (e: Exception) {
                false
            }
        }
        
        /**
         * Checks if the native library is available.
         * 
         * @return True if native library is loaded, false otherwise
         */
        fun isNativeLibraryAvailable(): Boolean = isNativeLibraryAvailable
    }
    
    // Properties
    private var modelPath: String? = null
    private var contextPointer: Long = 0L
    private var isModelLoaded: Boolean = false
    private var quantizationType: String = DEFAULT_QUANTIZATION
    private var lastInferenceTimeMs: Long = 0L
    private var lastMemoryUsage: Long = 0L
    
    // Thread safety
    @Volatile
    private var isLoading: Boolean = false
    
    /**
     * Loads a GGUF model from the assets/models/ directory.
     * Copies the model to internal storage and initializes the native context.
     * 
     * @param modelName Name of the model file in assets/models/
     * @return True if model loaded successfully, false otherwise
     */
    suspend fun loadModel(modelName: String): Boolean = withContext(Dispatchers.IO) {
        // Validate model name
        if (modelName.isBlank() || !modelName.endsWith(GGUF_EXTENSION)) {
            Log.e(TAG, "Invalid model name: $modelName")
            return@withContext false
        }
        
        // Check if model exists in assets
        if (!isModelAvailable(context, modelName)) {
            Log.e(TAG, "Model not found in assets: $modelName")
            return@withContext false
        }
        
        // Copy model from assets to internal storage
        val internalModelPath = copyModelFromAssets(modelName)
        if (internalModelPath == null) {
            Log.e(TAG, "Failed to copy model from assets")
            return@withContext false
        }
        
        synchronized(this@LLMService) {
            try {
                // Check if already loading
                if (isLoading) {
                    Log.w(TAG, "Model loading already in progress")
                    return@withContext false
                }
                
                // Check if model already loaded
                if (isModelLoaded) {
                    Log.w(TAG, "Model already loaded, unload first")
                    return@withContext false
                }
                
                isLoading = true
                
                // Initialize native context
                val nativeContext = if (isNativeLibraryAvailable) {
                    initializeNative(internalModelPath)
                } else {
                    Log.w(TAG, "Native library not available, using mock context")
                    1L // Mock context pointer
                }
                
                if (nativeContext == 0L) {
                    Log.e(TAG, "Failed to initialize native context")
                    return@withContext false
                }
                
                // Update state
                modelPath = internalModelPath
                contextPointer = nativeContext
                isModelLoaded = true
                
                // Detect quantization type from model name
                quantizationType = detectQuantizationType(modelName)
                
                Log.i(TAG, "Successfully loaded model: $modelName")
                Log.i(TAG, "Model path: $internalModelPath")
                Log.i(TAG, "Quantization: $quantizationType")
                Log.i(TAG, "Context pointer: $contextPointer")
                
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model: $modelName", e)
                false
            } finally {
                isLoading = false
            }
        }
    }
    
    /**
     * Unloads the current model and frees native memory.
     * This method should be called when the model is no longer needed.
     */
    @Synchronized
    fun unloadModel() {
        try {
            if (isModelLoaded && contextPointer != 0L) {
                // Free native resources
                if (isNativeLibraryAvailable) {
                    freeNative(contextPointer)
                } else {
                    Log.d(TAG, "Skipping native cleanup - library not available")
                }
                
                // Clear state
                modelPath = null
                contextPointer = 0L
                isModelLoaded = false
                lastInferenceTimeMs = 0L
                lastMemoryUsage = 0L
                
                Log.i(TAG, "Successfully unloaded model")
            } else {
                Log.w(TAG, "No model loaded to unload")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading model", e)
        }
    }
    
    /**
     * Generates a response for the given prompt using the loaded model.
     * This is an async operation that runs on the IO dispatcher.
     * 
     * @param prompt The input prompt for the LLM
     * @return Generated response string, or empty string if failed
     */
    suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            // Check if model is loaded
            if (!isModelLoaded || contextPointer == 0L) {
                Log.e(TAG, "Model not loaded, cannot generate response")
                return@withContext ""
            }
            
            // Validate prompt
            if (prompt.isBlank()) {
                Log.w(TAG, "Empty prompt provided")
                return@withContext ""
            }
            
            Log.d(TAG, "Generating response for prompt: ${prompt.take(100)}...")
            
            // Measure inference time
            val startTime = System.currentTimeMillis()
            
            // Generate response using native inference
            val response = if (isNativeLibraryAvailable) {
                inferNative(contextPointer, prompt)
            } else {
                // Mock response for testing
                "Mock response: This is a simulated LLM response for testing purposes. The native library is not available."
            }
            
            val endTime = System.currentTimeMillis()
            lastInferenceTimeMs = endTime - startTime
            
            // Update memory usage
            lastMemoryUsage = getMemoryUsage()
            
            Log.d(TAG, "Generated response in ${lastInferenceTimeMs}ms")
            Log.d(TAG, "Response length: ${response.length} characters")
            
            response
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating response", e)
            ""
        }
    }
    
    /**
     * Gets the last inference time in milliseconds.
     * 
     * @return Last inference time in milliseconds
     */
    fun getInferenceTime(): Long = lastInferenceTimeMs
    
    /**
     * Gets the current memory usage in bytes.
     * 
     * @return Current memory usage in bytes
     */
    fun getMemoryUsage(): Long = lastMemoryUsage
    
    /**
     * Gets the model name from the loaded model path.
     * 
     * @return Model name, or null if no model loaded
     */
    fun getModelName(): String? {
        return modelPath?.let { path ->
            File(path).nameWithoutExtension
        }
    }
    
    /**
     * Gets the quantization type of the loaded model.
     * 
     * @return Quantization type string
     */
    fun getQuantizationType(): String = quantizationType
    
    /**
     * Checks if a model is currently loaded.
     * 
     * @return True if model is loaded, false otherwise
     */
    fun isModelLoaded(): Boolean = isModelLoaded
    
    /**
     * Gets the current model path.
     * 
     * @return Model path, or null if no model loaded
     */
    fun getModelPath(): String? = modelPath
    
    /**
     * Gets the native context pointer.
     * 
     * @return Context pointer, or 0 if no model loaded
     */
    fun getContextPointer(): Long = contextPointer
    
    /**
     * Copies a model file from assets to internal storage.
     * 
     * @param modelName Name of the model file in assets
     * @return Path to the copied model file, or null if failed
     */
    private suspend fun copyModelFromAssets(modelName: String): String? = withContext(Dispatchers.IO) {
        try {
            val internalDir = File(context.filesDir, MODELS_DIR)
            if (!internalDir.exists()) {
                internalDir.mkdirs()
            }
            
            val internalModelPath = File(internalDir, modelName).absolutePath
            val internalModelFile = File(internalModelPath)
            
            // Check if model already exists in internal storage
            if (internalModelFile.exists()) {
                Log.d(TAG, "Model already exists in internal storage: $internalModelPath")
                return@withContext internalModelPath
            }
            
            // Copy from assets to internal storage
            val assetsPath = "$ASSETS_MODELS_PATH$modelName"
            context.assets.open(assetsPath).use { inputStream ->
                FileOutputStream(internalModelFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            Log.d(TAG, "Copied model from assets to: $internalModelPath")
            internalModelPath
            
        } catch (e: IOException) {
            Log.e(TAG, "Error copying model from assets", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error copying model from assets", e)
            null
        }
    }
    
    /**
     * Detects the quantization type from the model name.
     * 
     * @param modelName Name of the model file
     * @return Detected quantization type
     */
    private fun detectQuantizationType(modelName: String): String {
        return when {
            modelName.contains("q2") || modelName.contains("2bit") -> "2-bit"
            modelName.contains("q3") || modelName.contains("3bit") -> "3-bit"
            modelName.contains("q4") || modelName.contains("4bit") -> "4-bit"
            modelName.contains("q5") || modelName.contains("5bit") -> "5-bit"
            modelName.contains("q6") || modelName.contains("6bit") -> "6-bit"
            modelName.contains("q8") || modelName.contains("8bit") -> "8-bit"
            modelName.contains("f16") || modelName.contains("fp16") -> "FP16"
            modelName.contains("f32") || modelName.contains("fp32") -> "FP32"
            else -> DEFAULT_QUANTIZATION
        }
    }
    
    /**
     * Validates that the model file exists and is readable.
     * 
     * @param modelPath Path to the model file
     * @return True if model file is valid, false otherwise
     */
    private fun validateModelFile(modelPath: String): Boolean {
        return try {
            val modelFile = File(modelPath)
            modelFile.exists() && modelFile.isFile && modelFile.canRead()
        } catch (e: Exception) {
            Log.e(TAG, "Error validating model file: $modelPath", e)
            false
        }
    }
    
    /**
     * Gets the size of the loaded model file.
     * 
     * @return Model file size in bytes, or 0 if not available
     */
    fun getModelSize(): Long {
        return modelPath?.let { path ->
            try {
                File(path).length()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting model size", e)
                0L
            }
        } ?: 0L
    }
    
    /**
     * Gets the size of the loaded model file in MB.
     * 
     * @return Model file size in MB
     */
    fun getModelSizeMB(): Float {
        return getModelSize() / (1024.0f * 1024.0f)
    }
    
    /**
     * Resets the service state (useful for testing).
     */
    @Synchronized
    fun reset() {
        unloadModel()
        quantizationType = DEFAULT_QUANTIZATION
        Log.d(TAG, "Service state reset")
    }
    
    /**
     * Native method to initialize the llama.cpp context.
     * This method will be implemented in C++ JNI code.
     * 
     * @param path Path to the GGUF model file
     * @return Native context pointer, or 0 if failed
     */
    private external fun initializeNative(path: String): Long
    
    /**
     * Native method to run inference using the loaded model.
     * This method will be implemented in C++ JNI code.
     * 
     * @param context Native context pointer
     * @param prompt Input prompt for inference
     * @return Generated response string
     */
    private external fun inferNative(context: Long, prompt: String): String
    
    /**
     * Native method to free native resources.
     * This method will be implemented in C++ JNI code.
     * 
     * @param context Native context pointer to free
     */
    private external fun freeNative(context: Long)
    
    /**
     * Cleanup method to ensure proper resource disposal.
     * Should be called when the service is no longer needed.
     */
    fun cleanup() {
        unloadModel()
        Log.d(TAG, "LLMService cleaned up")
    }
}
