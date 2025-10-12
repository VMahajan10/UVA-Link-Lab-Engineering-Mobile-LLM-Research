package com.research.llmbattery

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.work.*
import com.research.llmbattery.models.QueryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * QueryScheduler class responsible for scheduling and executing periodic LLM queries
 * using Android WorkManager. Manages a predefined set of test queries and executes
 * them at specified intervals while monitoring battery consumption and logging results.
 * 
 * Features:
 * - Periodic query execution using WorkManager
 * - Predefined diverse test queries for comprehensive testing
 * - Battery and system metrics logging after each query
 * - Work constraints to prevent execution during low battery or power save mode
 * - Error handling and retry logic
 * - Cycle through queries for continuous testing
 */
class QueryScheduler(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "QueryScheduler"
        
        // Work names and tags
        const val WORK_NAME_1MIN = "query_scheduler_1min"
        const val WORK_NAME_5MIN = "query_scheduler_5min"
        const val WORK_TAG_QUERIES = "llm_queries"
        const val WORK_TAG_BENCHMARK = "battery_benchmark"
        
        // Input data keys
        private const val KEY_QUERY_INTERVAL = "query_interval"
        private const val KEY_QUERY_INDEX = "query_index"
        
        // Predefined test queries for comprehensive LLM testing
        private val TEST_QUERIES = listOf(
            "What is machine learning and how does it work?",
            "Explain quantum computing in simple terms",
            "Write a haiku about artificial intelligence",
            "What are the benefits of renewable energy?",
            "Describe the process of photosynthesis",
            "What is the difference between AI and machine learning?",
            "Explain the concept of blockchain technology",
            "Write a short story about a robot learning to paint",
            "What are the ethical implications of artificial intelligence?",
            "Describe the water cycle in detail",
            "What is the theory of relativity?",
            "Explain how neural networks work",
            "Write a poem about the future of technology",
            "What are the main causes of climate change?",
            "Describe the structure of DNA",
            "What is the difference between supervised and unsupervised learning?",
            "Explain the concept of sustainable development",
            "Write a dialogue between a human and an AI assistant",
            "What are the potential risks of artificial general intelligence?",
            "Describe the process of cellular respiration"
        )
        
        // Work constraints
        private fun getWorkConstraints(): Constraints {
            return Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .build()
        }
        
        /**
         * Schedules periodic query execution with the specified interval.
         * 
         * @param context Application context
         * @param intervalMinutes Interval between queries in minutes (1 or 5)
         * @param llmService LLMService instance for query execution
         * @param dataLogger DataLogger instance for result logging
         * @param batteryMonitor BatteryMonitor instance for metrics logging
         */
        fun scheduleQueries(
            context: Context,
            intervalMinutes: Int,
            llmService: LLMService,
            dataLogger: DataLogger,
            batteryMonitor: BatteryMonitor
        ) {
            val workManager = WorkManager.getInstance(context)
            
            // Cancel existing work first
            workManager.cancelAllWorkByTag(WORK_TAG_QUERIES)
            
            val workName = if (intervalMinutes == 1) WORK_NAME_1MIN else WORK_NAME_5MIN
            val interval = if (intervalMinutes == 1) 1L else 5L
            
            val inputData = Data.Builder()
                .putLong(KEY_QUERY_INTERVAL, intervalMinutes * 60 * 1000L) // Convert to milliseconds
                .putInt(KEY_QUERY_INDEX, 0)
                .build()
            
            val periodicWorkRequest = PeriodicWorkRequestBuilder<QueryScheduler>(
                interval, TimeUnit.MINUTES
            )
                .setInputData(inputData)
                .setConstraints(getWorkConstraints())
                .addTag(WORK_TAG_QUERIES)
                .addTag(WORK_TAG_BENCHMARK)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            
            workManager.enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.REPLACE,
                periodicWorkRequest
            )
            
            Log.i(TAG, "Scheduled periodic queries every $intervalMinutes minute(s)")
        }
        
        /**
         * Cancels all scheduled query work.
         * 
         * @param context Application context
         */
        fun cancelSchedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelAllWorkByTag(WORK_TAG_QUERIES)
            Log.i(TAG, "Cancelled all scheduled query work")
        }
        
        /**
         * Gets the predefined test queries.
         * 
         * @return List of test query strings
         */
        fun getTestQueries(): List<String> = TEST_QUERIES
    }
    
    // Properties
    private var queryInterval: Long = 0L
    private val testQueries: List<String> = TEST_QUERIES
    private var currentQueryIndex: Int = 0
    private lateinit var llmService: LLMService
    private lateinit var dataLogger: DataLogger
    private lateinit var batteryMonitor: BatteryMonitor
    
    /**
     * Initializes the QueryScheduler with required dependencies.
     * This method should be called before scheduling work.
     * 
     * @param llmService LLMService instance
     * @param dataLogger DataLogger instance
     * @param batteryMonitor BatteryMonitor instance
     */
    fun initialize(
        llmService: LLMService,
        dataLogger: DataLogger,
        batteryMonitor: BatteryMonitor
    ) {
        this.llmService = llmService
        this.dataLogger = dataLogger
        this.batteryMonitor = batteryMonitor
    }
    
    /**
     * WorkManager callback that executes the scheduled query work.
     * This method is called by WorkManager at the scheduled intervals.
     * 
     * @return Result indicating success or failure
     */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Extract input data
            queryInterval = inputData.getLong(KEY_QUERY_INTERVAL, 60000L) // Default 1 minute
            currentQueryIndex = inputData.getInt(KEY_QUERY_INDEX, 0)
            
            // Check if LLM service is available
            if (!::llmService.isInitialized) {
                Log.e(TAG, "LLMService not initialized")
                return@withContext Result.failure()
            }
            
            // Check battery level before executing query
            if (!isBatteryLevelAcceptable()) {
                Log.w(TAG, "Battery level too low, skipping query execution")
                return@withContext Result.retry()
            }
            
            // Check if device is in power save mode
            if (isPowerSaveMode()) {
                Log.w(TAG, "Device in power save mode, skipping query execution")
                return@withContext Result.retry()
            }
            
            // Execute the query
            val queryResult = executeQuery()
            if (queryResult != null) {
                // Log query result
                dataLogger.logQuery(queryResult)
                
                // Log battery metrics
                val batteryMetrics = batteryMonitor.logMetrics()
                dataLogger.logBattery(batteryMetrics)
                
                Log.i(TAG, "Successfully executed query: ${queryResult.queryText.take(50)}...")
                Result.success()
            } else {
                Log.e(TAG, "Failed to execute query")
                Result.retry()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in doWork", e)
            Result.retry()
        }
    }
    
    /**
     * Executes a single query and returns the result.
     * Gets the next query from the test queries list and processes it through the LLM service.
     * 
     * @return QueryResult object with query details and performance metrics, or null if failed
     */
    suspend fun executeQuery(): QueryResult? = withContext(Dispatchers.IO) {
        try {
            val queryText = getNextQuery()
            val startTime = System.currentTimeMillis()
            val batteryLevel = batteryMonitor.getCurrentBatteryLevel()
            
            // Generate response using LLM service
            val responseText = llmService.generateResponse(queryText)
            val endTime = System.currentTimeMillis()
            val inferenceTimeMs = endTime - startTime
            
            // Create QueryResult
            QueryResult.createNow(
                queryText = queryText,
                responseText = responseText,
                inferenceTimeMs = inferenceTimeMs,
                batteryLevel = batteryLevel,
                quantization = llmService.getQuantizationType(),
                modelName = llmService.getModelName() ?: "unknown"
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error executing query", e)
            null
        }
    }
    
    /**
     * Gets the next query from the test queries list.
     * Cycles through the list, starting from the beginning when reaching the end.
     * 
     * @return Next query string
     */
    fun getNextQuery(): String {
        val query = testQueries[currentQueryIndex]
        currentQueryIndex = (currentQueryIndex + 1) % testQueries.size
        return query
    }
    
    /**
     * Checks if the current battery level is acceptable for query execution.
     * Prevents execution when battery is critically low.
     * 
     * @return True if battery level is acceptable, false otherwise
     */
    private fun isBatteryLevelAcceptable(): Boolean {
        return try {
            val batteryLevel = batteryMonitor.getCurrentBatteryLevel()
            batteryLevel > 15 // Require at least 15% battery
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery level", e)
            true // Allow execution if we can't check battery level
        }
    }
    
    /**
     * Checks if the device is in power save mode.
     * Prevents execution during power save mode to avoid interference.
     * 
     * @return True if device is in power save mode, false otherwise
     */
    private fun isPowerSaveMode(): Boolean {
        return try {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            powerManager.isPowerSaveMode
        } catch (e: Exception) {
            Log.e(TAG, "Error checking power save mode", e)
            false // Allow execution if we can't check power save mode
        }
    }
    
    /**
     * Gets the current query index.
     * 
     * @return Current query index
     */
    fun getCurrentQueryIndex(): Int = currentQueryIndex
    
    /**
     * Gets the total number of test queries.
     * 
     * @return Number of test queries
     */
    fun getTestQueryCount(): Int = testQueries.size
    
    /**
     * Gets the current query interval in milliseconds.
     * 
     * @return Query interval in milliseconds
     */
    fun getQueryInterval(): Long = queryInterval
    
    /**
     * Resets the query index to start from the beginning.
     */
    fun resetQueryIndex() {
        currentQueryIndex = 0
        Log.d(TAG, "Reset query index to 0")
    }
    
    /**
     * Gets a specific query by index.
     * 
     * @param index Index of the query to retrieve
     * @return Query string at the specified index, or null if index is invalid
     */
    fun getQueryByIndex(index: Int): String? {
        return if (index in 0 until testQueries.size) {
            testQueries[index]
        } else {
            null
        }
    }
    
    /**
     * Executes a specific query by index for testing purposes.
     * 
     * @param queryIndex Index of the query to execute
     * @return QueryResult object, or null if execution failed
     */
    suspend fun executeQueryByIndex(queryIndex: Int): QueryResult? = withContext(Dispatchers.IO) {
        try {
            val query = getQueryByIndex(queryIndex)
            if (query != null) {
                val startTime = System.currentTimeMillis()
                val batteryLevel = batteryMonitor.getCurrentBatteryLevel()
                
                val responseText = llmService.generateResponse(query)
                val endTime = System.currentTimeMillis()
                val inferenceTimeMs = endTime - startTime
                
                QueryResult.createNow(
                    queryText = query,
                    responseText = responseText,
                    inferenceTimeMs = inferenceTimeMs,
                    batteryLevel = batteryLevel,
                    quantization = llmService.getQuantizationType(),
                    modelName = llmService.getModelName() ?: "unknown"
                )
            } else {
                Log.e(TAG, "Invalid query index: $queryIndex")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing query by index", e)
            null
        }
    }
    
    /**
     * Executes all test queries sequentially for comprehensive testing.
     * This method is useful for running a complete test suite.
     * 
     * @return List of QueryResult objects for all executed queries
     */
    suspend fun executeAllQueries(): List<QueryResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<QueryResult>()
        
        try {
            Log.i(TAG, "Starting execution of all ${testQueries.size} test queries")
            
            for (i in testQueries.indices) {
                val result = executeQueryByIndex(i)
                if (result != null) {
                    results.add(result)
                    dataLogger.logQuery(result)
                    
                    // Log battery metrics after each query
                    val batteryMetrics = batteryMonitor.logMetrics()
                    dataLogger.logBattery(batteryMetrics)
                    
                    Log.d(TAG, "Completed query ${i + 1}/${testQueries.size}")
                    
                    // Small delay between queries to prevent overwhelming the system
                    delay(1000)
                } else {
                    Log.w(TAG, "Failed to execute query ${i + 1}/${testQueries.size}")
                }
            }
            
            Log.i(TAG, "Completed execution of all test queries. Success: ${results.size}/${testQueries.size}")
            results
            
        } catch (e: Exception) {
            Log.e(TAG, "Error executing all queries", e)
            results
        }
    }
}
