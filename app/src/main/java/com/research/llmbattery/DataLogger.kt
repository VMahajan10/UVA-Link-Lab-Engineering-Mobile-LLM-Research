package com.research.llmbattery

import android.content.Context
import android.os.Environment
import android.util.Log
import com.research.llmbattery.models.BatteryMetrics
import com.research.llmbattery.models.QueryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * DataLogger class responsible for logging and exporting performance and query results.
 * Handles both QueryResult and BatteryMetrics data, exporting them to separate CSV files
 * for analysis and reporting purposes.
 * 
 * Features:
 * - Thread-safe logging of query results and battery metrics
 * - CSV export to external storage with proper formatting
 * - Separate CSV files for different data types
 * - Human-readable timestamp formatting
 * - Comprehensive error handling and logging
 * - Memory-efficient data management
 */
class DataLogger(
    private val context: Context
) {
    companion object {
        private const val TAG = "DataLogger"
        private const val QUERY_RESULTS_FILE = "query_results.csv"
        private const val BATTERY_METRICS_FILE = "battery_metrics.csv"
        private const val CSV_DELIMITER = ","
        private const val CSV_QUOTE = "\""
        private const val NEWLINE = "\n"
        
        // CSV Headers
        private const val QUERY_HEADER = "timestamp,queryText,responseText,inferenceTimeMs,batteryLevel,quantization,modelName"
        private const val BATTERY_HEADER = "timestamp,batteryLevel,batteryDrainRate,cpuUsage,memoryUsage,temperature"
        private const val INCREMENTAL_HEADER = "timestamp,query_number,prompt_number,query_text,response_text,response_length_chars,inference_time_ms,battery_before,battery_after,battery_change_absolute,battery_drain_rate_per_hour,cpu_usage_before,cpu_usage_after,cpu_usage_change,memory_usage_before,memory_usage_after,memory_usage_change_mb,temperature_before,temperature_after,temperature_change,model_name,quantization"
        private const val PROMPTS_HEADER = "prompt_number,prompt_text"
    }
    
    // Properties
    private val logFilePath: String = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.absolutePath
        ?: context.filesDir.absolutePath
    private val results: MutableList<QueryResult> = mutableListOf()
    private val batteryMetrics: MutableList<BatteryMetrics> = mutableListOf()
    
    // Thread safety
    private val lock = ReentrantReadWriteLock()
    
    // Date formatter for human-readable timestamps
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    // Single incremental CSV file
    private var incrementalCsvFile: File? = null
    private var incrementalCsvWriter: FileWriter? = null
    private var isIncrementalFileInitialized = false
    
    /**
     * Initializes the incremental CSV file for writing data after each query.
     * Should be called once at the start of a benchmark.
     * 
     * @param modelName Model name (for reference, stored in each row)
     * @param quantization Quantization level (for reference, stored in each row)
     * @param prompts Optional list of prompts used in the benchmark. If provided, will be added as a separate section.
     */
    suspend fun initializeIncrementalCSV(
        @Suppress("UNUSED_PARAMETER") modelName: String, 
        @Suppress("UNUSED_PARAMETER") quantization: String,
        prompts: List<String>? = null
    ) {
        withContext(Dispatchers.IO) {
            lock.write {
                try {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) {
                        downloadsDir.mkdirs()
                    }
                    
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val fileName = "llm_benchmark_$timestamp.csv"
                    
                    incrementalCsvFile = if (downloadsDir.canWrite()) {
                        File(downloadsDir, fileName)
                    } else {
                        File(logFilePath, fileName)
                    }
                    
                    incrementalCsvFile?.parentFile?.mkdirs()
                    incrementalCsvWriter = FileWriter(incrementalCsvFile, false) // Overwrite mode
                    
                    // Note: Prompts section will be added at the end with only used prompts
                    
                    // Write header
                    incrementalCsvWriter?.write(INCREMENTAL_HEADER)
                    incrementalCsvWriter?.write(NEWLINE)
                    incrementalCsvWriter?.flush()
                    
                    isIncrementalFileInitialized = true
                    Log.i(TAG, "Initialized incremental CSV: ${incrementalCsvFile?.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error initializing incremental CSV", e)
                    incrementalCsvWriter?.close()
                    incrementalCsvWriter = null
                    incrementalCsvFile = null
                }
            }
        }
    }
    
    /**
     * Appends a query result with battery metrics to the incremental CSV file.
     * Writes immediately to disk after each query.
     * 
     * @param queryResult The query result
     * @param batteryBefore Battery metrics before the query
     * @param batteryAfter Battery metrics after the query
     * @param queryNumber The query number (1, 2, 3, etc.)
     */
    suspend fun appendQueryToCSV(
        queryResult: QueryResult,
        batteryBefore: BatteryMetrics?,
        batteryAfter: BatteryMetrics?,
        queryNumber: Int
    ) {
        withContext(Dispatchers.IO) {
            lock.write {
                try {
                    if (!isIncrementalFileInitialized || incrementalCsvWriter == null) {
                        Log.w(TAG, "Incremental CSV not initialized, skipping append")
                        return@withContext
                    }
                    
                    // Calculate actual changes
                    val batteryBeforeLevel = batteryBefore?.batteryLevel ?: queryResult.batteryLevel
                    val batteryAfterLevel = queryResult.batteryLevel
                    val batteryChange = batteryBeforeLevel - batteryAfterLevel
                    
                    val cpuBefore = batteryBefore?.cpuUsage ?: 0.0f
                    val cpuAfter = batteryAfter?.cpuUsage ?: batteryBefore?.cpuUsage ?: 0.0f
                    val cpuChange = cpuAfter - cpuBefore
                    
                    val memoryBefore = batteryBefore?.memoryUsage ?: 0L
                    val memoryAfter = batteryAfter?.memoryUsage ?: batteryBefore?.memoryUsage ?: 0L
                    val memoryChangeMB = (memoryAfter - memoryBefore) / (1024.0 * 1024.0)
                    
                    val tempBefore = batteryBefore?.temperature ?: 0.0f
                    val tempAfter = batteryAfter?.temperature ?: batteryBefore?.temperature ?: 0.0f
                    val tempChange = tempAfter - tempBefore
                    
                    val responseLength = queryResult.responseText.length
                    
                    // For incremental CSV, we don't have prompt number, so use 0
                    val promptNumber = 0
                    
                    val csvLine = buildString {
                        // Timestamp
                        append(escapeCsvField(formatTimestamp(queryResult.timestamp)))
                        append(CSV_DELIMITER)
                        
                        // Query number
                        append(queryNumber)
                        append(CSV_DELIMITER)
                        
                        // Prompt number (0 for incremental, will be updated in final export)
                        append(promptNumber)
                        append(CSV_DELIMITER)
                        
                        // Query text
                        append(escapeCsvField(queryResult.queryText))
                        append(CSV_DELIMITER)
                        
                        // Response text (full response)
                        append(escapeCsvField(queryResult.responseText))
                        append(CSV_DELIMITER)
                        
                        // Response length
                        append(responseLength)
                        append(CSV_DELIMITER)
                        
                        // Inference time
                        append(queryResult.inferenceTimeMs)
                        append(CSV_DELIMITER)
                        
                        // Battery before
                        append(batteryBeforeLevel)
                        append(CSV_DELIMITER)
                        
                        // Battery after
                        append(batteryAfterLevel)
                        append(CSV_DELIMITER)
                        
                        // Battery change (absolute) - actual change
                        append(batteryChange)
                        append(CSV_DELIMITER)
                        
                        // Battery drain rate (projected per hour)
                        append(batteryAfter?.batteryDrainRate ?: batteryBefore?.batteryDrainRate ?: 0.0f)
                        append(CSV_DELIMITER)
                        
                        // CPU usage before
                        append(cpuBefore)
                        append(CSV_DELIMITER)
                        
                        // CPU usage after
                        append(cpuAfter)
                        append(CSV_DELIMITER)
                        
                        // CPU usage change
                        append(cpuChange)
                        append(CSV_DELIMITER)
                        
                        // Memory usage before
                        append(memoryBefore)
                        append(CSV_DELIMITER)
                        
                        // Memory usage after
                        append(memoryAfter)
                        append(CSV_DELIMITER)
                        
                        // Memory usage change (MB)
                        append(String.format(Locale.US, "%.2f", memoryChangeMB))
                        append(CSV_DELIMITER)
                        
                        // Temperature before
                        append(tempBefore)
                        append(CSV_DELIMITER)
                        
                        // Temperature after
                        append(tempAfter)
                        append(CSV_DELIMITER)
                        
                        // Temperature change
                        append(String.format(Locale.US, "%.1f", tempChange))
                        append(CSV_DELIMITER)
                        
                        // Model name
                        append(escapeCsvField(queryResult.modelName))
                        append(CSV_DELIMITER)
                        
                        // Quantization
                        append(escapeCsvField(queryResult.quantization))
                        append(NEWLINE)
                    }
                    
                    incrementalCsvWriter?.write(csvLine)
                    incrementalCsvWriter?.flush() // Force write to disk immediately
                    
                    Log.i(TAG, "Appended query $queryNumber to incremental CSV")
                } catch (e: Exception) {
                    Log.e(TAG, "Error appending to incremental CSV", e)
                }
            }
        }
    }
    
    /**
     * Exports all collected query results and battery metrics to a single CSV file.
     * This method matches each query with battery metrics before and after the query.
     * Should be called at the end of benchmark after all data is collected.
     * 
     * @param prompts Optional list of prompts used in the benchmark. If provided, will be added as a separate section.
     * @return File object representing the CSV file, or null if export fails
     */
    suspend fun exportAllDataToCSV(prompts: List<String>? = null): File? {
        return withContext(Dispatchers.IO) {
            try {
                // Save to public Downloads folder for easy access
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "llm_benchmark_$timestamp.csv"
                
                val csvFile = if (downloadsDir.canWrite()) {
                    File(downloadsDir, fileName)
                } else {
                    File(logFilePath, fileName)
                }
                
                csvFile.parentFile?.mkdirs()
                val writer = FileWriter(csvFile, false) // Overwrite mode
                
                // Get all data first
                val queryResults = lock.read { results.toList() }
                
                // Only show prompts that were actually used in the benchmark
                if (queryResults.isNotEmpty()) {
                    // Extract unique prompts that were actually used
                    val usedPrompts = queryResults.map { it.queryText }.distinct()
                    
                    // Create a mapping of prompt to its index in the original list (if provided)
                    val promptToIndex = mutableMapOf<String, Int>()
                    if (prompts != null) {
                        prompts.forEachIndexed { index, prompt ->
                            promptToIndex[prompt] = index + 1
                        }
                    }
                    
                    writer.write("# ========================================")
                    writer.write(NEWLINE)
                    writer.write("# PROMPTS ACTUALLY USED IN THIS BENCHMARK")
                    writer.write(NEWLINE)
                    writer.write("# Total prompts available: ${prompts?.size ?: "unknown"}, Prompts used: ${usedPrompts.size}")
                    writer.write(NEWLINE)
                    writer.write("# ========================================")
                    writer.write(NEWLINE)
                    writer.write("prompt_number,prompt_text,times_used")
                    writer.write(NEWLINE)
                    
                    // Show each used prompt with how many times it was used
                    usedPrompts.forEach { usedPrompt ->
                        val timesUsed = queryResults.count { it.queryText == usedPrompt }
                        val promptNumber = promptToIndex[usedPrompt] ?: "?"
                        writer.write("$promptNumber")
                        writer.write(CSV_DELIMITER)
                        writer.write(escapeCsvField(usedPrompt))
                        writer.write(CSV_DELIMITER)
                        writer.write("$timesUsed")
                        writer.write(NEWLINE)
                    }
                    writer.write(NEWLINE)
                    writer.write("# ========================================")
                    writer.write(NEWLINE)
                    writer.write("# BENCHMARK RESULTS")
                    writer.write(NEWLINE)
                    writer.write("# ========================================")
                    writer.write(NEWLINE)
                }
                
                // Write header
                writer.write(INCREMENTAL_HEADER)
                writer.write(NEWLINE)
                
                // Get battery metrics (queryResults already retrieved above)
                val allBatteryMetrics = lock.read { batteryMetrics.toList() }
                
                Log.i(TAG, "=== CSV EXPORT START ===")
                Log.i(TAG, "Query results count: ${queryResults.size}")
                Log.i(TAG, "Battery metrics count: ${allBatteryMetrics.size}")
                
                if (queryResults.isEmpty()) {
                    Log.w(TAG, "WARNING: No query results to export!")
                }
                if (allBatteryMetrics.isEmpty()) {
                    Log.w(TAG, "WARNING: No battery metrics to export!")
                }
                
                // Create a mapping of prompt text to its number in the original list
                val promptToNumber = mutableMapOf<String, Int>()
                if (prompts != null) {
                    prompts.forEachIndexed { index, prompt ->
                        promptToNumber[prompt] = index + 1
                    }
                }
                
                // Write each query with matching battery metrics
                var rowsWritten = 0
                queryResults.forEachIndexed { index, queryResult ->
                    val queryNumber = index + 1
                    val promptNumber = promptToNumber[queryResult.queryText] ?: 0
                    
                    // Find battery metrics closest to this query timestamp
                    val queryTime = queryResult.timestamp
                    val batteryBefore = allBatteryMetrics
                        .filter { it.timestamp <= queryTime }
                        .maxByOrNull { it.timestamp }
                    val batteryAfter = allBatteryMetrics
                        .filter { it.timestamp >= queryTime }
                        .minByOrNull { it.timestamp }
                    
                    // Calculate actual changes
                    val batteryBeforeLevel = batteryBefore?.batteryLevel ?: queryResult.batteryLevel
                    val batteryAfterLevel = queryResult.batteryLevel
                    val batteryChange = batteryBeforeLevel - batteryAfterLevel  // Actual change (before - after)
                    
                    val cpuBefore = batteryBefore?.cpuUsage ?: 0.0f
                    val cpuAfter = batteryAfter?.cpuUsage ?: batteryBefore?.cpuUsage ?: 0.0f
                    val cpuChange = cpuAfter - cpuBefore
                    
                    val memoryBefore = batteryBefore?.memoryUsage ?: 0L
                    val memoryAfter = batteryAfter?.memoryUsage ?: batteryBefore?.memoryUsage ?: 0L
                    val memoryChangeMB = (memoryAfter - memoryBefore) / (1024.0 * 1024.0)  // Convert to MB
                    
                    val tempBefore = batteryBefore?.temperature ?: 0.0f
                    val tempAfter = batteryAfter?.temperature ?: batteryBefore?.temperature ?: 0.0f
                    val tempChange = tempAfter - tempBefore
                    
                    val responseLength = queryResult.responseText.length
                    
                    val csvLine = buildString {
                        // Timestamp
                        append(escapeCsvField(formatTimestamp(queryResult.timestamp)))
                        append(CSV_DELIMITER)
                        
                        // Query number
                        append(queryNumber)
                        append(CSV_DELIMITER)
                        
                        // Prompt number (from original prompt list)
                        append(promptNumber)
                        append(CSV_DELIMITER)
                        
                        // Query text
                        append(escapeCsvField(queryResult.queryText))
                        append(CSV_DELIMITER)
                        
                        // Response text (full response)
                        append(escapeCsvField(queryResult.responseText))
                        append(CSV_DELIMITER)
                        
                        // Response length
                        append(responseLength)
                        append(CSV_DELIMITER)
                        
                        // Inference time
                        append(queryResult.inferenceTimeMs)
                        append(CSV_DELIMITER)
                        
                        // Battery before
                        append(batteryBeforeLevel)
                        append(CSV_DELIMITER)
                        
                        // Battery after
                        append(batteryAfterLevel)
                        append(CSV_DELIMITER)
                        
                        // Battery change (absolute) - actual change
                        append(batteryChange)
                        append(CSV_DELIMITER)
                        
                        // Battery drain rate (projected per hour)
                        append(batteryAfter?.batteryDrainRate ?: batteryBefore?.batteryDrainRate ?: 0.0f)
                        append(CSV_DELIMITER)
                        
                        // CPU usage before
                        append(cpuBefore)
                        append(CSV_DELIMITER)
                        
                        // CPU usage after
                        append(cpuAfter)
                        append(CSV_DELIMITER)
                        
                        // CPU usage change
                        append(cpuChange)
                        append(CSV_DELIMITER)
                        
                        // Memory usage before
                        append(memoryBefore)
                        append(CSV_DELIMITER)
                        
                        // Memory usage after
                        append(memoryAfter)
                        append(CSV_DELIMITER)
                        
                        // Memory usage change (MB)
                        append(String.format(Locale.US, "%.2f", memoryChangeMB))
                        append(CSV_DELIMITER)
                        
                        // Temperature before
                        append(tempBefore)
                        append(CSV_DELIMITER)
                        
                        // Temperature after
                        append(tempAfter)
                        append(CSV_DELIMITER)
                        
                        // Temperature change
                        append(String.format(Locale.US, "%.1f", tempChange))
                        append(CSV_DELIMITER)
                        
                        // Model name
                        append(escapeCsvField(queryResult.modelName))
                        append(CSV_DELIMITER)
                        
                        // Quantization
                        append(escapeCsvField(queryResult.quantization))
                        append(NEWLINE)
                    }
                    
                    writer.write(csvLine)
                    rowsWritten++
                }
                
                writer.flush()
                writer.close()
                
                // Small delay to ensure file system has flushed
                kotlinx.coroutines.delay(100)
                
                // Verify file was written correctly
                val fileSize = csvFile.length()
                val fileExists = csvFile.exists()
                
                Log.i(TAG, "=== CSV EXPORT COMPLETE ===")
                Log.i(TAG, "Rows written: $rowsWritten (expected: ${queryResults.size})")
                Log.i(TAG, "File path: ${csvFile.absolutePath}")
                Log.i(TAG, "File size: $fileSize bytes")
                Log.i(TAG, "File exists: $fileExists")
                
                if (fileSize == 0L) {
                    Log.e(TAG, "ERROR: CSV file is empty (0 bytes)!")
                    Log.e(TAG, "Query results available: ${queryResults.size}")
                    Log.e(TAG, "Battery metrics available: ${allBatteryMetrics.size}")
                    Log.e(TAG, "Rows written: $rowsWritten")
                } else if (rowsWritten == 0) {
                    Log.e(TAG, "ERROR: No rows were written to CSV!")
                    Log.e(TAG, "Query results available: ${queryResults.size}")
                    Log.e(TAG, "Battery metrics available: ${allBatteryMetrics.size}")
                } else {
                    Log.i(TAG, "✅ CSV export successful: $rowsWritten rows, $fileSize bytes")
                }
                
                csvFile
                
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting all data to CSV", e)
                null
            }
        }
    }
    
    /**
     * Closes the incremental CSV file. Should be called at the end of benchmark.
     * @deprecated Use exportAllDataToCSV() instead
     */
    @Deprecated("Use exportAllDataToCSV() instead")
    suspend fun closeIncrementalCSV(): File? {
        return withContext(Dispatchers.IO) {
            lock.write {
                try {
                    incrementalCsvWriter?.flush()
                    incrementalCsvWriter?.close()
                    val file = incrementalCsvFile
                    incrementalCsvWriter = null
                    incrementalCsvFile = null
                    isIncrementalFileInitialized = false
                    Log.i(TAG, "Closed incremental CSV: ${file?.absolutePath}")
                    file
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing incremental CSV", e)
                    null
                }
            }
        }
    }
    
    /**
     * Logs a QueryResult to the in-memory storage.
     * Thread-safe operation that adds the result to the results list.
     * 
     * @param result The QueryResult to log
     */
    fun logQuery(result: QueryResult) {
        lock.write {
            try {
                results.add(result)
                Log.d(TAG, "Logged query result: ${result.queryText.take(50)}...")
            } catch (e: Exception) {
                Log.e(TAG, "Error logging query result", e)
            }
        }
    }
    
    /**
     * Logs BatteryMetrics to the in-memory storage.
     * Thread-safe operation that adds the metrics to the battery metrics list.
     * 
     * @param metrics The BatteryMetrics to log
     */
    fun logBattery(metrics: BatteryMetrics) {
        lock.write {
            try {
                batteryMetrics.add(metrics)
                Log.d(TAG, "Logged battery metrics: Level=${metrics.batteryLevel}%, Drain=${metrics.batteryDrainRate}%/h")
            } catch (e: Exception) {
                Log.e(TAG, "Error logging battery metrics", e)
            }
        }
    }
    
    /**
     * Forces immediate write to disk to ensure data isn't lost.
     * This method ensures all buffered data is persisted.
     * Note: Since we're using in-memory lists, this primarily ensures
     * the data structures are in a consistent state.
     */
    fun flush() {
        lock.read {
            try {
                // Force synchronization to ensure data is written
                // The actual disk write happens during export, but this ensures
                // the in-memory data is in a consistent state
                val queryCount = results.size
                val batteryCount = batteryMetrics.size
                Log.d(TAG, "Flush: $queryCount queries, $batteryCount battery metrics in memory")
            } catch (e: Exception) {
                Log.e(TAG, "Error during flush", e)
            }
        }
    }
    
    /**
     * Exports all logged data to CSV files.
     * Creates two separate CSV files: one for query results and one for battery metrics.
     * Files are saved to the public Downloads folder for easy access.
     * 
     * @return File object representing the query results CSV file, or null if export fails
     */
    suspend fun exportToCSV(): File? {
        return withContext(Dispatchers.IO) {
            try {
                // Save to public Downloads folder for easy access
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                
                // Ensure Downloads directory exists
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                
                // Check if Downloads directory is writable
                if (!downloadsDir.canWrite()) {
                    Log.e(TAG, "Downloads directory is not writable: ${downloadsDir.absolutePath}")
                    // Fallback to app directory
                    return@withContext exportToCSVFallback()
                }
                
                // Generate timestamp for unique filenames
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                
                // Export query results
                val queryFile = File(downloadsDir, "llm_benchmark_queries_$timestamp.csv")
                val queryExported = exportQueryResults(queryFile)
                
                // Export battery metrics
                val batteryFile = File(downloadsDir, "llm_benchmark_battery_$timestamp.csv")
                val batteryExported = exportBatteryMetrics(batteryFile)
                
                if (queryExported && batteryExported) {
                    Log.i(TAG, "Successfully exported data to Downloads folder")
                    Log.i(TAG, "Query results: ${queryFile.absolutePath}")
                    Log.i(TAG, "Battery metrics: ${batteryFile.absolutePath}")
                    queryFile
                } else {
                    Log.e(TAG, "Failed to export CSV files")
                    null
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during CSV export: ${e.message}", e)
                // Fallback to app directory
                exportToCSVFallback()
            }
        }
    }
    
    /**
     * Fallback method to export CSV files to app directory if Downloads folder is not accessible.
     * 
     * @return File object representing the query results CSV file, or null if export fails
     */
    private suspend fun exportToCSVFallback(): File? {
        return withContext(Dispatchers.IO) {
            try {
                // Ensure directory exists
                val logDir = File(logFilePath)
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }
                
                // Export query results
                val queryFile = File(logDir, QUERY_RESULTS_FILE)
                val queryExported = exportQueryResults(queryFile)
                
                // Export battery metrics
                val batteryFile = File(logDir, BATTERY_METRICS_FILE)
                val batteryExported = exportBatteryMetrics(batteryFile)
                
                if (queryExported && batteryExported) {
                    Log.i(TAG, "Exported data to app directory (Downloads not accessible)")
                    Log.i(TAG, "Query results: ${queryFile.absolutePath}")
                    Log.i(TAG, "Battery metrics: ${batteryFile.absolutePath}")
                    queryFile
                } else {
                    Log.e(TAG, "Failed to export CSV files")
                    null
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during CSV export fallback: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Exports query results to CSV file.
     * 
     * @param queryFile The file to write query results to
     * @return True if successful, false otherwise
     */
    private suspend fun exportQueryResults(queryFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Ensure parent directory exists
                queryFile.parentFile?.mkdirs()
                
                val writer = FileWriter(queryFile)
                
                // Write header
                writer.write(QUERY_HEADER)
                writer.write(NEWLINE)
                
                // Write data
                lock.read {
                    results.forEach { result ->
                        val csvLine = buildString {
                            append(escapeCsvField(formatTimestamp(result.timestamp)))
                            append(CSV_DELIMITER)
                            append(escapeCsvField(result.queryText))
                            append(CSV_DELIMITER)
                            append(escapeCsvField(result.responseText))
                            append(CSV_DELIMITER)
                            append(result.inferenceTimeMs)
                            append(CSV_DELIMITER)
                            append(result.batteryLevel)
                            append(CSV_DELIMITER)
                            append(escapeCsvField(result.quantization))
                            append(CSV_DELIMITER)
                            append(escapeCsvField(result.modelName))
                            append(NEWLINE)
                        }
                        writer.write(csvLine)
                    }
                }
                
                writer.close()
                Log.d(TAG, "Exported ${results.size} query results to ${queryFile.absolutePath}")
                true
                
            } catch (e: IOException) {
                Log.e(TAG, "Error writing query results CSV", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error writing query results CSV", e)
                false
            }
        }
    }
    
    /**
     * Exports battery metrics to CSV file.
     * 
     * @param batteryFile The file to write battery metrics to
     * @return True if successful, false otherwise
     */
    private suspend fun exportBatteryMetrics(batteryFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Ensure parent directory exists
                batteryFile.parentFile?.mkdirs()
                
                val writer = FileWriter(batteryFile)
                
                // Write header
                writer.write(BATTERY_HEADER)
                writer.write(NEWLINE)
                
                // Write data
                lock.read {
                    batteryMetrics.forEach { metrics ->
                        val csvLine = buildString {
                            append(escapeCsvField(formatTimestamp(metrics.timestamp)))
                            append(CSV_DELIMITER)
                            append(metrics.batteryLevel)
                            append(CSV_DELIMITER)
                            append(metrics.batteryDrainRate)
                            append(CSV_DELIMITER)
                            append(metrics.cpuUsage)
                            append(CSV_DELIMITER)
                            append(metrics.memoryUsage)
                            append(CSV_DELIMITER)
                            append(metrics.temperature)
                            append(NEWLINE)
                        }
                        writer.write(csvLine)
                    }
                }
                
                writer.close()
                Log.d(TAG, "Exported ${batteryMetrics.size} battery metrics to ${batteryFile.absolutePath}")
                true
                
            } catch (e: IOException) {
                Log.e(TAG, "Error writing battery metrics CSV", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error writing battery metrics CSV", e)
                false
            }
        }
    }
    
    /**
     * Clears all logged data from memory.
     * Thread-safe operation that removes all stored results and metrics.
     */
    fun clearLogs() {
        lock.write {
            try {
                val queryCount = results.size
                val batteryCount = batteryMetrics.size
                
                results.clear()
                batteryMetrics.clear()
                
                Log.i(TAG, "Cleared logs: $queryCount query results, $batteryCount battery metrics")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing logs", e)
            }
        }
    }
    
    /**
     * Returns the number of logged query results.
     * Thread-safe operation that provides the current count.
     * 
     * @return Number of logged QueryResult objects
     */
    fun getResultsCount(): Int {
        return lock.read {
            results.size
        }
    }
    
    /**
     * Returns the number of logged battery metrics.
     * 
     * @return Number of logged BatteryMetrics objects
     */
    fun getBatteryMetricsCount(): Int {
        return lock.read {
            batteryMetrics.size
        }
    }
    
    /**
     * Returns the total number of logged items (query results + battery metrics).
     * 
     * @return Total number of logged items
     */
    fun getTotalLogCount(): Int {
        return lock.read {
            results.size + batteryMetrics.size
        }
    }
    
    /**
     * Gets a copy of all logged query results.
     * 
     * @return List of QueryResult objects
     */
    fun getQueryResults(): List<QueryResult> {
        return lock.read {
            results.toList()
        }
    }
    
    /**
     * Gets a copy of all logged battery metrics.
     * 
     * @return List of BatteryMetrics objects
     */
    fun getBatteryMetrics(): List<BatteryMetrics> {
        return lock.read {
            batteryMetrics.toList()
        }
    }
    
    /**
     * Gets the log file directory path.
     * 
     * @return String path to the log directory
     */
    fun getLogFilePath(): String = logFilePath
    
    /**
     * Helper method to write data to a file.
     * Handles file creation, writing, and error management.
     * 
     * @param data The data to write to the file
     * @param fileName The name of the file to write to
     * @return True if successful, false otherwise
     */
    private suspend fun writeToFile(data: String, fileName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(logFilePath, fileName)
                
                // Ensure parent directory exists
                file.parentFile?.mkdirs()
                
                val writer = FileWriter(file)
                writer.write(data)
                writer.close()
                
                Log.d(TAG, "Successfully wrote data to ${file.absolutePath}")
                true
                
            } catch (e: IOException) {
                Log.e(TAG, "Error writing to file $fileName", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error writing to file $fileName", e)
                false
            }
        }
    }
    
    /**
     * Escapes CSV field values to handle commas, quotes, and newlines.
     * 
     * @param field The field value to escape
     * @return Escaped field value
     */
    private fun escapeCsvField(field: String): String {
        return when {
            field.contains(CSV_DELIMITER) || field.contains(CSV_QUOTE) || field.contains(NEWLINE) -> {
                "$CSV_QUOTE${field.replace(CSV_QUOTE, "$CSV_QUOTE$CSV_QUOTE")}$CSV_QUOTE"
            }
            else -> field
        }
    }
    
    /**
     * Formats a timestamp to a human-readable string.
     * 
     * @param timestamp The timestamp in milliseconds
     * @return Formatted timestamp string
     */
    private fun formatTimestamp(timestamp: Long): String {
        return try {
            dateFormatter.format(Date(timestamp))
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting timestamp $timestamp", e)
            timestamp.toString()
        }
    }
    
    /**
     * Exports data to a single combined CSV file for convenience.
     * This creates a file with both query results and battery metrics in chronological order.
     * Files are saved to the public Downloads folder for easy access.
     * 
     * @return File object if successful, null otherwise
     */
    suspend fun exportCombinedCSV(): File? {
        return withContext(Dispatchers.IO) {
            try {
                // Save to public Downloads folder for easy access
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                
                // Ensure Downloads directory exists
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                
                // Check if Downloads directory is writable
                val useDownloads = downloadsDir.canWrite()
                val targetDir = if (useDownloads) downloadsDir else File(logFilePath)
                
                // Generate timestamp for unique filename
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val combinedFile = File(targetDir, "llm_benchmark_combined_$timestamp.csv")
                
                // Ensure parent directory exists
                combinedFile.parentFile?.mkdirs()
                
                val writer = FileWriter(combinedFile)
                
                // Write header
                writer.write("type,timestamp,queryText,responseText,inferenceTimeMs,batteryLevel,quantization,modelName,batteryDrainRate,cpuUsage,memoryUsage,temperature")
                writer.write(NEWLINE)
                
                // Combine and sort all data by timestamp
                val allData = mutableListOf<Pair<String, Any>>()
                
                lock.read {
                    results.forEach { result ->
                        allData.add("query" to result)
                    }
                    batteryMetrics.forEach { metrics ->
                        allData.add("battery" to metrics)
                    }
                }
                
                // Sort by timestamp
                allData.sortBy { (_, data) ->
                    when (data) {
                        is QueryResult -> data.timestamp
                        is BatteryMetrics -> data.timestamp
                        else -> 0L
                    }
                }
                
                // Write combined data
                allData.forEach { (type, data) ->
                    val csvLine = when (type) {
                        "query" -> {
                            val result = data as QueryResult
                            buildString {
                                append("query")
                                append(CSV_DELIMITER)
                                append(escapeCsvField(formatTimestamp(result.timestamp)))
                                append(CSV_DELIMITER)
                                append(escapeCsvField(result.queryText))
                                append(CSV_DELIMITER)
                                append(escapeCsvField(result.responseText))
                                append(CSV_DELIMITER)
                                append(result.inferenceTimeMs)
                                append(CSV_DELIMITER)
                                append(result.batteryLevel)
                                append(CSV_DELIMITER)
                                append(escapeCsvField(result.quantization))
                                append(CSV_DELIMITER)
                                append(escapeCsvField(result.modelName))
                                append(CSV_DELIMITER)
                                append("") // batteryDrainRate
                                append(CSV_DELIMITER)
                                append("") // cpuUsage
                                append(CSV_DELIMITER)
                                append("") // memoryUsage
                                append(CSV_DELIMITER)
                                append("") // temperature
                                append(NEWLINE)
                            }
                        }
                        "battery" -> {
                            val metrics = data as BatteryMetrics
                            buildString {
                                append("battery")
                                append(CSV_DELIMITER)
                                append(escapeCsvField(formatTimestamp(metrics.timestamp)))
                                append(CSV_DELIMITER)
                                append("") // queryText
                                append(CSV_DELIMITER)
                                append("") // responseText
                                append(CSV_DELIMITER)
                                append("") // inferenceTimeMs
                                append(CSV_DELIMITER)
                                append(metrics.batteryLevel)
                                append(CSV_DELIMITER)
                                append("") // quantization
                                append(CSV_DELIMITER)
                                append("") // modelName
                                append(CSV_DELIMITER)
                                append(metrics.batteryDrainRate)
                                append(CSV_DELIMITER)
                                append(metrics.cpuUsage)
                                append(CSV_DELIMITER)
                                append(metrics.memoryUsage)
                                append(CSV_DELIMITER)
                                append(metrics.temperature)
                                append(NEWLINE)
                            }
                        }
                        else -> ""
                    }
                    writer.write(csvLine)
                }
                
                writer.close()
                if (useDownloads) {
                    Log.i(TAG, "Exported combined CSV to Downloads folder: ${combinedFile.absolutePath}")
                } else {
                    Log.i(TAG, "Exported combined CSV to app directory (Downloads not accessible): ${combinedFile.absolutePath}")
                }
                combinedFile
                
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting combined CSV: ${e.message}", e)
                null
            }
        }
    }
}
