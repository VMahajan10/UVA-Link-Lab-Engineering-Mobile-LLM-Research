package com.research.llmbattery

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.WorkerParameters
import com.research.llmbattery.databinding.ActivityMainBinding
import com.research.llmbattery.models.ModelConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    }
    
    // ViewBinding
    private lateinit var binding: ActivityMainBinding
    
    // Core components
    private lateinit var llmService: LLMService
    private lateinit var batteryMonitor: BatteryMonitor
    private var queryScheduler: QueryScheduler? = null
    private lateinit var dataLogger: DataLogger
    
    // State management
    private var selectedModel: ModelConfig? = null
    private var isRunning: Boolean = false
    
    // UI update coroutine
    private var uiUpdateJob: kotlinx.coroutines.Job? = null
    
    // Available models
    private val availableModels = mutableListOf<ModelConfig>()
    
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
        
        // Initialize ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Keep screen on during benchmark
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Initialize components
        initializeComponents()
        
        // Setup UI
        setupUI()
        
        // Request permissions
        requestPermissions()
        
        // Load available models
        loadAvailableModels()
        
        Log.d(TAG, "MainActivity created")
    }
    
    override fun onStart() {
        super.onStart()
        
        // Resume monitoring if benchmark was running
        if (isRunning) {
            batteryMonitor.startMonitoring()
            startUIUpdates()
        }
        
        Log.d(TAG, "MainActivity started")
    }
    
    override fun onStop() {
        super.onStop()
        
        // Pause monitoring
        if (isRunning) {
            batteryMonitor.stopMonitoring()
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
     * Initializes all core components.
     */
    private fun initializeComponents() {
        llmService = LLMService(this)
        batteryMonitor = BatteryMonitor(this)
        dataLogger = DataLogger(this)
        
        // Initialize QueryScheduler (will be properly initialized when starting benchmark)
        // Note: QueryScheduler will be properly initialized when starting the benchmark
        queryScheduler = null
        
        Log.d(TAG, "Components initialized")
    }
    
    /**
     * Sets up the UI components and event listeners.
     */
    private fun setupUI() {
        // Setup model selection spinner
        setupModelSpinner()
        
        // Setup interval selection spinner
        setupIntervalSpinner()
        
        // Setup button click listeners
        setupButtonListeners()
        
        // Initialize UI state
        updateUI()
        
        Log.d(TAG, "UI setup completed")
    }
    
    /**
     * Sets up the model selection spinner.
     */
    private fun setupModelSpinner() {
        val modelNames = availableModels.map { "${it.modelName} (${it.quantization})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerModel.adapter = adapter
        
        // Set default selection
        if (availableModels.isNotEmpty()) {
            selectedModel = availableModels[0]
            binding.spinnerModel.setSelection(0)
        }
    }
    
    /**
     * Sets up the interval selection spinner.
     */
    private fun setupIntervalSpinner() {
        val intervals = listOf("1 minute", "5 minutes")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervals)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerInterval.adapter = adapter
        
        // Set default to 1 minute
        binding.spinnerInterval.setSelection(0)
    }
    
    /**
     * Sets up button click listeners.
     */
    private fun setupButtonListeners() {
        binding.btnStartStop.setOnClickListener {
            if (isRunning) {
                showStopConfirmationDialog()
            } else {
                startBenchmark()
            }
        }
        
        binding.btnExport.setOnClickListener {
            exportResults()
        }
        
        binding.spinnerModel.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedModel = availableModels[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        })
    }
    
    /**
     * Starts the battery benchmark with the selected model and interval.
     */
    private fun startBenchmark() {
        if (selectedModel == null) {
            Toast.makeText(this, "Please select a model first", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                // Show loading state
                binding.progressBar.visibility = View.VISIBLE
                binding.btnStartStop.isEnabled = false
                
                // Load the selected model
                val modelName = selectedModel!!.modelName
                val success = llmService.loadModel("$modelName.gguf")
                
                if (!success) {
                    Toast.makeText(this@MainActivity, "Failed to load model: $modelName", Toast.LENGTH_LONG).show()
                    return@launch
                }
                
                // Initialize QueryScheduler with dependencies
                queryScheduler?.initialize(llmService, dataLogger, batteryMonitor)
                
                // Start battery monitoring
                batteryMonitor.startMonitoring()
                
                // Get selected interval
                val intervalMinutes = if (binding.spinnerInterval.selectedItemPosition == 0) 1 else 5
                
                // Schedule queries
                QueryScheduler.scheduleQueries(
                    this@MainActivity,
                    intervalMinutes,
                    llmService,
                    dataLogger,
                    batteryMonitor
                )
                
                // Update state
                isRunning = true
                
                // Start UI updates
                startUIUpdates()
                
                // Update UI
                updateUI()
                
                Toast.makeText(this@MainActivity, "Benchmark started", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting benchmark", e)
                Toast.makeText(this@MainActivity, "Error starting benchmark: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnStartStop.isEnabled = true
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
                
                // Stop battery monitoring
                batteryMonitor.stopMonitoring()
                
                // Stop UI updates
                stopUIUpdates()
                
                // Update state
                isRunning = false
                
                // Update UI
                updateUI()
                
                // Show completion message
                val queryCount = dataLogger.getResultsCount()
                val batteryCount = dataLogger.getBatteryMetricsCount()
                
                Toast.makeText(
                    this@MainActivity,
                    "Benchmark completed. Queries: $queryCount, Battery metrics: $batteryCount",
                    Toast.LENGTH_LONG
                ).show()
                
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
            val batteryLevel = batteryMonitor.getCurrentBatteryLevel()
            binding.tvBatteryLevel.text = "Battery: $batteryLevel%"
            
            // Update queries completed
            val queryCount = dataLogger.getResultsCount()
            binding.tvQueriesCompleted.text = "Queries: $queryCount"
            
            // Update average inference time
            val avgInferenceTime = calculateAverageInferenceTime()
            binding.tvAvgInferenceTime.text = "Avg Time: ${avgInferenceTime}ms"
            
            // Update estimated battery life
            val estimatedLife = calculateEstimatedBatteryLife()
            binding.tvEstBatteryLife.text = "Est. Life: $estimatedLife"
            
            // Update button states
            binding.btnStartStop.text = if (isRunning) "Stop Benchmark" else "Start Benchmark"
            binding.btnStartStop.isEnabled = true
            
            // Update model selection state
            binding.spinnerModel.isEnabled = !isRunning
            
            // Update progress bar
            binding.progressBar.visibility = if (isRunning) View.VISIBLE else View.GONE
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating UI", e)
        }
    }
    
    /**
     * Selects a model from the spinner.
     */
    private fun selectModelFromSpinner() {
        val selectedIndex = binding.spinnerModel.selectedItemPosition
        if (selectedIndex >= 0 && selectedIndex < availableModels.size) {
            selectedModel = availableModels[selectedIndex]
            Toast.makeText(this, "Selected model: ${selectedModel!!.modelName}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Exports results to CSV and shows success dialog.
     */
    private fun exportResults() {
        lifecycleScope.launch {
            try {
                binding.btnExport.isEnabled = false
                
                // Export to CSV
                val exportedFile = dataLogger.exportToCSV()
                
                if (exportedFile != null) {
                    showExportSuccessDialog(exportedFile.absolutePath)
                } else {
                    Toast.makeText(this@MainActivity, "Failed to export results", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting results", e)
                Toast.makeText(this@MainActivity, "Error exporting results: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnExport.isEnabled = true
            }
        }
    }
    
    /**
     * Loads available models from assets.
     */
    private fun loadAvailableModels() {
        try {
            val modelFiles = LLMService.getAvailableModels(this)
            
            availableModels.clear()
            
            modelFiles.forEach { modelName ->
                val quantization = detectQuantizationFromName(modelName)
                val modelConfig = ModelConfig.create(
                    modelName = modelName.removeSuffix(".gguf"),
                    modelPath = "assets/models/$modelName",
                    quantization = quantization,
                    sizeInMB = 0f // Will be updated when model is loaded
                )
                availableModels.add(modelConfig)
            }
            
            // Update model spinner
            setupModelSpinner()
            
            Log.d(TAG, "Loaded ${availableModels.size} available models")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading available models", e)
            Toast.makeText(this, "Error loading models: ${e.message}", Toast.LENGTH_LONG).show()
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
        val results = dataLogger.getQueryResults()
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
        val batteryLevel = batteryMonitor.getCurrentBatteryLevel()
        val drainRate = batteryMonitor.getBatteryDrainRate()
        
        return if (drainRate > 0) {
            val hoursRemaining = batteryLevel / drainRate
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
     * Shows success dialog after export with file location.
     */
    private fun showExportSuccessDialog(filePath: String) {
        AlertDialog.Builder(this)
            .setTitle("Export Successful")
            .setMessage("Results exported to:\n$filePath")
            .setPositiveButton("OK", null)
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
     * Cleans up resources.
     */
    private fun cleanup() {
        try {
            // Stop UI updates
            stopUIUpdates()
            
            // Stop benchmark if running
            if (isRunning) {
                stopBenchmark()
            }
            
            // Cleanup components
            llmService.cleanup()
            batteryMonitor.cleanup()
            
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
