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
     * Exports all logged data to CSV files.
     * Creates two separate CSV files: one for query results and one for battery metrics.
     * Files are saved to external storage with proper formatting and headers.
     * 
     * @return File object representing the query results CSV file, or null if export fails
     */
    suspend fun exportToCSV(): File? {
        return withContext(Dispatchers.IO) {
            try {
                // Ensure directory exists
                val logDir = File(logFilePath)
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }
                
                // Export query results
                val queryFile = exportQueryResults()
                
                // Export battery metrics
                val batteryFile = exportBatteryMetrics()
                
                if (queryFile != null && batteryFile != null) {
                    Log.i(TAG, "Successfully exported data to CSV files:")
                    Log.i(TAG, "Query results: ${queryFile.absolutePath}")
                    Log.i(TAG, "Battery metrics: ${batteryFile.absolutePath}")
                    queryFile
                } else {
                    Log.e(TAG, "Failed to export CSV files")
                    null
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during CSV export", e)
                null
            }
        }
    }
    
    /**
     * Exports query results to CSV file.
     * 
     * @return File object if successful, null otherwise
     */
    private suspend fun exportQueryResults(): File? {
        return withContext(Dispatchers.IO) {
            try {
                val queryFile = File(logFilePath, QUERY_RESULTS_FILE)
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
                queryFile
                
            } catch (e: IOException) {
                Log.e(TAG, "Error writing query results CSV", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error writing query results CSV", e)
                null
            }
        }
    }
    
    /**
     * Exports battery metrics to CSV file.
     * 
     * @return File object if successful, null otherwise
     */
    private suspend fun exportBatteryMetrics(): File? {
        return withContext(Dispatchers.IO) {
            try {
                val batteryFile = File(logFilePath, BATTERY_METRICS_FILE)
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
                batteryFile
                
            } catch (e: IOException) {
                Log.e(TAG, "Error writing battery metrics CSV", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error writing battery metrics CSV", e)
                null
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
     * 
     * @return File object if successful, null otherwise
     */
    suspend fun exportCombinedCSV(): File? {
        return withContext(Dispatchers.IO) {
            try {
                val combinedFile = File(logFilePath, "combined_results.csv")
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
                Log.i(TAG, "Exported combined CSV to ${combinedFile.absolutePath}")
                combinedFile
                
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting combined CSV", e)
                null
            }
        }
    }
}
