package com.research.llmbattery

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.research.llmbattery.models.QueryResult
import kotlinx.coroutines.*
import java.io.File
import java.util.Date

class BenchmarkService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    
    private lateinit var llmService: LLMService
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var dataLogger: DataLogger
    
    private val testQueries = listOf(
        // Science & Technology
        "What is machine learning?",
        "Explain quantum computing simply.",
        "How does photosynthesis work?",
        "What is DNA?",
        "Explain relativity theory.",
        "Explain gravity simply.",
        "How do computers work?",
        "How does internet work?",
        "How do batteries work?",
        "What is the scientific method?",
        "How do vaccines work?",
        "What causes seasons?",
        "Describe the water cycle.",
        "What is evolution?",
        "How does the human brain work?",
        "What is the difference between weather and climate?",
        "Explain how solar panels generate electricity.",
        "What is artificial intelligence?",
        "How do satellites stay in orbit?",
        "What is the greenhouse effect?",
        
        // Environment & Energy
        "What are renewable energy benefits?",
        "What is climate change?",
        "What is biodiversity?",
        "How does recycling help the environment?",
        "What causes air pollution?",
        "Explain the carbon cycle.",
        "What are fossil fuels?",
        "How does deforestation affect ecosystems?",
        "What is sustainable development?",
        "How do wind turbines generate power?",
        
        // Geography & History
        "What is the capital of France?",
        "What is the largest ocean?",
        "How many continents are there?",
        "What is the longest river in the world?",
        "Explain the difference between a country and a continent.",
        "What caused World War I?",
        "Who invented the telephone?",
        "What is the Great Wall of China?",
        "How did the Industrial Revolution change society?",
        "What is the significance of the Renaissance?",
        
        // Social Sciences
        "What is democracy?",
        "What is the difference between capitalism and socialism?",
        "How does the economy work?",
        "What is the purpose of government?",
        "Explain the concept of human rights.",
        "What is cultural diversity?",
        "How do societies organize themselves?",
        "What is the role of education in society?",
        "Explain the concept of justice.",
        "What is globalization?",
        
        // Arts & Literature
        "Write a haiku about AI.",
        "What are primary colors?",
        "What is the difference between a novel and a short story?",
        "Explain the concept of perspective in art.",
        "What is poetry?",
        "How does music affect emotions?",
        "What is the purpose of storytelling?",
        "Explain the color wheel.",
        "What is the difference between modern and contemporary art?",
        "How do films tell stories?",
        
        // Mathematics & Logic
        "What is the Pythagorean theorem?",
        "Explain the concept of infinity.",
        "What is probability?",
        "How do you calculate percentages?",
        "What is the Fibonacci sequence?",
        "Explain the concept of zero.",
        "What is algebra?",
        "How does geometry relate to the real world?",
        "What is calculus used for?",
        "Explain the concept of prime numbers.",
        
        // Health & Medicine
        "How does the immune system work?",
        "What is the importance of exercise?",
        "Explain how the heart pumps blood.",
        "What is nutrition?",
        "How do antibiotics work?",
        "What is mental health?",
        "Explain the importance of sleep.",
        "What is the difference between a virus and bacteria?",
        "How does the digestive system work?",
        "What is the role of vitamins in the body?",
        
        // Philosophy & Ethics
        "What is the meaning of life?",
        "Explain the concept of free will.",
        "What is ethics?",
        "What is the difference between right and wrong?",
        "Explain the concept of truth.",
        "What is consciousness?",
        "How do we know what we know?",
        "What is the purpose of philosophy?",
        "Explain the concept of morality.",
        "What is the nature of reality?",
        
        // Practical & Everyday
        "How do I learn a new language?",
        "What are good study habits?",
        "How do I manage my time effectively?",
        "What is the importance of communication?",
        "How do I solve problems?",
        "What makes a good leader?",
        "How do I build good relationships?",
        "What is critical thinking?",
        "How do I make good decisions?",
        "What is the importance of curiosity?"
    )
    
    private var currentQueryIndex = 0
    private var isRunning = false
    private var queriesCompleted = 0
    private var startTimeMs = 0L
    private var endTimeMs = 0L
    private var totalQueries = 0
    private var originalDurationMinutes = 0.0
    private val CRITICAL_BATTERY_LEVEL = 5 // Export CSV when battery drops to 5%
    
    companion object {
        private const val TAG = "BenchmarkService"
        const val CHANNEL_ID = "llm_benchmark_channel"
        const val CHANNEL_ID_COMPLETION = "llm_benchmark_completion_channel"
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_ID_COMPLETION = 2
        
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_QUANTIZATION = "quantization"
        const val EXTRA_INTERVAL_MINUTES = "interval_minutes"
        const val EXTRA_DURATION_HOURS = "duration_hours"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"  // More precise than hours
        
        const val ACTION_START = "START_BENCHMARK"
        const val ACTION_STOP = "STOP_BENCHMARK"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "!!! SERVICE CREATED at ${Date()}")
        
        // Create notification channels
        createNotificationChannel()
        createCompletionNotificationChannel()
        
        // Request battery optimization exemption (Android 6.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                startActivity(intent)
                Log.i(TAG, "Battery optimization exemption requested")
            } catch (e: Exception) {
                Log.e(TAG, "Could not request battery optimization exemption", e)
            }
        }
        
        // Initialize wake lock (will be acquired when benchmark starts)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LLMBenchmark::WakeLock"
        ).apply {
            setReferenceCounted(false) // Don't count references, just hold it
        }
        Log.i(TAG, "WakeLock initialized")
        
        // Initialize components
        llmService = LLMService(this)
        batteryMonitor = BatteryMonitor(this)
        dataLogger = DataLogger(this)
        Log.i(TAG, "All components initialized")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e(TAG, "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        Log.e(TAG, "SERVICE onStartCommand CALLED")
        Log.e(TAG, "Action: ${intent?.action}")
        Log.e(TAG, "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        
        // Show toast to user (works even if service doesn't start)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "BenchmarkService starting!", Toast.LENGTH_LONG).show()
        }
        
        Log.i(TAG, "=== onStartCommand ===")
        Log.i(TAG, "Action: ${intent?.action}")
        Log.i(TAG, "Flags: $flags, StartId: $startId")
        Log.i(TAG, "isRunning: $isRunning")
        
        if (intent == null) {
            Log.w(TAG, "onStartCommand called with null intent - returning START_STICKY")
            return START_STICKY
        }
        
        when (intent.action) {
            ACTION_START -> {
                Log.e(TAG, "ACTION_START received")
                
                val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
                val quantization = intent.getStringExtra(EXTRA_QUANTIZATION)
                val intervalMinutes = intent.getIntExtra(EXTRA_INTERVAL_MINUTES, 5)
                
                // Support both minutes (preferred) and hours (for backward compatibility)
                val durationHours: Int
                val durationMinutes: Double
                
                if (intent.hasExtra(EXTRA_DURATION_MINUTES)) {
                    durationMinutes = intent.getDoubleExtra(EXTRA_DURATION_MINUTES, 60.0)  // Default 60 minutes
                    durationHours = (durationMinutes / 60.0).toInt()
                } else {
                    // Fallback to hours if minutes not provided
                    durationHours = intent.getIntExtra(EXTRA_DURATION_HOURS, 4)
                    durationMinutes = durationHours * 60.0
                }
                
                Log.e(TAG, "Model path: $modelPath")
                Log.e(TAG, "Quantization: $quantization")
                Log.e(TAG, "Interval: $intervalMinutes min")
                Log.e(TAG, "Duration: $durationHours hours (${durationMinutes} minutes)")
                
                if (modelPath.isNullOrEmpty()) {
                    Log.e(TAG, "Model path is empty! Cannot start benchmark.")
                    // Show error notification instead of silently failing
                    val errorNotification = NotificationCompat.Builder(this@BenchmarkService, CHANNEL_ID)
                        .setContentTitle("LLM Benchmark Error")
                        .setContentText("Please select a model in the app")
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()
                    startForeground(NOTIFICATION_ID, errorNotification)
                    return START_STICKY // Keep service alive to show error
                }
                
                Log.e(TAG, "Calling startBenchmark()...")
                startBenchmark(modelPath, quantization ?: "unknown", intervalMinutes, durationMinutes)
                Log.e(TAG, "startBenchmark() called (may run async)")
            }
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP received")
                stopBenchmark()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
            }
        }
        
        return START_STICKY
    }
    
    private fun startBenchmark(
        modelPath: String,
        quantization: String,
        intervalMinutes: Int,
        durationMinutes: Double
    ) {
        Log.e(TAG, "========================================")
        Log.e(TAG, "startBenchmark() ENTERED")
        Log.e(TAG, "isRunning: $isRunning")
        Log.e(TAG, "modelPath: $modelPath")
        Log.e(TAG, "quantization: $quantization")
        Log.e(TAG, "intervalMinutes: $intervalMinutes")
        Log.e(TAG, "durationMinutes: $durationMinutes")
        Log.e(TAG, "========================================")
        
        if (isRunning) {
            Log.e(TAG, "Already running, returning")
            return
        }
        
        Log.e(TAG, "Creating notification...")
        val notification = createNotification("Starting benchmark...")
        
        Log.e(TAG, "Starting foreground service...")
        startForeground(NOTIFICATION_ID, notification)
        Log.e(TAG, "Foreground service started")
        
        // Acquire wake lock to keep CPU running even when screen is off
        Log.e(TAG, "Checking WakeLock...")
        if (wakeLock?.isHeld != true) {
            Log.e(TAG, "Acquiring WakeLock...")
            wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours max
            Log.e(TAG, "WakeLock acquired (10 hours) - service will run even when screen is off")
        } else {
            Log.e(TAG, "WakeLock already held")
        }
        
        isRunning = true
        Log.e(TAG, "Set isRunning = true")
        
        Log.e(TAG, "Launching coroutine...")
        serviceScope.launch {
            Log.e(TAG, "INSIDE COROUTINE - Starting benchmark logic")
            try {
                Log.e(TAG, "Loading model: $modelPath")
                updateNotification("Loading model...")
                Log.e(TAG, "Notification updated to 'Loading model...'")
                
                Log.e(TAG, "Calling llmService.loadModel()...")
                val loaded = llmService.loadModel(modelPath)
                Log.e(TAG, "llmService.loadModel() returned: $loaded")
                
                if (!loaded) {
                    Log.e(TAG, "Failed to load model: $modelPath")
                    updateNotification("Model loading failed: ${modelPath.substringAfterLast("/")}")
                    
                    // Show error notification
                    Log.e(TAG, "Creating error notification...")
                    val notificationManager = getSystemService(NotificationManager::class.java)
                    val errorNotification = NotificationCompat.Builder(this@BenchmarkService, CHANNEL_ID_COMPLETION)
                        .setContentTitle("❌ Benchmark Failed")
                        .setContentText("Failed to load model: ${modelPath.substringAfterLast("/")}")
                        .setStyle(NotificationCompat.BigTextStyle()
                            .bigText("The model file was not found.\n\n" +
                                    "Please ensure the model file is in your Downloads folder:\n" +
                                    "${modelPath.substringAfterLast("/")}\n\n" +
                                    "The service will now stop."))
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build()
                    notificationManager.notify(NOTIFICATION_ID_COMPLETION, errorNotification)
                    Log.e(TAG, "Error notification shown, waiting 3 seconds...")
                    
                    delay(3000) // Show error for 3 seconds
                    Log.e(TAG, "Calling stopBenchmark()...")
                    stopBenchmark()
                    Log.e(TAG, "Returning from coroutine (model load failed)")
                    return@launch
                }
                
                Log.e(TAG, "Model loaded successfully, starting benchmark")
                Log.e(TAG, "Starting battery monitoring...")
                batteryMonitor.startMonitoring()
                Log.e(TAG, "Battery monitoring started")
                
                isRunning = true
                Log.e(TAG, "Set isRunning = true (inside coroutine)")
                
                // Convert minutes to milliseconds
                Log.e(TAG, "Calculating benchmark timing...")
                originalDurationMinutes = durationMinutes // Store original duration for metadata
                startTimeMs = System.currentTimeMillis()
                val durationMs = (durationMinutes * 60 * 1000L).toLong()
                endTimeMs = startTimeMs + durationMs
                
                // Calculate how many queries will run
                val intervalMs = intervalMinutes * 60 * 1000L
                totalQueries = ((endTimeMs - startTimeMs) / intervalMs).toInt()
                
                val durationHours = durationMinutes / 60.0
                
                Log.e(TAG, "=== BENCHMARK CONFIGURATION ===")
                Log.e(TAG, "Start time: ${Date(startTimeMs)}")
                Log.e(TAG, "End time: ${Date(endTimeMs)}")
                Log.e(TAG, "Total duration: $durationHours hours")
                Log.e(TAG, "Query interval: $intervalMinutes minutes")
                Log.e(TAG, "Expected queries: $totalQueries")
                Log.e(TAG, "==============================")
                
                // Don't create CSV file yet - will create at end with all data
                Log.e(TAG, "Data will be collected in memory and exported to CSV at end of benchmark")
                
                // Send initial broadcast with full duration
                Log.e(TAG, "Sending initial broadcast update...")
                Log.e(TAG, "endTimeMs: $endTimeMs, currentTime: ${System.currentTimeMillis()}, remaining: ${getTimeRemainingMs()}ms")
                broadcastUpdate()
                Log.e(TAG, "Initial broadcast sent")
                
                // Send multiple broadcasts at start to ensure UI receives it
                // (UI might switch to countdown view after first broadcast)
                delay(300)
                broadcastUpdate()
                delay(300)
                broadcastUpdate()
                delay(500)
                broadcastUpdate()
                
                Log.e(TAG, "Entering query loop...")
                while (isRunning && System.currentTimeMillis() < endTimeMs) {
                    Log.e(TAG, "--- Query loop iteration ---")
                    val query = getNextQuery()
                    
                    // Get battery metrics BEFORE query
                    val batteryBefore = batteryMonitor.logMetrics()
                    
                    // Comprehensive logging before EVERY query
                    Log.i(TAG, "=== QUERY ${queriesCompleted + 1} START ===")
                    Log.i(TAG, "Wall clock time: ${Date()}")
                    Log.i(TAG, "Elapsed time: ${(System.currentTimeMillis() - startTimeMs) / 1000 / 60} min")
                    Log.i(TAG, "Model loaded: ${llmService.isModelLoaded}")
                    Log.i(TAG, "Battery: ${batteryMonitor.getCurrentBatteryLevel()}%")
                    Log.i(TAG, "Question: $query")
                    Log.i(TAG, "Query ${queriesCompleted + 1}/$totalQueries")
                    Log.i(TAG, "Time remaining: ${getTimeRemaining()}")
                    
                    updateNotification("Query ${queriesCompleted + 1}/$totalQueries - Battery: ${batteryMonitor.getCurrentBatteryLevel()}%")
                    
                    val formattedPrompt = """<|im_start|>system
You are a helpful assistant.<|im_end|>
<|im_start|>user
$query<|im_end|>
<|im_start|>assistant
"""
                    
                    val queryStartTime = System.currentTimeMillis()
                    val response = try {
                        llmService.generateResponse(formattedPrompt)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error generating response for query: $query", e)
                        "ERROR: ${e.message}"
                    }
                    val inferenceTime = System.currentTimeMillis() - queryStartTime
                    
                    Log.i(TAG, "Response time: ${inferenceTime}ms")
                    Log.i(TAG, "Response: ${response.take(100)}...")  // First 100 chars
                    
                    // Get battery metrics AFTER query
                    val batteryAfter = batteryMonitor.logMetrics()
                    val batteryLevel = batteryMonitor.getCurrentBatteryLevel()
                    
                    // Create query result
                    val queryResult = QueryResult(
                        timestamp = System.currentTimeMillis(),
                        queryText = query,
                        responseText = response,
                        inferenceTimeMs = inferenceTime,
                        batteryLevel = batteryLevel,
                        quantization = quantization,
                        modelName = modelPath.substringAfterLast("/")
                    )
                    
                    queriesCompleted++
                    
                    // Store data in memory (CSV will be created at end with all data)
                    dataLogger.logQuery(queryResult)
                    dataLogger.logBattery(batteryBefore) // Log before metrics
                    dataLogger.logBattery(batteryAfter)  // Log after metrics
                    Log.i(TAG, "Query $queriesCompleted data stored in memory (will be exported to CSV at end)")
                    
                    // Check battery level after query - if critically low, save CSV immediately
                    val batteryLevelAfterQuery = batteryMonitor.getCurrentBatteryLevel()
                    if (batteryLevelAfterQuery <= CRITICAL_BATTERY_LEVEL && batteryLevelAfterQuery > 0) {
                        Log.e(TAG, "⚠️ CRITICAL BATTERY LEVEL after query: $batteryLevelAfterQuery% - Saving CSV immediately before phone dies")
                        handleEarlyTermination("Battery died at ${batteryLevelAfterQuery}%")
                        return@launch // Exit coroutine
                    }
                    
                    // Check again after query (in case query took a long time)
                    if (System.currentTimeMillis() >= endTimeMs) {
                        Log.i(TAG, "Duration expired during query execution")
                        Log.i(TAG, "Query completed successfully (duration expired)")
                        Log.i(TAG, "Inference: ${inferenceTime}ms")
                        Log.i(TAG, "Battery after query: $batteryLevel%")
                        Log.i(TAG, "=== QUERY $queriesCompleted END (DURATION EXPIRED) ===")
                        
                        // Ensure final battery metrics are logged
                        val finalBatteryMetrics = batteryMonitor.logMetrics()
                        dataLogger.logBattery(finalBatteryMetrics)
                        
                        broadcastUpdate()
                        break
                    }
                    
                    // Comprehensive logging after EVERY query
                    Log.i(TAG, "Query completed successfully")
                    Log.i(TAG, "Inference: ${inferenceTime}ms")
                    Log.i(TAG, "Battery after query: $batteryLevel%")
                    Log.i(TAG, "=== QUERY $queriesCompleted END ===")
                    
                    broadcastUpdate()
                    
                    // Verify WakeLock is still held (important for screen-off operation)
                    if (wakeLock?.isHeld != true) {
                        Log.w(TAG, "WakeLock was released! Re-acquiring...")
                        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours
                        Log.i(TAG, "WakeLock re-acquired")
                    }
                    
                    // Wait for next interval with heartbeat logging
                    val currentTime = System.currentTimeMillis()
                    val timeRemaining = endTimeMs - currentTime
                    val waitDurationMs = intervalMinutes * 60 * 1000L
                    
                    // Check if there's enough time for another query
                    if (timeRemaining <= 0) {
                        Log.i(TAG, "No time remaining, exiting query loop")
                        break
                    }
                    
                    // Only wait if we have enough time for the full interval
                    // Otherwise, exit to allow the last query to complete
                    if (timeRemaining < waitDurationMs) {
                        Log.i(TAG, "Not enough time for full interval (${timeRemaining / 1000}s remaining < ${waitDurationMs / 1000}s needed), exiting")
                        break
                    }
                    
                    val nextQueryTime = currentTime + waitDurationMs
                    Log.i(TAG, "Sleeping for $intervalMinutes minutes until ${Date(nextQueryTime)}")
                    
                    // Send broadcast with waiting status
                    updateNotification("Waiting for next query... | ${getTimeRemaining()}")
                    broadcastUpdate() // Send update to show waiting status
                    
                    // Heartbeat logging and broadcast updates every 10 seconds during wait
                    var waited = 0L
                    val checkInterval = 10000L // 10 seconds (more frequent updates)
                    while (waited < waitDurationMs && isRunning && System.currentTimeMillis() < endTimeMs) {
                        delay(checkInterval)
                        waited += checkInterval
                        
                        // Check battery level - if critically low, save CSV immediately
                        val batteryLevel = batteryMonitor.getCurrentBatteryLevel()
                        if (batteryLevel <= CRITICAL_BATTERY_LEVEL && batteryLevel > 0) {
                            Log.e(TAG, "⚠️ CRITICAL BATTERY LEVEL: $batteryLevel% - Saving CSV immediately before phone dies")
                            handleEarlyTermination("Battery died at ${batteryLevel}%")
                            return@launch // Exit coroutine
                        }
                        
                        val remainingWait = waitDurationMs - waited
                        val timeLeft = endTimeMs - System.currentTimeMillis()
                        if (remainingWait > 0 && timeLeft > 0) {
                            Log.i(TAG, "Heartbeat - Still alive. Battery: ${batteryMonitor.getCurrentBatteryLevel()}%. Next query in ${remainingWait / 1000}s, Time left: ${timeLeft / 1000}s")
                            // Send periodic updates during wait
                            broadcastUpdate()
                        }
                    }
                    
                    // Final delay if any time remains
                    val finalWait = waitDurationMs - waited
                    if (finalWait > 0 && isRunning && System.currentTimeMillis() < endTimeMs) {
                        delay(finalWait)
                    }
                }
                
                Log.e(TAG, "Query loop exited")
                Log.e(TAG, "=== BENCHMARK COMPLETE ===")
                Log.e(TAG, "Total queries completed: $queriesCompleted")
                Log.e(TAG, "Total time: ${(System.currentTimeMillis() - startTimeMs) / 1000 / 60} minutes")
                
                // Log final battery metrics before export
                val finalBatteryMetrics = batteryMonitor.logMetrics()
                dataLogger.logBattery(finalBatteryMetrics)
                Log.e(TAG, "Final battery metrics logged")
                
                // Verify data is ready for export
                val queryCount = dataLogger.getResultsCount()
                val batteryCount = dataLogger.getBatteryMetricsCount()
                Log.e(TAG, "Data ready for export: $queryCount queries, $batteryCount battery metrics")
                
                if (queryCount == 0) {
                    Log.e(TAG, "WARNING: No queries were logged! CSV will be empty.")
                }
                
                // Export all collected data to CSV file (created only now, at the end)
                Log.e(TAG, "Exporting all collected data to CSV file...")
                updateNotification("Exporting results...")
                val csvFile = dataLogger.exportAllDataToCSV(prompts = testQueries)
                Log.e(TAG, "=== CSV FILE EXPORTED ===")
                Log.e(TAG, "File: ${csvFile?.absolutePath ?: "FAILED"}")
                if (csvFile != null) {
                    Log.e(TAG, "File exists: ${csvFile.exists()}")
                    Log.e(TAG, "File size: ${csvFile.length()} bytes")
                    Log.e(TAG, "Queries exported: ${dataLogger.getResultsCount()}")
                    Log.e(TAG, "Battery metrics exported: ${dataLogger.getBatteryMetricsCount()}")
                } else {
                    Log.e(TAG, "CSV export failed - file is null")
                }
                
                // Show completion notification
                Log.e(TAG, "Showing completion notification...")
                showCompletionNotification(queriesCompleted, csvFile)
                Log.e(TAG, "Completion notification shown")
                
                updateNotification("Complete! $queriesCompleted queries. Results saved.")
                Log.e(TAG, "Waiting 5 seconds before stopping...")
                delay(5000)
                
                Log.e(TAG, "Calling stopBenchmark()...")
                stopBenchmark()
                Log.e(TAG, "stopBenchmark() returned")
                Log.e(TAG, "Coroutine ending normally")
                
            } catch (e: Exception) {
                Log.e(TAG, "========================================")
                Log.e(TAG, "EXCEPTION IN COROUTINE!")
                Log.e(TAG, "Error message: ${e.message}")
                Log.e(TAG, "Error type: ${e.javaClass.name}")
                Log.e(TAG, "Stack trace:", e)
                Log.e(TAG, "========================================")
                updateNotification("Error: ${e.message}")
                Log.e(TAG, "Calling stopBenchmark() from catch block...")
                stopBenchmark()
                Log.e(TAG, "stopBenchmark() returned from catch block")
            }
            Log.e(TAG, "Coroutine scope ending")
        }
        Log.e(TAG, "startBenchmark() function returning")
    }
    
    /**
     * Handles early termination of benchmark (e.g., due to low battery).
     * Exports CSV immediately with metadata about the early termination.
     */
    private suspend fun handleEarlyTermination(reason: String) {
        Log.e(TAG, "=== EARLY TERMINATION ===")
        Log.e(TAG, "Reason: $reason")
        
        // Calculate time remaining
        val currentTime = System.currentTimeMillis()
        val timeRemainingMs = maxOf(0, endTimeMs - currentTime)
        val timeRemainingMinutes = timeRemainingMs / (60.0 * 1000.0)
        val timeRemainingHours = timeRemainingMinutes / 60.0
        
        Log.e(TAG, "Original duration: $originalDurationMinutes minutes (${originalDurationMinutes / 60.0} hours)")
        Log.e(TAG, "Time remaining: $timeRemainingMinutes minutes (${timeRemainingHours} hours)")
        Log.e(TAG, "Queries completed: $queriesCompleted")
        
        // Log final battery metrics
        val finalBatteryMetrics = batteryMonitor.logMetrics()
        dataLogger.logBattery(finalBatteryMetrics)
        
        // Export CSV with early termination metadata
        updateNotification("Battery low! Saving results...")
        val csvFile = dataLogger.exportAllDataToCSV(
            prompts = testQueries,
            originalDurationMinutes = originalDurationMinutes,
            timeRemainingMinutes = timeRemainingMinutes,
            terminationReason = reason
        )
        
        Log.e(TAG, "=== CSV FILE EXPORTED (EARLY TERMINATION) ===")
        Log.e(TAG, "File: ${csvFile?.absolutePath ?: "FAILED"}")
        if (csvFile != null) {
            Log.e(TAG, "File exists: ${csvFile.exists()}")
            Log.e(TAG, "File size: ${csvFile.length()} bytes")
        }
        
        // Show notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_COMPLETION)
            .setContentTitle("⚠️ Benchmark Stopped Early")
            .setContentText("Battery died. Results saved.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Benchmark stopped early due to low battery.\n\n" +
                        "Original duration: ${originalDurationMinutes / 60.0} hours\n" +
                        "Time remaining: ${String.format("%.1f", timeRemainingHours)} hours\n" +
                        "Queries completed: $queriesCompleted\n\n" +
                        "Results saved to CSV file."))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID_COMPLETION, notification)
        
        updateNotification("Battery died. Results saved.")
        delay(3000) // Give time for file to be written
        
        stopBenchmark()
    }
    
    private fun stopBenchmark() {
        isRunning = false
        batteryMonitor.stopMonitoring()
        llmService.unloadModel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun getNextQuery(): String {
        val query = testQueries[currentQueryIndex]
        currentQueryIndex = (currentQueryIndex + 1) % testQueries.size
        return query
    }
    
    private fun getTimeRemaining(): String {
        val remainingMs = endTimeMs - System.currentTimeMillis()
        if (remainingMs <= 0) return "Complete"
        
        val hours = remainingMs / (60 * 60 * 1000)
        val minutes = (remainingMs % (60 * 60 * 1000)) / (60 * 1000)
        
        return "${hours}h ${minutes}m remaining"
    }
    
    /**
     * Gets time remaining in milliseconds for precise countdown display.
     */
    private fun getTimeRemainingMs(): Long {
        return maxOf(0, endTimeMs - System.currentTimeMillis())
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LLM Benchmark",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Running battery benchmark"
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun createCompletionNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_COMPLETION,
            "LLM Benchmark Completion",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Benchmark completion notifications"
            enableVibration(true)
            enableLights(true)
            // Set vibration pattern: vibrate for 500ms, pause 200ms, vibrate 500ms
            vibrationPattern = longArrayOf(0, 500, 200, 500)
            // Set sound to default notification sound
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun createNotification(text: String): Notification {
        val stopIntent = Intent(this, BenchmarkService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LLM Battery Benchmark")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val timeRemaining = getTimeRemaining()
        val fullText = "$text | $timeRemaining"
        val notification = createNotification(fullText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun broadcastUpdate() {
        val intent = Intent("BENCHMARK_UPDATE").apply {
            putExtra("queries", queriesCompleted)
            putExtra("total_queries", totalQueries)
            putExtra("battery", batteryMonitor.getCurrentBatteryLevel())
            putExtra("time_remaining", getTimeRemaining())
            putExtra("time_remaining_ms", getTimeRemainingMs()) // For precise countdown
        }
        sendBroadcast(intent)
    }
    
    private fun showCompletionNotification(queriesCompleted: Int, csvFile: File?) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val fileName = csvFile?.name ?: "results.csv"
        val filePath = csvFile?.absolutePath ?: "Unknown location"
        
        // Extract just the Downloads folder path for display
        val displayPath = if (filePath.contains("/Download")) {
            "Downloads folder"
        } else {
            "app files folder"
        }
        
        // Create intent to open file manager (optional)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "resource/folder"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_COMPLETION)
            .setContentTitle("✅ Benchmark Complete!")
            .setContentText("Duration finished! $queriesCompleted queries completed. CSV files exported to $displayPath.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("⏱️ Benchmark Duration Finished!\n\n" +
                        "✅ Queries Completed: $queriesCompleted\n" +
                        "✅ CSV Results Exported Successfully\n" +
                        "📁 File: $fileName\n" +
                        "📂 Location: $displayPath\n\n" +
                        "You can find the CSV files in your Downloads folder using any file manager."))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)  // Dismissible
            .setContentIntent(pendingIntent)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_COMPLETION, notification)
        Log.i(TAG, "Completion notification shown: $queriesCompleted queries, file: $filePath")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        Log.i(TAG, "!!! SERVICE DESTROYED at ${Date()}")
        Log.i(TAG, "isRunning: $isRunning")
        Log.i(TAG, "queriesCompleted: $queriesCompleted")
        Log.i(TAG, "WakeLock held: ${wakeLock?.isHeld}")
        serviceScope.cancel()
        wakeLock?.release()
        Log.i(TAG, "WakeLock released")
        super.onDestroy()
        Log.i(TAG, "Service cleanup complete")
    }
}

