package com.research.llmbattery

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker that executes when the benchmark duration expires.
 * Stops the benchmark and exports results to CSV files saved in the phone's files section.
 */
class DurationExpiredWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "DurationExpiredWorker"
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Duration expired - stopping benchmark and exporting results")
            
            // Stop query scheduling
            QueryScheduler.cancelSchedule(applicationContext)
            Log.i(TAG, "Cancelled query scheduling")
            
            // Export results to CSV (saved in phone's files section)
            val dataLogger = DataLogger(applicationContext)
            val exportedFile = dataLogger.exportToCSV()
            
            if (exportedFile != null) {
                Log.i(TAG, "Results exported successfully to: ${exportedFile.absolutePath}")
                Log.i(TAG, "CSV files are saved in: ${exportedFile.parent}")
                Result.success()
            } else {
                Log.e(TAG, "Failed to export results")
                Result.retry()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in duration expired worker", e)
            Result.retry()
        }
    }
}

