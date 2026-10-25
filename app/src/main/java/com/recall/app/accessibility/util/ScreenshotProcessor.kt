package com.recall.app.accessibility.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import androidx.annotation.RequiresApi
import com.recall.app.accessibility.model.CapturedSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object ScreenshotProcessor {

    /**
     * Safely wraps the HardwareBuffer, guarantees its closure to prevent GPU leaks,
     * copies to a software bitmap, optionally crops to content bounds, and encodes to WebP.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun processHardwareBuffer(
        context: Context,
        hardwareBuffer: HardwareBuffer,
        colorSpace: ColorSpace?,
        packageName: String,
        chatTitle: String,
        contentHash: String,
        snippets: List<String>,
        cropRect: Rect?,
        quality: Int = 80
    ): CapturedSnapshot? {
        val targetDir = context.getExternalFilesDir("ChatSnapshots")
            ?: File(context.filesDir, "ChatSnapshots")
        if (!targetDir.exists()) targetDir.mkdirs()

        val timestamp = System.currentTimeMillis()
        val cleanPkg = packageName.replace(".", "_")
        val imageFile = File(targetDir, "${cleanPkg}_${timestamp}.webp")
        val metadataFile = File(targetDir, "${cleanPkg}_${timestamp}.json")

        var softwareBitmap: Bitmap? = null
        var croppedBitmap: Bitmap? = null

        try {
            // 1. Wrap and immediately copy to software Bitmap
            val hardwareBitmap = if (colorSpace != null) {
                Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
            } else {
                Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
            } ?: return null

            softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            hardwareBitmap.recycle()

            // 2. Crop if requested and bounds are valid
            val finalBitmap = if (cropRect != null && isRectValid(cropRect, softwareBitmap.width, softwareBitmap.height)) {
                croppedBitmap = Bitmap.createBitmap(
                    softwareBitmap,
                    cropRect.left,
                    cropRect.top,
                    cropRect.width(),
                    cropRect.height()
                )
                croppedBitmap
            } else {
                softwareBitmap
            }

            // 3. Encode to WebP
            FileOutputStream(imageFile).use { out ->
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                finalBitmap.compress(format, quality, out)
            }

            // 4. Save JSON metadata
            val metadataJson = JSONObject().apply {
                put("packageName", packageName)
                put("chatTitle", chatTitle)
                put("timestamp", timestamp)
                put("contentHash", contentHash)
                put("messageCount", snippets.size)
                put("snippets", JSONArray(snippets))
                put("imageFile", imageFile.name)
            }
            metadataFile.writeText(metadataJson.toString(2))

            // 5. Automatic rolling storage prune
            pruneStorage(targetDir, maxQuotaMb = 500L, retentionDays = 14)

            return CapturedSnapshot(
                id = "${cleanPkg}_${timestamp}",
                packageName = packageName,
                chatTitle = chatTitle,
                timestamp = timestamp,
                imageFile = imageFile,
                metadataFile = metadataFile,
                messageCount = snippets.size,
                contentHash = contentHash,
                messagesSnippet = snippets
            )
        } catch (e: Exception) {
            imageFile.delete()
            metadataFile.delete()
            return null
        } finally {
            // CRITICAL: Always close HardwareBuffer to free graphics memory
            try {
                hardwareBuffer.close()
            } catch (ignored: Exception) {}
            softwareBitmap?.recycle()
            croppedBitmap?.recycle()
        }
    }

    /**
     * Fallback for devices without screenshot capability (or API 29): writes metadata only.
     */
    fun saveTextMetadataOnly(
        context: Context,
        packageName: String,
        chatTitle: String,
        contentHash: String,
        snippets: List<String>
    ): CapturedSnapshot {
        val targetDir = context.getExternalFilesDir("ChatSnapshots")
            ?: File(context.filesDir, "ChatSnapshots")
        if (!targetDir.exists()) targetDir.mkdirs()

        val timestamp = System.currentTimeMillis()
        val cleanPkg = packageName.replace(".", "_")
        val metadataFile = File(targetDir, "${cleanPkg}_${timestamp}.json")

        val metadataJson = JSONObject().apply {
            put("packageName", packageName)
            put("chatTitle", chatTitle)
            put("timestamp", timestamp)
            put("contentHash", contentHash)
            put("messageCount", snippets.size)
            put("snippets", JSONArray(snippets))
            put("imageFile", null)
        }
        metadataFile.writeText(metadataJson.toString(2))

        return CapturedSnapshot(
            id = "${cleanPkg}_${timestamp}",
            packageName = packageName,
            chatTitle = chatTitle,
            timestamp = timestamp,
            imageFile = null,
            metadataFile = metadataFile,
            messageCount = snippets.size,
            contentHash = contentHash,
            messagesSnippet = snippets
        )
    }

    private fun isRectValid(rect: Rect, maxWidth: Int, maxHeight: Int): Boolean {
        return rect.left in 0 until rect.right &&
                rect.right <= maxWidth &&
                rect.top in 0 until rect.bottom &&
                rect.bottom <= maxHeight &&
                rect.width() > 50 &&
                rect.height() > 50
    }

    /**
     * Enforces storage retention policy: deletes files older than retentionDays,
     * and deletes oldest files if total size exceeds maxQuotaMb.
     */
    fun pruneStorage(directory: File, maxQuotaMb: Long = 500L, retentionDays: Int = 14) {
        try {
            val allFiles = directory.listFiles() ?: return
            val cutoff = System.currentTimeMillis() - (retentionDays * 86_400_000L)

            // Prune expired files
            allFiles.filter { it.lastModified() < cutoff }.forEach { it.delete() }

            // Prune by total quota
            val remaining = directory.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: return
            var totalBytes = remaining.sumOf { it.length() }
            val maxBytes = maxQuotaMb * 1024L * 1024L

            while (totalBytes > maxBytes && remaining.isNotEmpty()) {
                val oldest = remaining.removeAt(0)
                totalBytes -= oldest.length()
                oldest.delete()
            }
        } catch (ignored: Exception) {}
    }
}
