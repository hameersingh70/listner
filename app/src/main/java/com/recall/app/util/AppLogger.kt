package com.recall.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val TAG = "CallMonitor"
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            logFile = File(dir, "app_debug.log")
        } catch (ignored: Exception) {}
    }

    @Synchronized
    fun log(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = "[$timestamp] $message"

        Log.i(TAG, logLine)

        try {
            logFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.appendLine(logLine)
                }
            }
        } catch (ignored: Exception) {}
    }

    fun getLogs(): String {
        return try {
            logFile?.readText() ?: "No logs recorded."
        } catch (e: Exception) {
            "Error reading log: ${e.message}"
        }
    }

    fun clear() {
        try {
            logFile?.writeText("")
        } catch (ignored: Exception) {}
    }
}
