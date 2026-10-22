package com.recall.app.ai

data class DailySummary(
    val phoneNumber: String,
    val date: String,
    val callCount: Int,
    val totalDurationSeconds: Long,
    val recordingFiles: List<String>,
    val overview: String,
    val decisions: List<String>,
    val actionItems: List<String>,
    val fullTranscript: String
)
