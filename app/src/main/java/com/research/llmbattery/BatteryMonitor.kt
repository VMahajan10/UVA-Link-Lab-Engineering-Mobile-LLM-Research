package com.research.llmbattery

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Debug
import android.os.Process
import androidx.core.content.ContextCompat
import com.research.llmbattery.models.BatteryMetrics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * BatteryMonitor class responsible for monitoring battery consumption and system metrics
 * during LLM inference operations. Uses Android's BatteryManager and system APIs to
 * track battery drain, CPU usage, memory consumption, and device temperature.
 * 
 * Features:
 * - Real-time battery level monitoring via BroadcastReceiver
 * - CPU usage calculation from /proc/stat
 * - Memory usage tracking via ActivityManager
 * - Temperature monitoring from battery status
 * - Coroutine-based background monitoring
 * - Comprehensive error handling and permission management
 */
class BatteryMonitor(
    private val context: Context
) {
    // Core properties
    private val batteryManager: BatteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private var startBatteryLevel: Int = 0
    private var currentBatteryLevel: Int = 0
    private var startTime: Long = 0L
    private val metrics: MutableList<BatteryMetrics> = mutableListOf()
    
    // Monitoring state
    private var isMonitoring = false
    private var monitoringJob: Job? = null
    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Battery state tracking
    private val _batteryLevelFlow = MutableStateFlow(0)
    val batteryLevelFlow: StateFlow<Int> = _batteryLevelFlow.asStateFlow()
    
    // BroadcastReceiver for battery changes
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level != -1 && scale != -1) {
                    currentBatteryLevel = (level * 100 / scale.toFloat()).roundToInt()
                    _batteryLevelFlow.value = currentBatteryLevel
                }
            }
        }
    }
    
    /**
     * Starts battery monitoring by registering the BroadcastReceiver and initializing
     * tracking variables. This method should be called when beginning LLM operations.
     */
    fun startMonitoring() {
        if (isMonitoring) {
            return // Already monitoring
        }
        
        try {
            // Register battery change receiver
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(batteryReceiver, filter)
            
            // Initialize monitoring state
            startBatteryLevel = getCurrentBatteryLevel()
            currentBatteryLevel = startBatteryLevel
            startTime = System.currentTimeMillis()
            isMonitoring = true
            
            // Start background monitoring coroutine
            monitoringJob = monitoringScope.launch {
                while (isMonitoring) {
                    try {
                        // Log metrics every 5 seconds during monitoring
                        val batteryMetrics = logMetrics()
                        withContext(Dispatchers.Main) {
                            // Update UI if needed
                        }
                        delay(5000) // Monitor every 5 seconds
                    } catch (e: Exception) {
                        // Log error but continue monitoring
                        e.printStackTrace()
                    }
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            isMonitoring = false
        }
    }
    
    /**
     * Stops battery monitoring and unregisters the BroadcastReceiver.
     * This method should be called when LLM operations are complete.
     */
    fun stopMonitoring() {
        if (!isMonitoring) {
            return
        }
        
        try {
            isMonitoring = false
            monitoringJob?.cancel()
            
            // Unregister battery receiver
            context.unregisterReceiver(batteryReceiver)
            
            // Log final metrics
            logMetrics()
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Gets the current battery level as a percentage (0-100).
     * Uses Android's BatteryManager for accurate battery level reading.
     * 
     * @return Current battery level percentage, or -1 if unable to read
     */
    fun getCurrentBatteryLevel(): Int {
        return try {
            val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (batteryLevel != Integer.MIN_VALUE) {
                currentBatteryLevel = batteryLevel
                batteryLevel
            } else {
                // Fallback to manual calculation if BatteryManager fails
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level != -1 && scale != -1) {
                    val percentage = (level * 100 / scale.toFloat()).roundToInt()
                    currentBatteryLevel = percentage
                    percentage
                } else {
                    -1
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }
    
    /**
     * Calculates the battery drain rate as percentage per hour.
     * Based on the difference between start and current battery levels
     * and the elapsed time since monitoring started.
     * 
     * @return Battery drain rate in percentage per hour, or 0.0 if calculation fails
     */
    fun getBatteryDrainRate(): Float {
        return try {
            if (startTime == 0L || startBatteryLevel == 0) {
                0.0f
            }
            
            val currentTime = System.currentTimeMillis()
            val elapsedTimeHours = (currentTime - startTime) / (1000.0 * 60 * 60) // Convert to hours
            
            if (elapsedTimeHours <= 0) {
                0.0f
            }
            
            val batteryDrained = startBatteryLevel - currentBatteryLevel
            val drainRate = (batteryDrained / elapsedTimeHours).toFloat()
            
            max(0.0f, drainRate) // Ensure non-negative drain rate
        } catch (e: Exception) {
            e.printStackTrace()
            0.0f
        }
    }
    
    /**
     * Gets the current CPU usage percentage by reading /proc/stat.
     * This method calculates CPU usage by comparing idle time over a short interval.
     * 
     * @return CPU usage percentage (0.0-100.0), or 0.0 if unable to read
     */
    fun getCPUUsage(): Float {
        return try {
            val reader1 = BufferedReader(FileReader("/proc/stat"))
            val line1 = reader1.readLine()
            reader1.close()
            
            // Wait 100ms for more accurate reading
            Thread.sleep(100)
            
            val reader2 = BufferedReader(FileReader("/proc/stat"))
            val line2 = reader2.readLine()
            reader2.close()
            
            val values1 = line1.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
            val values2 = line2.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
            
            if (values1.size >= 4 && values2.size >= 4) {
                val idle1 = values1[3]
                val idle2 = values2[3]
                val total1 = values1.sum()
                val total2 = values2.sum()
                
                val idleDiff = idle2 - idle1
                val totalDiff = total2 - total1
                
                if (totalDiff > 0) {
                    val cpuUsage = ((totalDiff - idleDiff) * 100.0 / totalDiff).toFloat()
                    max(0.0f, minOf(100.0f, cpuUsage))
                } else {
                    0.0f
                }
            } else {
                0.0f
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0.0f
        }
    }
    
    /**
     * Gets the current memory usage of the application in bytes.
     * Uses ActivityManager to get detailed memory information for the current process.
     * 
     * @return Memory usage in bytes, or 0 if unable to read
     */
    fun getMemoryUsage(): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            // Get process memory info
            val pid = Process.myPid()
            val memoryInfos = activityManager.getProcessMemoryInfo(intArrayOf(pid))
            
            if (memoryInfos.isNotEmpty()) {
                val memoryInfo = memoryInfos[0]
                // Total PSS (Proportional Set Size) in KB, convert to bytes
                (memoryInfo.totalPss * 1024).toLong()
            } else {
                // Fallback to Debug.getNativeHeapSize()
                Debug.getNativeHeapSize()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }
    
    /**
     * Gets the current device temperature from battery status.
     * Temperature is provided in tenths of a degree Celsius.
     * 
     * @return Temperature in Celsius, or 0.0 if unable to read
     */
    private fun getTemperature(): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (temperature != -1) {
                temperature / 10.0f // Convert from tenths of degree to degrees
            } else {
                0.0f
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0.0f
        }
    }
    
    /**
     * Creates a snapshot of current battery and system metrics.
     * This method collects all current measurements and creates a BatteryMetrics object.
     * 
     * @return BatteryMetrics object with current system state
     */
    fun logMetrics(): BatteryMetrics {
        val currentTime = System.currentTimeMillis()
        val batteryLevel = getCurrentBatteryLevel()
        val batteryDrainRate = getBatteryDrainRate()
        val cpuUsage = getCPUUsage()
        val memoryUsage = getMemoryUsage()
        val temperature = getTemperature()
        
        val batteryMetrics = BatteryMetrics.create(
            timestamp = currentTime,
            batteryLevel = batteryLevel,
            batteryDrainRate = batteryDrainRate,
            cpuUsage = cpuUsage,
            memoryUsage = memoryUsage,
            temperature = temperature
        )
        
        // Add to metrics history
        synchronized(metrics) {
            metrics.add(batteryMetrics)
        }
        
        return batteryMetrics
    }
    
    /**
     * Returns a copy of the logged metrics history.
     * This provides access to all previously recorded battery metrics.
     * 
     * @return List of BatteryMetrics objects in chronological order
     */
    fun getMetricsHistory(): List<BatteryMetrics> {
        return synchronized(metrics) {
            metrics.toList()
        }
    }
    
    /**
     * Clears all logged metrics history.
     * Useful for starting fresh monitoring sessions.
     */
    fun clearMetrics() {
        synchronized(metrics) {
            metrics.clear()
        }
    }
    
    /**
     * Gets the total number of logged metrics.
     * 
     * @return Count of logged BatteryMetrics objects
     */
    fun getMetricsCount(): Int {
        return synchronized(metrics) {
            metrics.size
        }
    }
    
    /**
     * Checks if monitoring is currently active.
     * 
     * @return True if monitoring is active, false otherwise
     */
    fun isMonitoring(): Boolean = isMonitoring
    
    /**
     * Cleanup method to properly dispose of resources.
     * Should be called when the BatteryMonitor is no longer needed.
     */
    fun cleanup() {
        stopMonitoring()
        monitoringScope.cancel()
    }
}
