package com.research.llmbattery

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Simplified MainActivity for testing - no ViewBinding, minimal functionality
 */
class MainActivitySimple : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Use a simple layout
        setContentView(android.R.layout.activity_list_item)
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        Log.d(TAG, "MainActivitySimple created successfully!")
        
        // Try to find a TextView and set some text
        try {
            val textView = findViewById<TextView>(android.R.id.text1)
            textView?.text = "LLM Battery Benchmark - App is working!"
        } catch (e: Exception) {
            Log.e(TAG, "Error setting text", e)
        }
    }

    companion object {
        private const val TAG = "MainActivitySimple"
    }
}
