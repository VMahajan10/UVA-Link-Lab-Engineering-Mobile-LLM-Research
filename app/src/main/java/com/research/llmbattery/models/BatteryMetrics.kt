package com.research.llmbattery.models

/**
 * Data class representing battery and system performance metrics at a specific point in time.
 * Contains information about battery level, drain rate, CPU usage, memory usage, and temperature.
 */
data class BatteryMetrics(
    val timestamp: Long,
    val batteryLevel: Int,
    val batteryDrainRate: Float,
    val cpuUsage: Float,
    val memoryUsage: Long,
    val temperature: Float
) {
    companion object {
        /**
         * Factory method for creating a BatteryMetrics instance.
         * @param timestamp Timestamp when the metrics were recorded
         * @param batteryLevel Battery level at the timestamp (0-100)
         * @param batteryDrainRate Battery drain rate in percentage per minute
         * @param cpuUsage CPU usage percentage (0.0-100.0)
         * @param memoryUsage Memory usage in bytes
         * @param temperature Device temperature in Celsius
         * @return A new BatteryMetrics instance
         */
        fun create(
            timestamp: Long,
            batteryLevel: Int,
            batteryDrainRate: Float,
            cpuUsage: Float,
            memoryUsage: Long,
            temperature: Float
        ): BatteryMetrics {
            return BatteryMetrics(
                timestamp = timestamp,
                batteryLevel = batteryLevel,
                batteryDrainRate = batteryDrainRate,
                cpuUsage = cpuUsage,
                memoryUsage = memoryUsage,
                temperature = temperature
            )
        }
        
        /**
         * Creates a sample BatteryMetrics for testing with default values.
         * @return A sample BatteryMetrics instance
         */
        fun createSample(): BatteryMetrics {
            return BatteryMetrics(
                timestamp = System.currentTimeMillis(),
                batteryLevel = 85,
                batteryDrainRate = 2.5f,
                cpuUsage = 45.0f,
                memoryUsage = 1024 * 1024 * 512L, // 512 MB
                temperature = 35.5f
            )
        }
        
        /**
         * Creates a BatteryMetrics instance with current system time.
         * @param batteryLevel Battery level at the timestamp (0-100)
         * @param batteryDrainRate Battery drain rate in percentage per minute
         * @param cpuUsage CPU usage percentage (0.0-100.0)
         * @param memoryUsage Memory usage in bytes
         * @param temperature Device temperature in Celsius
         * @return A new BatteryMetrics instance with current timestamp
         */
        fun createNow(
            batteryLevel: Int,
            batteryDrainRate: Float,
            cpuUsage: Float,
            memoryUsage: Long,
            temperature: Float
        ): BatteryMetrics {
            return BatteryMetrics(
                timestamp = System.currentTimeMillis(),
                batteryLevel = batteryLevel,
                batteryDrainRate = batteryDrainRate,
                cpuUsage = cpuUsage,
                memoryUsage = memoryUsage,
                temperature = temperature
            )
        }
    }
}
