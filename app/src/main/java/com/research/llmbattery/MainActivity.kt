package com.research.llmbattery

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.research.llmbattery.R
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import com.research.llmbattery.models.ModelConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MainActivity class that serves as the primary interface for the LLM battery benchmarking application.
 * Provides Material Design 3 UI for controlling benchmark operations, monitoring battery consumption,
 * and managing LLM model selection and query scheduling.
 * 
 * Features:
 * - Material Design 3 UI with ViewBinding
 * - Real-time battery monitoring and display
 * - Model selection and loading
 * - Query scheduling with configurable intervals
 * - Results export and sharing
 * - Permission handling and error management
 * - Lifecycle-aware component management
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val UI_UPDATE_INTERVAL = 5000L // 5 seconds
        private const val PERMISSION_REQUEST_CODE = 1001
        const val WORK_NAME_DURATION_EXPIRED = "duration_expired_work"
        const val KEY_DURATION_HOURS = "duration_hours"
        
        // Cancel duration work when stopping manually
        fun cancelDurationWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_DURATION_EXPIRED)
        }
    }
    
    // UI Components
    private lateinit var spinnerModel: Spinner
    private lateinit var spinnerInterval: Spinner
    private lateinit var etDuration: EditText
    private lateinit var tvBatteryLevel: TextView
    private lateinit var tvQueriesCompleted: TextView
    private lateinit var tvAvgInferenceTime: TextView
    private lateinit var tvEstBatteryLife: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnStartStop: Button
    private lateinit var btnExport: Button
    
    // Core components
    private var llmService: LLMService? = null
    private var batteryMonitor: BatteryMonitor? = null
    private var queryScheduler: QueryScheduler? = null
    private var dataLogger: DataLogger? = null
    
    // State management
    private var selectedModel: ModelConfig? = null
    private var isRunning: Boolean = false
    
    // UI update coroutine
    private var uiUpdateJob: kotlinx.coroutines.Job? = null
    
    // Countdown update coroutine
    private var countdownUpdateJob: kotlinx.coroutines.Job? = null
    
    // Broadcast receiver for benchmark updates
    private val benchmarkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "BENCHMARK_UPDATE" -> {
                    val queries = intent.getIntExtra("queries", 0)
                    val totalQueries = intent.getIntExtra("total_queries", 0)
                    val battery = intent.getIntExtra("battery", 0)
                    val timeRemaining = intent.getStringExtra("time_remaining") ?: ""
                    val timeRemainingMs = intent.getLongExtra("time_remaining_ms", 0L)
                    
                    // Update countdown view if benchmark is running
                    if (isRunning && isCountdownViewActive) {
                        updateCountdownView(queries, totalQueries, battery, timeRemainingMs)
                    } else {
                        // Update normal view
                        tvQueriesCompleted.text = "Queries: $queries"
                        tvBatteryLevel.text = "Battery: $battery%"
                        tvEstBatteryLife.text = timeRemaining
                    }
                    
                    Log.d(TAG, "Received benchmark update: queries=$queries, battery=$battery%, time=$timeRemaining")
                }
            }
        }
    }
    
    // Countdown view elements
    private var tvTimeRemaining: TextView? = null
    private var tvQueriesCount: TextView? = null
    private var tvBatteryInfo: TextView? = null
    private var tvDrainRate: TextView? = null
    private var tvStatus: TextView? = null
    private var btnStopBenchmark: Button? = null
    private var isCountdownViewActive = false
    
    // Available models
    private val availableModels = listOf(
        ModelConfig(
            modelName = "Qwen2.5-0.5B (2-bit)",
            modelPath = "qwen2.5-0.5b-instruct-q2_k.gguf",
            quantization = "2-bit",
            sizeInMB = 200f
        ),
        ModelConfig(
            modelName = "Qwen2.5-0.5B (3-bit)",
            modelPath = "qwen2.5-0.5b-instruct-q3_k_m.gguf",
            quantization = "3-bit",
            sizeInMB = 250f
        ),
        ModelConfig(
            modelName = "Qwen2.5-0.5B (4-bit)",
            modelPath = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            quantization = "4-bit",
            sizeInMB = 350f
        )
    )
    
    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some permissions denied", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            // Set content view
            setContentView(R.layout.activity_main)
            
            // Initialize UI components
            initializeUIComponents()
        
        // Keep screen on during benchmark
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Initialize components
        initializeComponents()
        
        // Setup UI
        setupUI()
            
            // Initialize battery monitor
            try {
                batteryMonitor = BatteryMonitor(this)
                updateBatteryDisplay()
            } catch (e: Exception) {
                Log.e("MainActivity", "Battery monitor init failed: ${e.message}")
                tvBatteryLevel.text = "Battery: Error"
            }
            
            // Initialize data logger
            try {
                dataLogger = DataLogger(this)
                Log.i("MainActivity", "DataLogger initialized")
            } catch (e: Exception) {
                Log.e("MainActivity", "DataLogger init failed: ${e.message}")
            }
            
            // Initialize LLM service
            try {
                llmService = LLMService(this)
                Log.i("MainActivity", "LLMService initialized")
            } catch (e: Exception) {
                Log.e("MainActivity", "LLMService init failed: ${e.message}")
                Toast.makeText(this, "Warning: LLM loading may fail", Toast.LENGTH_SHORT).show()
            }
        
        // Request permissions
        requestPermissions()
        
        // Load available models
            // loadAvailableModels()  // TODO: Enable when models are ready
        
        Log.d(TAG, "MainActivity created")
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate: ${e.message}", e)
            Toast.makeText(this, "Error starting app: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    override fun onStart() {
        super.onStart()
        
        // Check if BenchmarkService is actually running
        val serviceRunning = isServiceRunning(BenchmarkService::class.java)
        
        if (serviceRunning) {
            // Service is running, update state
            if (!isRunning) {
                isRunning = true
                Log.d(TAG, "Service is running, updating UI state")
            }
            // Don't start battery monitoring here - service has its own
            startUIUpdates()
            
            // Switch to countdown view if service is running (after UI is set up)
            if (!isCountdownViewActive) {
                switchToCountdownView()
            }
        } else {
            // Service is not running, reset state
            if (isRunning) {
                isRunning = false
                Log.d(TAG, "Service is not running, resetting state")
            }
            // Switch back to main view if service stopped
            if (isCountdownViewActive) {
                switchToMainView()
            }
        }
        
        updateUI()
        Log.d(TAG, "MainActivity started, serviceRunning=$serviceRunning, isRunning=$isRunning")
    }
    
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("BENCHMARK_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(benchmarkReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(benchmarkReceiver, filter)
        }
        Log.d(TAG, "BroadcastReceiver registered")
    }
    
    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(benchmarkReceiver)
            Log.d(TAG, "BroadcastReceiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }
    
    /**
     * Checks if a service is currently running.
     */
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
        
        for (service in runningServices) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
    
    override fun onStop() {
        super.onStop()
        
        // Pause monitoring
        if (isRunning) {
            batteryMonitor?.stopMonitoring()
            stopUIUpdates()
        }
        
        Log.d(TAG, "MainActivity stopped")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Cleanup resources
        cleanup()
        
        Log.d(TAG, "MainActivity destroyed")
    }
    
    
    /**
     * Initializes UI components using findViewById.
     */
    private fun initializeUIComponents() {
        try {
            spinnerModel = findViewById(R.id.spinnerModel)
            spinnerInterval = findViewById(R.id.spinnerInterval)
            etDuration = findViewById(R.id.etDuration)
            tvBatteryLevel = findViewById(R.id.tvBatteryLevel)
            tvQueriesCompleted = findViewById(R.id.tvQueriesCompleted)
            tvAvgInferenceTime = findViewById(R.id.tvAvgInferenceTime)
            tvEstBatteryLife = findViewById(R.id.tvEstBatteryLife)
            progressBar = findViewById(R.id.progressBar)
            btnStartStop = findViewById(R.id.btnStartStop)
            btnExport = findViewById(R.id.btnExport)
            
            Log.d(TAG, "UI components initialized")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing UI components: ${e.message}", e)
        }
    }
    
    /**
     * Updates the battery display with current battery level.
     */
    private fun updateBatteryDisplay() {
        try {
            val level = batteryMonitor?.getCurrentBatteryLevel() ?: 0
            tvBatteryLevel.text = "Battery: $level%"
        } catch (e: Exception) {
            tvBatteryLevel.text = "Battery: Error"
            Log.e("MainActivity", "Battery update failed: ${e.message}")
        }
    }
    
    /**
     * Initializes all core components.
     */
    private fun initializeComponents() {
        try {
            // Don't initialize LLMService yet (native library might not be ready)
            // llmService = LLMService(this)  // TODO: Initialize when actually needed
            // batteryMonitor and dataLogger will be initialized in onCreate after UI setup
            // queryScheduler will be initialized when benchmark starts
            queryScheduler = null
            
            Log.d(TAG, "Components initialized")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing components: ${e.message}", e)
        }
    }
    
    /**
     * Sets up the spinner components with model selection.
     */
    private fun setupSpinners() {
        // Model spinner
        val modelNames = availableModels.map { it.modelName }.toTypedArray()
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelNames)
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerModel.adapter = modelAdapter
        
        spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedModel = availableModels[position]
                Toast.makeText(this@MainActivity, "Selected: ${selectedModel?.modelName}", Toast.LENGTH_SHORT).show()
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedModel = null
            }
        }
        
        // Interval spinner
        val intervals = arrayOf("1 minute", "5 minutes")
        val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervals)
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerInterval.adapter = intervalAdapter
    }
    
    /**
     * Sets up the UI components and event listeners.
     */
    private fun setupUI() {
        try {
            setupSpinners()
            
            // Button listeners
            btnStartStop.setOnClickListener {
                if (!isRunning) {
                    // Double-check that service is not running (safety check)
                    if (isServiceRunning(BenchmarkService::class.java)) {
                        Log.w(TAG, "Service is running but isRunning flag is false, updating state")
                        isRunning = true
                        updateUI()
                        Toast.makeText(this, "Benchmark is already running", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    
                    // Validate selection
                    if (selectedModel == null) {
                        Toast.makeText(this, "Please select a model first", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    
                    // Get interval from spinner
                    val intervalMinutes = when (spinnerInterval.selectedItemPosition) {
                        0 -> 1  // "1 minute"
                        1 -> 5  // "5 minutes"
                        else -> 5
                    }
                    
                    // Get duration from EditText (in hours)
                    val durationText = etDuration.text.toString().trim()
                    if (durationText.isEmpty()) {
                        Toast.makeText(this, "Please enter benchmark duration (in hours)", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    
                    val durationHours = try {
                        durationText.toDouble()
                    } catch (e: NumberFormatException) {
                        Toast.makeText(this, "Invalid duration. Please enter a number.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    
                    if (durationHours <= 0) {
                        Toast.makeText(this, "Duration must be greater than 0", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    
                    // Convert hours to minutes for the service
                    val durationMinutes = durationHours * 60.0
                    
                    // Calculate display duration for toast message
                    val displayDuration = if (durationHours < 1.0) {
                        val mins = (durationHours * 60).toInt()
                        "$mins minute${if (mins != 1) "s" else ""}"
                    } else if (durationHours == durationHours.toInt().toDouble()) {
                        "${durationHours.toInt()} hour${if (durationHours.toInt() != 1) "s" else ""}"
                    } else {
                        val hours = durationHours.toInt()
                        val mins = ((durationHours - hours) * 60).toInt()
                        if (hours > 0) {
                            "$hours hour${if (hours != 1) "s" else ""} $mins minute${if (mins != 1) "s" else ""}"
                        } else {
                            "$mins minute${if (mins != 1) "s" else ""}"
                        }
                    }
                    
                    // Request to ignore battery optimizations for continuous operation
                    requestIgnoreBatteryOptimizations()
                    
                    // Start benchmark service
                    // LLMService.loadModel() expects just the filename, it will look in Downloads folder
                    val modelPath = selectedModel?.modelPath ?: ""
                    Log.i(TAG, "Starting benchmark service - modelPath: '$modelPath', quantization: '${selectedModel?.quantization}', interval: $intervalMinutes, duration: $durationMinutes minutes")
                    
                    val intent = Intent(this, BenchmarkService::class.java).apply {
                        action = BenchmarkService.ACTION_START
                        putExtra(BenchmarkService.EXTRA_MODEL_PATH, modelPath)
                        putExtra(BenchmarkService.EXTRA_QUANTIZATION, selectedModel?.quantization ?: "unknown")
                        putExtra(BenchmarkService.EXTRA_INTERVAL_MINUTES, intervalMinutes)
                        putExtra(BenchmarkService.EXTRA_DURATION_MINUTES, durationMinutes)  // Pass as minutes for precision
                    }
                    
                    // Store benchmark timing for immediate countdown display
                    benchmarkStartTimeMs = System.currentTimeMillis()
                    benchmarkDurationMs = (durationMinutes * 60 * 1000L).toLong()
                    benchmarkEndTimeMs = benchmarkStartTimeMs + benchmarkDurationMs
                    
                    Log.i(TAG, "Calling startForegroundService...")
                    startForegroundService(intent)
                    Log.i(TAG, "startForegroundService called")
                    
                    isRunning = true
                    btnStartStop.text = "Stop Benchmark"
                    Toast.makeText(this, "Benchmark started! Will run for $displayDuration", Toast.LENGTH_LONG).show()
                    
                    // Switch to countdown view (will use stored timing if broadcast hasn't arrived)
                    switchToCountdownView()
                    
                } else {
                    // Stop benchmark
                    val intent = Intent(this, BenchmarkService::class.java).apply {
                        action = BenchmarkService.ACTION_STOP
                    }
                    startService(intent)
                    
                    isRunning = false
                    switchToMainView()  // Switch back to main view
                    updateUI()  // Re-enable spinner and update button
                    Toast.makeText(this, "Benchmark stopped", Toast.LENGTH_SHORT).show()
                }
            }
            btnExport.setOnClickListener {
                lifecycleScope.launch {
                    try {
                        val resultsCount = dataLogger?.getResultsCount() ?: 0
                        if (resultsCount > 0) {
                            val file = dataLogger?.exportToCSV()
                            Toast.makeText(this@MainActivity, "Exported $resultsCount results to ${file?.name}", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "No results to export yet", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                        Log.e("MainActivity", "Export error: ${e.message}")
                    }
                }
            }
        
        // Initialize UI state
        updateUI()
        
        Log.d(TAG, "UI setup completed")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up UI: ${e.message}", e)
        }
    }
    
    /**
     * Sets up the model selection spinner.
     */
    private fun setupModelSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            availableModels.map { it.modelName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerModel.adapter = adapter
        
        // Set default selection to 3-bit (balanced option)
        if (availableModels.size >= 2) {
            selectedModel = availableModels[1] // 3-bit model
            spinnerModel.setSelection(1)
        } else if (availableModels.isNotEmpty()) {
            selectedModel = availableModels[0]
            spinnerModel.setSelection(0)
        }
    }
    
    /**
     * Sets up the interval selection spinner.
     */
    private fun setupIntervalSpinner() {
        val intervals = listOf("1 minute", "5 minutes")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervals)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerInterval.adapter = adapter
        
        // Set default to 1 minute
        spinnerInterval.setSelection(0)
    }
    
    /**
     * Sets up button click listeners.
     */
    private fun setupButtonListeners() {
        btnStartStop.setOnClickListener {
            try {
                updateBatteryDisplay()
                Toast.makeText(this, "Battery: ${batteryMonitor?.getCurrentBatteryLevel()}%", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        btnExport.setOnClickListener {
            // Show results dialog first, with option to export
            showResultsDialog()
        }
        
        spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedModel = availableModels[position]
                Toast.makeText(
                    this@MainActivity,
                    "Selected: ${selectedModel?.modelName}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedModel = null
            }
        }
    }
    
    /**
     * Validates duration and starts the benchmark.
     */
    private fun startBenchmarkWithDuration() {
        if (selectedModel == null) {
            Toast.makeText(this, "Please select a model first", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Get duration from EditText
        val durationText = etDuration.text.toString().trim()
        if (durationText.isEmpty()) {
            Toast.makeText(this, "Please enter benchmark duration", Toast.LENGTH_SHORT).show()
            return
        }
        
        val durationHours = try {
            durationText.toDouble()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "Invalid duration. Please enter a number.", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (durationHours <= 0) {
            Toast.makeText(this, "Duration must be greater than 0", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Start benchmark
        startBenchmark(durationHours)
    }
    
    /**
     * Starts the battery benchmark with the selected model and interval.
     * Loads the model, starts battery monitoring, and schedules automated queries.
     */
    private fun startBenchmark(durationHours: Double) {
        lifecycleScope.launch {
            try {
                // Show loading state
                progressBar.visibility = View.VISIBLE
                btnStartStop.isEnabled = false
                
                // Ensure all services are initialized
                if (llmService == null) {
                    llmService = LLMService(this@MainActivity)
                }
                if (batteryMonitor == null) {
                    batteryMonitor = BatteryMonitor(this@MainActivity)
                }
                if (dataLogger == null) {
                    dataLogger = DataLogger(this@MainActivity)
                }
                
                // Update battery display
                updateBatteryDisplay()
                
                // Load the selected model
                Toast.makeText(this@MainActivity, "Loading ${selectedModel!!.modelName}...", Toast.LENGTH_SHORT).show()
                val modelPath = selectedModel!!.modelPath
                val loaded = withContext(Dispatchers.IO) {
                    llmService?.loadModel(modelPath) ?: false
                }
                
                if (!loaded) {
                    Toast.makeText(this@MainActivity, "Failed to load model: ${selectedModel!!.modelName}", Toast.LENGTH_LONG).show()
                    return@launch
                }
                
                Toast.makeText(this@MainActivity, "Model loaded! Starting benchmark...", Toast.LENGTH_SHORT).show()
                
                // Start battery monitoring
                batteryMonitor?.startMonitoring()
                Log.i(TAG, "Battery monitoring started")
                
                // Get selected interval from spinner
                val intervalMinutes = if (spinnerInterval.selectedItemPosition == 0) 1 else 5
                
                // Schedule automated queries using QueryScheduler
                // Pass model path so Worker can load the model
                QueryScheduler.scheduleQueries(
                    this@MainActivity,
                    intervalMinutes,
                    llmService!!,
                    dataLogger!!,
                    batteryMonitor!!,
                    modelPath
                )
                Log.i(TAG, "Scheduled queries every $intervalMinutes minute(s)")
                
                // Schedule duration expired worker to stop benchmark and export results
                scheduleDurationExpiredWorker(durationHours)
                Log.i(TAG, "Scheduled duration expired worker for $durationHours hours")
                
                // Update state
                isRunning = true
                
                // Start UI updates
                startUIUpdates()
                
                // Update UI
                updateUI()
                
                Toast.makeText(
                    this@MainActivity,
                    "Benchmark started! Will run for $durationHours hour(s). Results will be saved to CSV files.",
                    Toast.LENGTH_LONG
                ).show()
                
                Log.i(TAG, "Benchmark started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting benchmark", e)
                Toast.makeText(this@MainActivity, "Error starting benchmark: ${e.message}", Toast.LENGTH_LONG).show()
                isRunning = false
                updateUI()
            } finally {
                progressBar.visibility = View.GONE
                btnStartStop.isEnabled = true
            }
        }
    }
    
    /**
     * Stops the battery benchmark and saves results.
     */
    private fun stopBenchmark() {
        lifecycleScope.launch {
            try {
                // Stop query scheduling
                QueryScheduler.cancelSchedule(this@MainActivity)
                
                // Cancel duration expired work (if stopping manually)
                cancelDurationWork(this@MainActivity)
                
                // Stop battery monitoring
                batteryMonitor?.stopMonitoring()
                
                // Stop UI updates
                stopUIUpdates()
                
                // Update state
                isRunning = false
                
                // Update UI
                updateUI()
                
                // Show completion message and results dialog
                val queryCount = dataLogger?.getResultsCount() ?: 0
                val batteryCount = dataLogger?.getBatteryMetricsCount() ?: 0
                
                Toast.makeText(
                    this@MainActivity,
                    "Benchmark completed. Queries: $queryCount, Battery metrics: $batteryCount",
                    Toast.LENGTH_SHORT
                ).show()
                
                // Automatically show results dialog when stopping
                if (queryCount > 0 || batteryCount > 0) {
                    delay(500) // Small delay so toast is visible first
                    showResultsDialog()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping benchmark", e)
                Toast.makeText(this@MainActivity, "Error stopping benchmark: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Updates the UI with current metrics and state.
     */
    private fun updateUI() {
        try {
            // Update battery level
            val batteryLevel = batteryMonitor?.getCurrentBatteryLevel() ?: 0
            tvBatteryLevel.text = "Battery: $batteryLevel%"
            
            // Update queries completed
            val queryCount = dataLogger?.getResultsCount() ?: 0
            tvQueriesCompleted.text = "Queries: $queryCount"
            
            // Update average inference time
            val avgInferenceTime = calculateAverageInferenceTime()
            tvAvgInferenceTime.text = "Avg Time: ${avgInferenceTime}ms"
            
            // Update estimated battery life
            val estimatedLife = calculateEstimatedBatteryLife()
            tvEstBatteryLife.text = "Est. Life: $estimatedLife"
            
            // Update button states
            btnStartStop.text = if (isRunning) "Stop Benchmark" else "Start Benchmark"
            btnStartStop.isEnabled = true
            
            // Update model selection state
            spinnerModel.isEnabled = !isRunning
            
            // Update progress bar
            progressBar.visibility = if (isRunning) View.VISIBLE else View.GONE
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating UI", e)
            // Set safe defaults
            tvBatteryLevel.text = "Battery: Unknown"
            tvQueriesCompleted.text = "Queries: 0"
            tvAvgInferenceTime.text = "Avg Time: 0ms"
            tvEstBatteryLife.text = "Est. Life: Unknown"
            btnStartStop.text = "Start Benchmark"
            btnStartStop.isEnabled = true
        }
    }
    
    
    /**
     * Shows a dialog with current benchmark results and statistics.
     */
    private fun showResultsDialog() {
        lifecycleScope.launch {
            try {
                val queryResults = dataLogger?.getQueryResults() ?: emptyList()
                val batteryMetrics = dataLogger?.getBatteryMetrics() ?: emptyList()
                val queryCount = queryResults.size
                val batteryCount = batteryMetrics.size
                
                if (queryCount == 0 && batteryCount == 0) {
                    Toast.makeText(this@MainActivity, "No results yet. Start the benchmark first.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Calculate statistics
                val avgInferenceTime = if (queryResults.isNotEmpty()) {
                    queryResults.map { it.inferenceTimeMs }.average().toLong()
                } else 0L
                
                val minInferenceTime = if (queryResults.isNotEmpty()) {
                    queryResults.minOf { it.inferenceTimeMs }
                } else 0L
                
                val maxInferenceTime = if (queryResults.isNotEmpty()) {
                    queryResults.maxOf { it.inferenceTimeMs }
                } else 0L
                
                val avgBatteryDrain = if (batteryMetrics.isNotEmpty()) {
                    batteryMetrics.map { it.batteryDrainRate }.average()
                } else 0.0
                
                // Build message
                val message = buildString {
                    append("📊 Benchmark Results\n\n")
                    append("Queries Completed: $queryCount\n")
                    append("Battery Metrics: $batteryCount\n\n")
                    
                    if (queryCount > 0) {
                        append("⚡ Inference Performance:\n")
                        append("  Avg Time: ${avgInferenceTime}ms\n")
                        append("  Min Time: ${minInferenceTime}ms\n")
                        append("  Max Time: ${maxInferenceTime}ms\n\n")
                    }
                    
                    if (batteryCount > 0) {
                        append("🔋 Battery Stats:\n")
                        append("  Avg Drain Rate: ${String.format("%.2f", avgBatteryDrain)}%/h\n\n")
                    }
                    
                    if (queryResults.isNotEmpty()) {
                        append("📝 Recent Queries:\n")
                        queryResults.takeLast(5).forEachIndexed { index, result ->
                            val queryPreview = result.queryText.take(40)
                            append("  ${index + 1}. $queryPreview... (${result.inferenceTimeMs}ms)\n")
                        }
                        if (queryCount > 5) {
                            append("  ... and ${queryCount - 5} more\n")
                        }
                    }
                    
                    append("\n💡 Tip: Use 'Export' to save full results to CSV")
                }
                
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Benchmark Results")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Export CSV") { _, _ ->
                        exportResults()
                    }
                    .show()
                    
            } catch (e: Exception) {
                Log.e(TAG, "Error showing results dialog", e)
                Toast.makeText(this@MainActivity, "Error loading results: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Exports results to CSV and shows success dialog.
     */
    private fun exportResults() {
        lifecycleScope.launch {
            try {
                btnExport.isEnabled = false
                
                val queryCount = dataLogger?.getResultsCount() ?: 0
                val batteryCount = dataLogger?.getBatteryMetricsCount() ?: 0
                
                if (queryCount == 0 && batteryCount == 0) {
                    Toast.makeText(this@MainActivity, "No results to export yet", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Export to CSV
                val exportedFile = dataLogger?.exportToCSV()
                
                if (exportedFile != null) {
                    val message = "Exported successfully!\n\n" +
                            "Queries: $queryCount\n" +
                            "Battery Metrics: $batteryCount\n\n" +
                            "Location:\n${exportedFile.absolutePath}"
                    showExportSuccessDialog(message, exportedFile.absolutePath)
                } else {
                    Toast.makeText(this@MainActivity, "Failed to export results", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting results", e)
                Toast.makeText(this@MainActivity, "Error exporting results: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnExport.isEnabled = true
            }
        }
    }
    
    
    /**
     * Detects quantization type from model name.
     */
    private fun detectQuantizationFromName(modelName: String): String {
        return when {
            modelName.contains("q2") || modelName.contains("2bit") -> "2-bit"
            modelName.contains("q3") || modelName.contains("3bit") -> "3-bit"
            modelName.contains("q4") || modelName.contains("4bit") -> "4-bit"
            modelName.contains("q5") || modelName.contains("5bit") -> "5-bit"
            modelName.contains("q6") || modelName.contains("6bit") -> "6-bit"
            modelName.contains("q8") || modelName.contains("8bit") -> "8-bit"
            modelName.contains("f16") || modelName.contains("fp16") -> "FP16"
            modelName.contains("f32") || modelName.contains("fp32") -> "FP32"
            else -> "4-bit"
        }
    }
    
    /**
     * Calculates average inference time from logged results.
     */
    private fun calculateAverageInferenceTime(): Long {
        val results = dataLogger?.getQueryResults() ?: emptyList()
        return if (results.isNotEmpty()) {
            results.map { it.inferenceTimeMs }.average().toLong()
        } else {
            0L
        }
    }
    
    /**
     * Calculates estimated battery life remaining.
     */
    private fun calculateEstimatedBatteryLife(): String {
        val batteryLevel = batteryMonitor?.getCurrentBatteryLevel() ?: 0
        val drainRate = batteryMonitor?.getBatteryDrainRate() ?: 0.0f
        
        return if (drainRate > 0) {
            val hoursRemaining = batteryLevel.toDouble() / drainRate
            val minutesRemaining = (hoursRemaining * 60).toInt()
            "${minutesRemaining}m"
        } else {
            "Unknown"
        }
    }
    
    /**
     * Starts periodic UI updates.
     */
    private fun startUIUpdates() {
        stopUIUpdates() // Stop any existing updates
        
        uiUpdateJob = lifecycleScope.launch {
            while (isRunning) {
                updateUI()
                delay(UI_UPDATE_INTERVAL)
            }
        }
        
        Log.d(TAG, "UI updates started")
    }
    
    /**
     * Stops periodic UI updates.
     */
    private fun stopUIUpdates() {
        uiUpdateJob?.cancel()
        uiUpdateJob = null
    }
    
    /**
     * Shows confirmation dialog before stopping benchmark.
     */
    private fun showStopConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Stop Benchmark")
            .setMessage("Are you sure you want to stop the benchmark? Progress will be saved.")
            .setPositiveButton("Stop") { _, _ ->
                stopBenchmark()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Schedules a one-time work to stop the benchmark and export results after duration expires.
     */
    private fun scheduleDurationExpiredWorker(durationHours: Double) {
        try {
            val workManager = WorkManager.getInstance(this)
            
            // Cancel any existing duration work
            workManager.cancelUniqueWork(WORK_NAME_DURATION_EXPIRED)
            
            // Create input data
            val inputData = Data.Builder()
                .putDouble(KEY_DURATION_HOURS, durationHours)
                .build()
            
            // Schedule one-time work after duration
            val durationWorkRequest = OneTimeWorkRequestBuilder<DurationExpiredWorker>()
                .setInputData(inputData)
                .setInitialDelay(durationHours.toLong(), TimeUnit.HOURS)
                .addTag("duration_expired")
                .build()
            
            workManager.enqueueUniqueWork(
                WORK_NAME_DURATION_EXPIRED,
                ExistingWorkPolicy.REPLACE,
                durationWorkRequest
            )
            
            Log.i(TAG, "Scheduled duration expired worker for ${durationHours} hours")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling duration expired worker", e)
        }
    }
    
    /**
     * Shows success dialog after export with file location.
     */
    private fun showExportSuccessDialog(message: String, filePath: String) {
        AlertDialog.Builder(this)
            .setTitle("Export Successful")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("View Results") { _, _ ->
                showResultsDialog()
            }
            .show()
    }
    
    /**
     * Requests necessary permissions.
     */
    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    /**
     * Requests to ignore battery optimizations so the service can run continuously
     * even when the phone screen is off or in deep sleep mode.
     */
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Log.i(TAG, "Requested to ignore battery optimizations for continuous operation")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not request battery optimization exemption: ${e.message}")
                    // Fallback: Show instructions to user
                    Toast.makeText(
                        this,
                        "For best results, please disable battery optimization for this app in Settings",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Log.d(TAG, "Battery optimizations already ignored")
            }
        }
    }
    
    /**
     * Switches to the countdown timer view when benchmark is running.
     */
    private fun switchToCountdownView() {
        try {
            setContentView(R.layout.activity_benchmark_running)
            isCountdownViewActive = true
            
            // Initialize countdown view elements
            tvTimeRemaining = findViewById(R.id.tvTimeRemaining)
            tvQueriesCount = findViewById(R.id.tvQueriesCount)
            tvBatteryInfo = findViewById(R.id.tvBatteryInfo)
            tvDrainRate = findViewById(R.id.tvDrainRate)
            tvStatus = findViewById(R.id.tvStatus)
            btnStopBenchmark = findViewById(R.id.btnStopBenchmark)
            
            // Set stop button listener
            btnStopBenchmark?.setOnClickListener {
                val intent = Intent(this, BenchmarkService::class.java).apply {
                    action = BenchmarkService.ACTION_STOP
                }
                startService(intent)
                isRunning = false
                switchToMainView()
                updateUI()
                Toast.makeText(this, "Benchmark stopped", Toast.LENGTH_SHORT).show()
            }
            
            // Request immediate update from service by checking if it's running
            // and triggering a broadcast request
            if (isServiceRunning(BenchmarkService::class.java)) {
                // Service is running, send a request for update
                // The service will send periodic updates, but we can also trigger one
                Handler(Looper.getMainLooper()).postDelayed({
                    // Give service a moment, then check if we got an update
                    if (benchmarkEndTimeMs == 0L) {
                        // Still no update, request one more time
                        Log.d(TAG, "No benchmark time received yet, waiting for broadcast...")
                    }
                }, 1000)
            }
            
            // Start countdown update loop for smooth updates
            startCountdownUpdates()
            
            Log.d(TAG, "Switched to countdown view")
        } catch (e: Exception) {
            Log.e(TAG, "Error switching to countdown view", e)
        }
    }
    
    /**
     * Starts a coroutine to update the countdown timer every second for smooth display.
     */
    private fun startCountdownUpdates() {
        countdownUpdateJob?.cancel()
        countdownUpdateJob = lifecycleScope.launch {
            while (isRunning && isCountdownViewActive) {
                // Update timer every second for smooth countdown
                // We'll calculate remaining time locally if broadcast hasn't arrived yet
                updateCountdownTimer()
                delay(1000) // Update every second
            }
        }
    }
    
    // Store benchmark end time for local countdown calculation
    private var benchmarkEndTimeMs: Long = 0
    private var benchmarkStartTimeMs: Long = 0
    private var benchmarkDurationMs: Long = 0
    
    /**
     * Updates the countdown timer display with current remaining time.
     */
    private fun updateCountdownTimer() {
        val remainingMs = if (benchmarkEndTimeMs > 0) {
            maxOf(0, benchmarkEndTimeMs - System.currentTimeMillis())
        } else if (benchmarkStartTimeMs > 0 && benchmarkDurationMs > 0) {
            // Use stored timing if broadcast hasn't arrived yet
            val elapsed = System.currentTimeMillis() - benchmarkStartTimeMs
            maxOf(0, benchmarkDurationMs - elapsed)
        } else {
            0L
        }
        
        if (remainingMs > 0 || benchmarkEndTimeMs > 0 || benchmarkStartTimeMs > 0) {
            val hours = remainingMs / (60 * 60 * 1000)
            val minutes = (remainingMs % (60 * 60 * 1000)) / (60 * 1000)
            val seconds = (remainingMs % (60 * 1000)) / 1000
            
            val formattedTime = String.format("%02d:%02d:%02d", hours, minutes, seconds)
            tvTimeRemaining?.text = formattedTime
        }
    }
    
    /**
     * Stops the countdown update coroutine.
     */
    private fun stopCountdownUpdates() {
        countdownUpdateJob?.cancel()
        countdownUpdateJob = null
    }
    
    /**
     * Switches back to the main view when benchmark stops.
     */
    private fun switchToMainView() {
        try {
            stopCountdownUpdates()
            setContentView(R.layout.activity_main)
            isCountdownViewActive = false
            
            // Re-initialize main view components
            initializeUIComponents()
            setupUI()
            
            // Update UI state
            updateUI()
            
            Log.d(TAG, "Switched to main view")
        } catch (e: Exception) {
            Log.e(TAG, "Error switching to main view", e)
        }
    }
    
    /**
     * Updates the countdown timer view with current benchmark status.
     */
    private fun updateCountdownView(queries: Int, totalQueries: Int, battery: Int, timeRemainingMs: Long) {
        try {
            // Update benchmark end time for local countdown
            if (timeRemainingMs > 0) {
                benchmarkEndTimeMs = System.currentTimeMillis() + timeRemainingMs
            }
            
            // Format time as HH:MM:SS (use timeRemainingMs directly, or calculate from benchmarkEndTimeMs if available)
            val remainingMs = if (timeRemainingMs > 0) {
                timeRemainingMs
            } else if (benchmarkEndTimeMs > 0) {
                maxOf(0, benchmarkEndTimeMs - System.currentTimeMillis())
            } else {
                0L
            }
            
            val hours = remainingMs / (60 * 60 * 1000)
            val minutes = (remainingMs % (60 * 60 * 1000)) / (60 * 1000)
            val seconds = (remainingMs % (60 * 1000)) / 1000
            
            val formattedTime = String.format("%02d:%02d:%02d", hours, minutes, seconds)
            tvTimeRemaining?.text = formattedTime
            
            // Update queries count
            tvQueriesCount?.text = "Queries: $queries / $totalQueries"
            
            // Update battery info
            tvBatteryInfo?.text = "Battery: $battery%"
            
            // Get drain rate from battery monitor if available
            val drainRate = batteryMonitor?.getBatteryDrainRate() ?: 0.0f
            tvDrainRate?.text = "Drain Rate: ${String.format("%.1f", drainRate)}%/h"
            
            // Update status - show if query is running or waiting
            if (queries < totalQueries && timeRemainingMs > 0) {
                tvStatus?.text = "Running benchmark..."
            } else if (timeRemainingMs <= 0) {
                tvStatus?.text = "Complete!"
            } else {
                tvStatus?.text = ""
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating countdown view", e)
        }
    }
    
    /**
     * Parses time remaining string and formats it as HH:MM:SS.
     * Input format: "2h 30m remaining" or "Complete"
     * Output format: "02:30:00" or "00:00:00"
     */
    private fun parseTimeRemaining(timeRemaining: String): String {
        return try {
            if (timeRemaining.contains("Complete", ignoreCase = true)) {
                "00:00:00"
            } else {
                // Parse "2h 30m remaining" format
                var hours = 0
                var minutes = 0
                
                val hourMatch = Regex("(\\d+)h").find(timeRemaining)
                if (hourMatch != null) {
                    hours = hourMatch.groupValues[1].toInt()
                }
                
                val minuteMatch = Regex("(\\d+)m").find(timeRemaining)
                if (minuteMatch != null) {
                    minutes = minuteMatch.groupValues[1].toInt()
                }
                
                // Format as HH:MM:SS (seconds always 0 for now)
                String.format("%02d:%02d:00", hours, minutes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing time remaining: $timeRemaining", e)
            "00:00:00"
        }
    }
    
    /**
     * Cleans up resources.
     */
    private fun cleanup() {
        try {
            // Stop UI updates
            stopUIUpdates()
            stopCountdownUpdates()
            
            // Stop benchmark if running
            if (isRunning) {
                stopBenchmark()
            }
            
            // Cleanup components
            // llmService.cleanup()  // TODO: Enable when LLMService is ready
            batteryMonitor?.cleanup()
            
            Log.d(TAG, "Cleanup completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    /**
     * Handles configuration changes (rotation, etc.).
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // Re-setup UI if needed
        setupUI()
        
        Log.d(TAG, "Configuration changed")
    }
}
