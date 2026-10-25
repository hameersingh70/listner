package com.recall.app.accessibility.model

import java.io.File

data class CapturedSnapshot(
    val id: String,
    val packageName: String,
    val chatTitle: String,
    val timestamp: Long,
    val imageFile: File?,
    val metadataFile: File,
    val messageCount: Int,
    val contentHash: String,
    val messagesSnippet: List<String>
)
