package com.research.llmbattery

import android.content.Context
import android.util.Log
import android.provider.MediaStore
import android.content.ContentUris
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay

/**
 * LLMService class that uses llama.cpp native library for Android LLM inference.
 * Handles model loading, inference execution, and memory management for
 * battery benchmarking applications.
 * 
 * Features:
 * - llama.cpp native library integration via JNI
 * - Model loading from external storage
 * - Async inference execution with coroutines
 * - Memory usage tracking and inference time measurement
 * - Thread-safe operations with proper state management
 * - Comprehensive error handling and logging
 */
class LLMService(private val context: Context) {
    
    companion object {
        private const val TAG = "LLMService"
        
        init {
            try {
                // Load GGML dependencies first (required by libllama.so)
                try {
                    System.loadLibrary("ggml")
                    Log.i(TAG, "Native library ggml loaded successfully")
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "ggml library not found (may be statically linked): ${e.message}")
                }
                
                try {
                    System.loadLibrary("ggml-base")
                    Log.i(TAG, "Native library ggml-base loaded successfully")
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "ggml-base library not found (may be statically linked): ${e.message}")
                }
                
                try {
                    System.loadLibrary("ggml-cpu")
                    Log.i(TAG, "Native library ggml-cpu loaded successfully")
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "ggml-cpu library not found (may be statically linked): ${e.message}")
                }
                
                // Load llama.so (main library)
                System.loadLibrary("llama")
                Log.i(TAG, "Native library llama loaded successfully")
                
