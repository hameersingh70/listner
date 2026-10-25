package com.recall.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.recall.app.accessibility.model.ChatCaptureConfig
import com.recall.app.accessibility.util.NodeHierarchyParser
import com.recall.app.accessibility.util.ScreenshotProcessor
import com.recall.app.data.ChatTargetStore
import com.recall.app.util.AppLogger
import kotlinx.coroutines.*

class ChatAccessibilityService : AccessibilityService() {

    private lateinit var targetStore: ChatTargetStore
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val config = ChatCaptureConfig()

    private var debounceJob: Job? = null
    private var lastCaptureTimestamp = 0L
    private var lastContentHash = ""

    companion object {
        const val ACTION_SNAPSHOTS_UPDATED = "com.recall.app.action.SNAPSHOTS_UPDATED"
        var isServiceConnected = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        targetStore = ChatTargetStore(this)
        isServiceConnected = true
        AppLogger.log("ChatAccessibilityService: Connected and ready")
        notifyStatusChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (!targetStore.isPackageMonitored(packageName)) return

        // Debounce processing to ensure scrolling/animations have stopped
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(config.debounceIntervalMs)
            processActiveWindow(packageName)
        }
    }

    private suspend fun processActiveWindow(packageName: String) {
        val rootNode = rootInActiveWindow ?: return
        val parsed = NodeHierarchyParser.parse(rootNode)

        // Validate chat title
        if (!targetStore.isChatTitleEligible(parsed.chatTitle)) {
            return
        }

        // Deduplication: Avoid duplicate captures of identical screen text
        if (parsed.contentHash.isNotEmpty() && parsed.contentHash == lastContentHash) {
            return
        }

        // Throttling: Minimum interval between captures
        val now = System.currentTimeMillis()
        if (now - lastCaptureTimestamp < config.throttleIntervalMs) {
            return
        }

        lastCaptureTimestamp = now
        lastContentHash = parsed.contentHash
        AppLogger.log("ChatAccessibilityService: Target matched: $packageName ('${parsed.chatTitle}'), capturing...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+ native screenshot support
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        serviceScope.launch(Dispatchers.IO) {
                            val snapshot = ScreenshotProcessor.processHardwareBuffer(
                                context = applicationContext,
                                hardwareBuffer = screenshotResult.hardwareBuffer,
                                colorSpace = screenshotResult.colorSpace,
                                packageName = packageName,
                                chatTitle = parsed.chatTitle,
                                contentHash = parsed.contentHash,
                                snippets = parsed.messageSnippets,
                                cropRect = parsed.contentBounds,
                                quality = config.webpQuality
                            )
                            if (snapshot != null) {
                                AppLogger.log("ChatAccessibilityService: Saved snapshot ${snapshot.id}.webp")
                                notifySnapshotCaptured()
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        AppLogger.log("ChatAccessibilityService: takeScreenshot failed ($errorCode), saving metadata only")
                        ScreenshotProcessor.saveTextMetadataOnly(
                            context = applicationContext,
                            packageName = packageName,
                            chatTitle = parsed.chatTitle,
                            contentHash = parsed.contentHash,
                            snippets = parsed.messageSnippets
                        )
                        notifySnapshotCaptured()
                    }
                }
            )
        } else {
            // API 29 fallback: text metadata only
            ScreenshotProcessor.saveTextMetadataOnly(
                context = applicationContext,
                packageName = packageName,
                chatTitle = parsed.chatTitle,
                contentHash = parsed.contentHash,
                snippets = parsed.messageSnippets
            )
            notifySnapshotCaptured()
        }
    }

    override fun onInterrupt() {
        AppLogger.log("ChatAccessibilityService: onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceConnected = false
        debounceJob?.cancel()
        serviceJob.cancel()
        AppLogger.log("ChatAccessibilityService: Destroyed")
        notifyStatusChanged()
    }

    private fun notifySnapshotCaptured() {
        try {
            val intent = Intent(ACTION_SNAPSHOTS_UPDATED).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (ignored: Exception) {}
    }

    private fun notifyStatusChanged() {
        try {
            val intent = Intent(ACTION_SNAPSHOTS_UPDATED).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (ignored: Exception) {}
    }
}
