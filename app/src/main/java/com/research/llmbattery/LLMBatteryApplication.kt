package com.research.llmbattery

import android.app.Application
import android.util.Log

/**
 * Application class for the LLM Battery Benchmark app.
 * Handles global initialization and configuration.
 */
class LLMBatteryApplication : Application() {
    
    companion object {
        private const val TAG = "LLMBatteryApplication"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize application
        initializeApp()
        
        Log.d(TAG, "LLM Battery Benchmark Application started")
    }
    
    /**
     * Initializes the application with necessary configurations.
     */
    private fun initializeApp() {
        try {
            // Set up logging
            setupLogging()
            
            // Initialize WorkManager
            initializeWorkManager()
            
            // Set up crash reporting (if needed)
            setupCrashReporting()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing application", e)
        }
    }
    
    /**
     * Sets up application logging configuration.
     */
    private fun setupLogging() {
        // Configure logging levels based on build type
        Log.d(TAG, "Logging enabled")
    }
    
    /**
     * Initializes WorkManager for background task scheduling.
     */
    private fun initializeWorkManager() {
        try {
            // WorkManager is automatically initialized by the framework
            // Additional configuration can be added here if needed
            Log.d(TAG, "WorkManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WorkManager", e)
        }
    }
    
    /**
     * Sets up crash reporting and error handling.
     */
    private fun setupCrashReporting() {
        try {
            // Add crash reporting setup here if needed
            // For example, Firebase Crashlytics, Bugsnag, etc.
            Log.d(TAG, "Crash reporting initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up crash reporting", e)
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        Log.d(TAG, "Application terminated")
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory warning received")
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "Memory trim requested: level $level")
    }
}
