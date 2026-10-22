package com.recall.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class DownloadState {
    object Idle : DownloadState()
    data class Progress(
        val downloadedMb: Float,
        val totalMb: Float,
        val percent: Int
    ) : DownloadState()
    data class Success(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloadManager(private val context: Context) {

    companion object {
        // Official open-source Whisper tiny GGML model (~75 MB)
        const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
        const val MODEL_FILE_NAME = "whisper_tiny.bin"
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    fun getModelFile(): File {
        val modelsDir = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
        return File(modelsDir, MODEL_FILE_NAME)
    }

    fun isModelReady(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > 10_000_000L // Expected ~75 MB
    }

    suspend fun startDownload(downloadUrl: String = MODEL_URL): Boolean = withContext(Dispatchers.IO) {
        val targetFile = getModelFile()
        val tempFile = File(targetFile.parentFile, "$MODEL_FILE_NAME.tmp")

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            val url = URL(downloadUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode !in 200..299) {
                _downloadState.value = DownloadState.Error("Server error: HTTP ${connection.responseCode}")
                return@withContext false
            }

            val totalBytes = connection.contentLengthLong
            val totalMb = if (totalBytes > 0) totalBytes / (1024f * 1024f) else 75f

            inputStream = connection.inputStream
            outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(16384)
            var totalRead: Long = 0
            var lastUpdateMs = System.currentTimeMillis()
            var bytesRead = inputStream.read(buffer)

            while (isActive && bytesRead != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                val now = System.currentTimeMillis()
                // Update live counter every 150ms to keep UI smooth without flooding
                if (now - lastUpdateMs > 150) {
                    val downloadedMb = totalRead / (1024f * 1024f)
                    val percent = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt() else 0
                    _downloadState.value = DownloadState.Progress(downloadedMb, totalMb, percent)
                    lastUpdateMs = now
                }
                bytesRead = inputStream.read(buffer)
            }

            outputStream.flush()

            if (isActive) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                _downloadState.value = DownloadState.Success(targetFile)
                return@withContext true
            } else {
                tempFile.delete()
                _downloadState.value = DownloadState.Idle
                return@withContext false
            }
        } catch (e: Exception) {
            tempFile.delete()
            _downloadState.value = DownloadState.Error("Download failed: ${e.message}")
            return@withContext false
        } finally {
            try { outputStream?.close() } catch (ignored: Exception) {}
            try { inputStream?.close() } catch (ignored: Exception) {}
            connection?.disconnect()
        }
    }
}