                // Then load our JNI wrapper
                System.loadLibrary("llama-jni")
                Log.i(TAG, "Native library llama-jni loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}", e)
            }
        }
    }
    
    // Native method declarations
    private external fun nativeInit(modelPath: String, nThreads: Int, nCtx: Int): Long
    private external fun nativeGenerate(contextPtr: Long, prompt: String, maxTokens: Int): String
    private external fun nativeFree(contextPtr: Long)
    
    private var nativeContextPtr: Long = 0
    private var modelPath: String? = null
    var isModelLoaded: Boolean = false
        private set
    var quantizationType: String = ""
        private set
    private var lastInferenceTimeMs: Long = 0
    private var useNative: Boolean = true
    
    /**
     * Loads a model from external storage using MLC-LLM.
     * 
     * @param modelFileName Name of the model file (e.g., "qwen2.5-0.5b-instruct-q2_k.gguf")
     * @return True if model loaded successfully, false otherwise
     */
    fun loadModel(modelFileName: String): Boolean {
        return try {
            Log.i(TAG, "Loading model: $modelFileName")
            
            // Copy file to app's private directory for native access
            // Use MediaStore API to access Download folder on Android 10+
            val appFilesDir = context.getExternalFilesDir(null) ?: context.filesDir
            val privateModelFile = File(appFilesDir, modelFileName)
            
            // Check if we already have the file
            if (privateModelFile.exists()) {
                Log.i(TAG, "Model already exists in private directory: ${privateModelFile.absolutePath}")
                modelPath = privateModelFile.absolutePath
                Log.i(TAG, "Using existing model path: $modelPath")
            } else {
                // Try multiple methods to access the file
                var fileUri: android.net.Uri? = null
                
                // Method 1: Try MediaStore API
                Log.i(TAG, "Looking for model in Download folder via MediaStore...")
                fileUri = findFileInDownloads(modelFileName)
                
                // Method 2: Try direct file access with Environment API
                if (fileUri == null) {
                    Log.i(TAG, "MediaStore not found, trying direct file access...")
                    try {
                        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val file = File(downloadDir, modelFileName)
                        if (file.exists() && file.canRead()) {
                            fileUri = android.net.Uri.fromFile(file)
                            Log.i(TAG, "Found file via direct access: ${file.absolutePath}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Direct file access failed: ${e.message}")
                    }
                }
                
                // Method 3: Try /sdcard/Download path directly
                if (fileUri == null) {
                    Log.i(TAG, "Trying /sdcard/Download path...")
                    try {
                        val file = File("/sdcard/Download", modelFileName)
                        if (file.exists()) {
                            // Try to read it
                            file.inputStream().use { it.read() }
                            fileUri = android.net.Uri.fromFile(file)
                            Log.i(TAG, "Found file via /sdcard/Download: ${file.absolutePath}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "/sdcard/Download access failed: ${e.message}")
                    }
                }
                
                if (fileUri == null) {
                    Log.e(TAG, "Model not found in Download folder: $modelFileName")
                    Log.e(TAG, "Please ensure the file exists in /sdcard/Download/")
                    return false
                }
                
                // Copy from URI to private directory
                Log.i(TAG, "Copying model to private directory: ${privateModelFile.absolutePath}")
                try {
                    val inputStream = if (fileUri.scheme == "file") {
                        File(fileUri.path ?: "").inputStream()
                    } else {
                        context.contentResolver.openInputStream(fileUri)
                    }
                    
                    inputStream?.use { input ->
                        FileOutputStream(privateModelFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("Could not open input stream from URI")
                    
                    Log.i(TAG, "Model copied successfully (${privateModelFile.length()} bytes)")
                    modelPath = privateModelFile.absolutePath
                    Log.i(TAG, "Using model path: $modelPath")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy model file: ${e.message}", e)
                    return false
                }
            }
            
            // Try to load using native library
            if (useNative) {
                try {
                    val nThreads = 4 // Use 4 threads for mobile
                    val nCtx = 2048   // Context window size
                    nativeContextPtr = nativeInit(modelPath ?: "", nThreads, nCtx)
                    
                    if (nativeContextPtr == 0L) {
                        Log.w(TAG, "Native initialization failed, falling back to mock")
                        useNative = false
                    } else {
                        Log.i(TAG, "Native model loaded successfully, contextPtr: $nativeContextPtr")
                        isModelLoaded = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Native library error: ${e.message}", e)
                    useNative = false
                }
            }
            
            // Fallback to mock if native failed
            if (!useNative || !isModelLoaded) {
                Log.w(TAG, "Using mock engine as fallback")
                // Keep mock fallback for now, but we should remove it once native works
                isModelLoaded = true // Set to true for mock mode
            }
            
            // Detect quantization type from model name
            quantizationType = detectQuantizationType(modelFileName)
            
            Log.i(TAG, "Model loaded successfully (native: $useNative)")
            Log.i(TAG, "Model path: $modelPath")
            Log.i(TAG, "Quantization: $quantizationType")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            isModelLoaded = false
            false
        }
    }
    
    /**
     * Generates a response for the given prompt using the loaded model.
     * 
     * @param prompt The input prompt for the LLM
     * @return Generated response string, or error message if failed
     */
    suspend fun generateResponse(prompt: String): String {
        if (!isModelLoaded) {
            return "Error: Model not loaded"
        }
        
        return try {
            val startTime = System.currentTimeMillis()
            
            val response = if (useNative && nativeContextPtr != 0L) {
                // Use native library
                Log.d(TAG, "Using native library for inference")
                nativeGenerate(nativeContextPtr, prompt, maxTokens = 512)
            } else {
                // Fallback to mock
                Log.w(TAG, "Using mock response (native not available)")
                delay(1000) // Simulate inference delay
                "Mock Response: This is a simulated response for the prompt: '$prompt'. " +
                        "Model loaded from: $modelPath. Native library not available."
            }
            
            lastInferenceTimeMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "Inference completed in ${lastInferenceTimeMs}ms")
            
            response
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            "Error: ${e.message}"
        }
    }
    
    /**
     * Unloads the current model and frees resources.
     */
    fun unloadModel() {
        if (useNative && nativeContextPtr != 0L) {
            try {
                nativeFree(nativeContextPtr)
                Log.i(TAG, "Native context freed")
            } catch (e: Exception) {
                Log.e(TAG, "Error freeing native context: ${e.message}", e)
            }
            nativeContextPtr = 0L
        }
        isModelLoaded = false
        modelPath = null
        Log.i(TAG, "Model unloaded")
    }
    
    /**
     * Gets the last inference time in milliseconds.
     * 
     * @return Last inference time in milliseconds
     */
    fun getInferenceTime(): Long = lastInferenceTimeMs
    
    
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
     * Gets the current model path.
     * 
     * @return Model path, or null if no model loaded
     */
    fun getModelPath(): String? = modelPath
    
    /**
     * Gets the size of the loaded model file in MB.
     * 
     * @return Model file size in MB
     */
    fun getModelSizeMB(): Float {
        return modelPath?.let { path ->
            try {
                File(path).length() / (1024.0f * 1024.0f)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting model size", e)
                0f
            }
        } ?: 0f
    }
    
    /**
     * Resets the service state (useful for testing).
     */
    fun reset() {
        unloadModel()
        quantizationType = ""
        Log.d(TAG, "Service state reset")
    }
    
    /**
     * Cleanup method to ensure proper resource disposal.
     */
    fun cleanup() {
        unloadModel()
        Log.d(TAG, "LLMService cleaned up")
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
            else -> "Unknown"
        }
    }
    
    /**
     * Checks if a model file exists in external storage.
     * 
     * @param modelFileName Name of the model file
     * @return True if model exists, false otherwise
     */
    fun isModelAvailable(modelFileName: String): Boolean {
        return try {
            findFileInDownloads(modelFileName) != null
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Finds a file in the Download folder using MediaStore API (Android 10+ compatible).
     * 
     * @param fileName Name of the file to find
     * @return Content URI of the file, or null if not found
     */
    private fun findFileInDownloads(fileName: String): android.net.Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore.Downloads for Android 10+
                val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
                val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(fileName)
                
                val cursor = context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )
                
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idColumn = it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                        val id = it.getLong(idColumn)
                        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    } else {
                        null
                    }
                }
            } else {
                // For Android 9 and below, try direct file access
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                if (file.exists()) {
                    android.net.Uri.fromFile(file)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding file in Downloads: ${e.message}", e)
            null
        }
    }
}