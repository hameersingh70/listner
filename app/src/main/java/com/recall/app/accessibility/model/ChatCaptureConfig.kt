package com.recall.app.accessibility.model

data class ChatCaptureConfig(
    val debounceIntervalMs: Long = 600L,
    val throttleIntervalMs: Long = 3000L,
    val webpQuality: Int = 80,
    val maxStorageQuotaMb: Long = 500L,
    val retentionPeriodDays: Int = 14
)
