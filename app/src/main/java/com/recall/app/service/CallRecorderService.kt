package com.recall.app.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.recall.app.R
import com.recall.app.data.PhoneNumberStore
import com.recall.app.util.AppLogger
import com.recall.app.util.CallLogHelper
import kotlinx.coroutines.*
import java.io.File

class CallRecorderService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentRecordingFile: File? = null
    private var isPendingOutgoing = false
    private var isPendingIncoming = false
    private var currentTargetNumber: String? = null
    private var pendingCallStartTime: Long = 0L

    private lateinit var phoneStore: PhoneNumberStore
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var dynamicReceiver: BroadcastReceiver? = null
    private var telephonyCallback: Any? = null
    private var phoneStateListener: PhoneStateListener? = null

    private var lastCallState = TelephonyManager.CALL_STATE_IDLE
    private var currentIncomingNumber: String? = null
    private var detectedOutgoingNumber: String? = null

    companion object {
        const val ACTION_START_RECORDING = "com.recall.app.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.recall.app.action.STOP_RECORDING"
        const val ACTION_START_MONITOR = "com.recall.app.action.START_MONITOR"
        const val ACTION_RECORDINGS_UPDATED = "com.recall.app.action.RECORDINGS_UPDATED"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"

        private const val CHANNEL_ID = "call_monitor_silent_channel"
        private const val NOTIFICATION_ID = 2001

        fun startMonitor(context: Context) {
            val intent = Intent(context, CallRecorderService::class.java).apply {
                action = ACTION_START_MONITOR
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.log("Failed to start service: ${e.message}")
            }
        }

        fun startRecording(context: Context, phoneNumber: String) {
            val intent = Intent(context, CallRecorderService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.log("Failed to start recording: ${e.message}")
            }
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, CallRecorderService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.log("Failed to stop recording: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.log("CallRecorderService: onCreate started")

        phoneStore = PhoneNumberStore(this)
        getRecordingsDir()

        createSilentNotificationChannel()
        startMinimalForeground()
        registerDynamicTelephonyListeners()

        AppLogger.log("CallRecorderService: Running in foreground and monitoring calls")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMinimalForeground()

        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val number = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                AppLogger.log("Received ACTION_START_RECORDING for: $number")
                handleStartWhitelistedRecording(number)
            }
            ACTION_STOP_RECORDING -> {
                AppLogger.log("Received ACTION_STOP_RECORDING")
                handleStopRecording()
            }
            ACTION_START_MONITOR -> {
                AppLogger.log("Service monitor active")
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.log("CallRecorderService: onDestroy")
        handleStopRecording()
        unregisterDynamicTelephonyListeners()
        serviceJob.cancel()
    }

    private fun getRecordingsDir(): File {
        val dir = getExternalFilesDir("Recordings") ?: File(filesDir, "Recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    // ------------------------------------------------------------------------
    // RECORDING LOGIC WITH AUDIO SOURCE FALLBACKS
    // ------------------------------------------------------------------------

    private fun createConfiguredRecorder(outputFile: File): MediaRecorder {
        val sourcesToTry = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT
        )

        var lastError: Exception? = null

        for (source in sourcesToTry) {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            try {
                recorder.apply {
                    setAudioSource(source)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(64000)
                    setAudioChannels(1)
                    setOutputFile(outputFile.absolutePath)
                    prepare()
                    start()
                }
                AppLogger.log("MediaRecorder started successfully with AudioSource: $source -> ${outputFile.name}")
                return recorder
            } catch (e: Exception) {
                AppLogger.log("AudioSource $source failed: ${e.message}, trying next fallback...")
                lastError = e
                try {
                    recorder.reset()
                    recorder.release()
                } catch (ignored: Exception) {}
            }
        }

        throw lastError ?: RuntimeException("All audio sources failed to initialize")
    }

    @Synchronized
    private fun handleStartWhitelistedRecording(rawNumber: String) {
        val normalized = PhoneNumberStore.normalize(rawNumber)
        if (!phoneStore.isWhitelisted(normalized)) {
            AppLogger.log("Number $normalized is NOT whitelisted. Skipping.")
            return
        }

        if (isRecording) {
            AppLogger.log("Already recording, skipping duplicate request.")
            return
        }

        try {
            val dir = getRecordingsDir()
            val timestamp = System.currentTimeMillis()
            val fileName = "${normalized}_${timestamp}.m4a"
            val outputFile = File(dir, fileName)
            currentRecordingFile = outputFile
            currentTargetNumber = normalized
            isPendingOutgoing = false
            isPendingIncoming = false

            mediaRecorder = createConfiguredRecorder(outputFile)
            isRecording = true
            AppLogger.log("Started recording whitelisted contact: $normalized")
        } catch (e: Exception) {
            AppLogger.log("Error starting whitelisted recording: ${e.message}")
            currentRecordingFile?.delete()
            currentRecordingFile = null
            mediaRecorder = null
            isRecording = false
        }
    }

    @Synchronized
    private fun handleStartPendingRecording(isOutgoing: Boolean) {
        if (isRecording) {
            AppLogger.log("Already recording, ignoring pending start request")
            return
        }

        try {
            val dir = getRecordingsDir()
            val timestamp = System.currentTimeMillis()
            val prefix = if (isOutgoing) "pending_outgoing" else "pending_incoming"
            val fileName = "${prefix}_${timestamp}.m4a"
            val outputFile = File(dir, fileName)
            currentRecordingFile = outputFile
            isPendingOutgoing = isOutgoing
            isPendingIncoming = !isOutgoing
            currentTargetNumber = null
            pendingCallStartTime = timestamp

            mediaRecorder = createConfiguredRecorder(outputFile)
            isRecording = true
            AppLogger.log("Started tentative call recording ($prefix) -> ${outputFile.name}")
        } catch (e: Exception) {
            AppLogger.log("Error starting tentative recording: ${e.message}")
            currentRecordingFile?.delete()
            currentRecordingFile = null
            mediaRecorder = null
            isRecording = false
            isPendingOutgoing = false
            isPendingIncoming = false
        }
    }

    @Synchronized
    private fun handleStopRecording() {
        if (!isRecording && mediaRecorder == null) {
            return
        }

        AppLogger.log("Stopping recording session...")
        val fileToProcess = currentRecordingFile
        val wasPendingOutgoing = isPendingOutgoing
        val wasPendingIncoming = isPendingIncoming
        val wasTargetNumber = currentTargetNumber
        val callStart = pendingCallStartTime
        val preDetectedOutgoing = detectedOutgoingNumber

        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            AppLogger.log("MediaRecorder stopped and released successfully")
        } catch (e: Exception) {
            AppLogger.log("Exception during MediaRecorder stop/release: ${e.message}")
        } finally {
            mediaRecorder = null
            isRecording = false
            currentRecordingFile = null
            isPendingOutgoing = false
            isPendingIncoming = false
            currentTargetNumber = null
            detectedOutgoingNumber = null
        }

        if (fileToProcess != null && fileToProcess.exists()) {
            val fileSize = fileToProcess.length()
            AppLogger.log("Recorded file captured: ${fileToProcess.name} ($fileSize bytes)")

            if (wasPendingOutgoing) {
                serviceScope.launch {
                    AppLogger.log("Resolving outgoing call contact (preDetected='$preDetectedOutgoing')...")

                    // First check if number was caught at dial time
                    var resolvedNumber = preDetectedOutgoing

                    // If not, query CallLog with extended retry window (up to 25 seconds)
                    if (resolvedNumber.isNullOrBlank()) {
                        resolvedNumber = CallLogHelper.getLatestOutgoingNumber(
                            context = this@CallRecorderService,
                            callStartTime = callStart,
                            maxRetries = 25,
                            delayMs = 800L
                        )
                    }

                    if (!resolvedNumber.isNullOrBlank()) {
                        val normalized = PhoneNumberStore.normalize(resolvedNumber)
                        AppLogger.log("Resolved contact: $resolvedNumber -> normalized: $normalized")

                        if (phoneStore.isWhitelisted(normalized)) {
                            val timestamp = System.currentTimeMillis()
                            val finalFile = File(fileToProcess.parentFile, "${normalized}_${timestamp}.m4a")
                            try {
                                fileToProcess.copyTo(finalFile, overwrite = true)
                                fileToProcess.delete()
                                AppLogger.log("SUCCESS: Whitelisted outgoing call saved: ${finalFile.name} (${finalFile.length()} bytes)")
                                notifyRecordingsUpdated()
                                return@launch
                            } catch (e: Exception) {
                                AppLogger.log("Rename/copy failed: ${e.message}")
                            }
                        } else {
                            AppLogger.log("Contact $normalized is NOT in whitelist. Discarding audio.")
                            fileToProcess.delete()
                            return@launch
                        }
                    } else {
                        AppLogger.log("Could not resolve outgoing call contact after 25 attempts.")
                    }

                    // Check whitelist directly if only one contact exists
                    val currentWhitelist = phoneStore.getWhitelist()
                    if (currentWhitelist.size == 1 && fileSize > 5000) {
                        val singleNumber = currentWhitelist.first()
                        val timestamp = System.currentTimeMillis()
                        val finalFile = File(fileToProcess.parentFile, "${singleNumber}_${timestamp}.m4a")
                        fileToProcess.copyTo(finalFile, overwrite = true)
                        fileToProcess.delete()
                        AppLogger.log("SAVED using active whitelist target $singleNumber: ${finalFile.name}")
                        notifyRecordingsUpdated()
                        return@launch
                    }

                    fileToProcess.delete()
                }
            } else if (wasPendingIncoming) {
                serviceScope.launch {
                    val incomingNumber = CallLogHelper.getLatestIncomingNumber(
                        context = this@CallRecorderService,
                        callStartTime = callStart
                    )
                    if (!incomingNumber.isNullOrBlank()) {
                        val normalized = PhoneNumberStore.normalize(incomingNumber)
                        if (phoneStore.isWhitelisted(normalized)) {
                            val timestamp = System.currentTimeMillis()
                            val finalFile = File(fileToProcess.parentFile, "${normalized}_${timestamp}.m4a")
                            fileToProcess.copyTo(finalFile, overwrite = true)
                            fileToProcess.delete()
                            AppLogger.log("SUCCESS: Incoming call saved: ${finalFile.name}")
                            notifyRecordingsUpdated()
                            return@launch
                        }
                    }
                    fileToProcess.delete()
                }
            } else if (wasTargetNumber != null) {
                if (phoneStore.isWhitelisted(wasTargetNumber) && fileSize > 1000) {
                    AppLogger.log("SUCCESS: Saved direct recording: ${fileToProcess.name}")
                    notifyRecordingsUpdated()
                } else {
                    fileToProcess.delete()
                }
            }
        }
    }

    private fun notifyRecordingsUpdated() {
        try {
            val intent = Intent(ACTION_RECORDINGS_UPDATED).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            AppLogger.log("Error sending broadcast: ${e.message}")
        }
    }

    // ------------------------------------------------------------------------
    // TELEPHONY CALL STATE HANDLING
    // ------------------------------------------------------------------------

    fun onCallStateChanged(state: Int, incomingNumber: String?) {
        AppLogger.log("onCallStateChanged: state=$state (lastState=$lastCallState), incomingNumber=$incomingNumber")

        if (!incomingNumber.isNullOrBlank()) {
            currentIncomingNumber = incomingNumber
        }

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                AppLogger.log("State: CALL_STATE_RINGING (Incoming: $incomingNumber)")
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (lastCallState == TelephonyManager.CALL_STATE_RINGING) {
                    val rawNum = currentIncomingNumber ?: ""
                    val normalized = PhoneNumberStore.normalize(rawNum)
                    AppLogger.log("State: CALL_STATE_OFFHOOK (Incoming answered: raw='$rawNum', normalized='$normalized')")

                    if (phoneStore.isWhitelisted(normalized)) {
                        handleStartWhitelistedRecording(normalized)
                    } else if (normalized.isBlank()) {
                        handleStartPendingRecording(isOutgoing = false)
                    } else {
                        AppLogger.log("Incoming number $normalized not whitelisted. Ignoring.")
                    }
                } else if (lastCallState == TelephonyManager.CALL_STATE_IDLE) {
                    AppLogger.log("State: CALL_STATE_OFFHOOK (Outgoing call initiated)")
                    handleStartPendingRecording(isOutgoing = true)
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                AppLogger.log("State: CALL_STATE_IDLE (Call ended or missed)")
                handleStopRecording()
                currentIncomingNumber = null
            }
        }

        lastCallState = state
    }

    // ------------------------------------------------------------------------
    // DYNAMIC RECEIVERS & TELEPHONY CALLBACKS
    // ------------------------------------------------------------------------

    private fun registerDynamicTelephonyListeners() {
        // Dynamic BroadcastReceiver for phone state & outgoing calls
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                            AppLogger.log("DynamicReceiver: PHONE_STATE_CHANGED -> state=$stateStr, number=$number")

                            when (stateStr) {
                                TelephonyManager.EXTRA_STATE_RINGING -> onCallStateChanged(TelephonyManager.CALL_STATE_RINGING, number)
                                TelephonyManager.EXTRA_STATE_OFFHOOK -> onCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK, number)
                                TelephonyManager.EXTRA_STATE_IDLE -> onCallStateChanged(TelephonyManager.CALL_STATE_IDLE, null)
                            }
                        }
                        @Suppress("DEPRECATION")
                        Intent.ACTION_NEW_OUTGOING_CALL -> {
                            @Suppress("DEPRECATION")
                            val dialed = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                            AppLogger.log("DynamicReceiver: NEW_OUTGOING_CALL detected dialed number: $dialed")
                            if (!dialed.isNullOrBlank()) {
                                detectedOutgoingNumber = dialed
                            }
                        }
                    }
                }
            }

            dynamicReceiver = receiver
            val filter = IntentFilter().apply {
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                @Suppress("DEPRECATION")
                addAction(Intent.ACTION_NEW_OUTGOING_CALL)
            }

            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            AppLogger.log("Dynamic receiver registered for PHONE_STATE and NEW_OUTGOING_CALL")
        } catch (e: Exception) {
            AppLogger.log("Failed to register dynamic receiver: ${e.message}")
        }

        // TelephonyCallback (API 31+) or PhoneStateListener (API 29-30)
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        this@CallRecorderService.onCallStateChanged(state, currentIncomingNumber)
                    }
                }
                telephonyCallback = callback
                telephonyManager.registerTelephonyCallback(mainExecutor, callback)
                AppLogger.log("TelephonyCallback registered successfully on Android 12+")
            } catch (e: Exception) {
                AppLogger.log("Error registering TelephonyCallback: ${e.message}")
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        this@CallRecorderService.onCallStateChanged(state, phoneNumber)
                    }
                }
                phoneStateListener = listener
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                AppLogger.log("PhoneStateListener registered successfully on API < 31")
            } catch (e: Exception) {
                AppLogger.log("Error registering PhoneStateListener: ${e.message}")
            }
        }
    }

    private fun unregisterDynamicTelephonyListeners() {
        dynamicReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (ignored: Exception) {}
            dynamicReceiver = null
        }

        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let {
                try {
                    telephonyManager?.unregisterTelephonyCallback(it)
                } catch (ignored: Exception) {}
                telephonyCallback = null
            }
        } else {
            phoneStateListener?.let {
                try {
                    @Suppress("DEPRECATION")
                    telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
                } catch (ignored: Exception) {}
                phoneStateListener = null
            }
        }
    }

    // ------------------------------------------------------------------------
    // SILENT NOTIFICATION & FOREGROUND SERVICE
    // ------------------------------------------------------------------------

    private fun createSilentNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Monitor Background Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent background notification to keep call monitoring active"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildSilentNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Monitor")
            .setContentText("Active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .build()
    }

    private fun startMinimalForeground() {
        try {
            val notification = buildSilentNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            AppLogger.log("Error starting foreground service: ${e.message}")
        }
    }
}
