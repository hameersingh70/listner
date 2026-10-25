package com.recall.app.service

import android.app.*
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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.recall.app.R
import com.recall.app.data.PhoneNumberStore
import com.recall.app.receiver.CallStateReceiver
import com.recall.app.util.CallLogHelper
import kotlinx.coroutines.*
import java.io.File

class CallRecorderService : Service() {

    private val TAG = "CallRecorderService"

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentRecordingFile: File? = null
    private var isPendingOutgoing = false
    private var isPendingIncoming = false
    private var currentTargetNumber: String? = null

    private lateinit var phoneStore: PhoneNumberStore
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var dynamicReceiver: CallStateReceiver? = null
    private var telephonyCallback: Any? = null // TelephonyCallback on API 31+
    private var phoneStateListener: PhoneStateListener? = null // on API 29-30

    private var lastCallState = TelephonyManager.CALL_STATE_IDLE
    private var currentIncomingNumber: String? = null

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
                Log.e("CallRecorderService", "Failed to start service: ${e.message}")
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
                Log.e("CallRecorderService", "Failed to start recording: ${e.message}")
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
                Log.e("CallRecorderService", "Failed to stop recording: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate started")
        phoneStore = PhoneNumberStore(this)

        // Ensure Recordings directory exists immediately on service start
        getRecordingsDir()

        createSilentNotificationChannel()
        startMinimalForeground()
        registerDynamicTelephonyListeners()
        Log.i(TAG, "Service onCreate completed successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMinimalForeground()

        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val number = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                Log.i(TAG, "Received ACTION_START_RECORDING for number: $number")
                handleStartWhitelistedRecording(number)
            }
            ACTION_STOP_RECORDING -> {
                Log.i(TAG, "Received ACTION_STOP_RECORDING")
                handleStopRecording()
            }
            ACTION_START_MONITOR -> {
                Log.i(TAG, "Service monitor active and listening to calls")
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
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
                Log.i(TAG, "MediaRecorder started successfully with AudioSource: $source")
                return recorder
            } catch (e: Exception) {
                Log.w(TAG, "AudioSource $source failed: ${e.message}, trying next...")
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
            Log.i(TAG, "Number $normalized is NOT in whitelist. Skipping recording.")
            return
        }

        if (isRecording) {
            Log.i(TAG, "Already recording. Skipping duplicate start.")
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
            Log.i(TAG, "Started recording call with whitelisted contact: $normalized -> ${outputFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting whitelisted recording: ${e.message}", e)
            currentRecordingFile?.delete()
            currentRecordingFile = null
            mediaRecorder = null
            isRecording = false
        }
    }

    @Synchronized
    private fun handleStartPendingRecording(isOutgoing: Boolean) {
        if (isRecording) {
            Log.i(TAG, "Already recording, ignoring pending start request")
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

            mediaRecorder = createConfiguredRecorder(outputFile)
            isRecording = true
            Log.i(TAG, "Started tentative recording ($prefix) -> ${outputFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting tentative recording: ${e.message}", e)
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

        Log.i(TAG, "Stopping recording session...")
        val fileToProcess = currentRecordingFile
        val wasPendingOutgoing = isPendingOutgoing
        val wasPendingIncoming = isPendingIncoming
        val wasTargetNumber = currentTargetNumber

        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            Log.i(TAG, "MediaRecorder stopped and released")
        } catch (e: Exception) {
            Log.w(TAG, "Exception during MediaRecorder stop/release: ${e.message}")
        } finally {
            mediaRecorder = null
            isRecording = false
            currentRecordingFile = null
            isPendingOutgoing = false
            isPendingIncoming = false
            currentTargetNumber = null
        }

        // Process recorded audio file validation
        if (fileToProcess != null && fileToProcess.exists()) {
            if (wasPendingOutgoing) {
                // Outgoing call just finished! Query CallLog to identify the dialed contact
                serviceScope.launch {
                    Log.i(TAG, "Resolving outgoing call number from CallLog...")
                    val outgoingNumber = CallLogHelper.getLatestOutgoingNumber(this@CallRecorderService)
                    if (!outgoingNumber.isNullOrBlank()) {
                        val normalized = PhoneNumberStore.normalize(outgoingNumber)
                        Log.i(TAG, "Identified outgoing call contact: $outgoingNumber (normalized: $normalized)")

                        if (phoneStore.isWhitelisted(normalized)) {
                            val timestamp = System.currentTimeMillis()
                            val finalFile = File(fileToProcess.parentFile, "${normalized}_${timestamp}.m4a")
                            if (fileToProcess.renameTo(finalFile)) {
                                Log.i(TAG, "SUCCESS: Outgoing call with whitelisted contact $normalized saved to ${finalFile.absolutePath} (${finalFile.length()} bytes)")
                                notifyRecordingsUpdated()
                                return@launch
                            }
                        } else {
                            Log.i(TAG, "Contact $normalized is NOT in whitelist. Discarding audio.")
                        }
                    } else {
                        Log.w(TAG, "Could not determine outgoing call number from CallLog. Discarding audio for privacy.")
                    }

                    // Delete file if not whitelisted
                    fileToProcess.delete()
                }
            } else if (wasPendingIncoming) {
                // Incoming call without initial number
                serviceScope.launch {
                    val incomingNumber = CallLogHelper.getLatestIncomingNumber(this@CallRecorderService)
                    if (!incomingNumber.isNullOrBlank()) {
                        val normalized = PhoneNumberStore.normalize(incomingNumber)
                        if (phoneStore.isWhitelisted(normalized)) {
                            val timestamp = System.currentTimeMillis()
                            val finalFile = File(fileToProcess.parentFile, "${normalized}_${timestamp}.m4a")
                            if (fileToProcess.renameTo(finalFile)) {
                                Log.i(TAG, "SUCCESS: Incoming call with whitelisted contact $normalized saved to ${finalFile.absolutePath}")
                                notifyRecordingsUpdated()
                                return@launch
                            }
                        }
                    }
                    fileToProcess.delete()
                }
            } else if (wasTargetNumber != null) {
                if (phoneStore.isWhitelisted(wasTargetNumber) && fileToProcess.length() > 1000) {
                    Log.i(TAG, "SUCCESS: Saved whitelisted recording: ${fileToProcess.name} (${fileToProcess.length()} bytes)")
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
            Log.w(TAG, "Error sending recordings update broadcast: ${e.message}")
        }
    }

    // ------------------------------------------------------------------------
    // TELEPHONY CALL STATE HANDLING
    // ------------------------------------------------------------------------

    fun onCallStateChanged(state: Int, incomingNumber: String?) {
        Log.i(TAG, "onCallStateChanged: state=$state (lastState=$lastCallState), incomingNumber=$incomingNumber")

        if (!incomingNumber.isNullOrBlank()) {
            currentIncomingNumber = incomingNumber
        }

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Phone is ringing with incoming call
                Log.i(TAG, "State: CALL_STATE_RINGING (Incoming call: $incomingNumber)")
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (lastCallState == TelephonyManager.CALL_STATE_RINGING) {
                    // Incoming call was answered
                    val rawNum = currentIncomingNumber ?: ""
                    val normalized = PhoneNumberStore.normalize(rawNum)
                    Log.i(TAG, "State: CALL_STATE_OFFHOOK (Incoming call answered: raw='$rawNum', normalized='$normalized')")

                    if (phoneStore.isWhitelisted(normalized)) {
                        handleStartWhitelistedRecording(normalized)
                    } else if (normalized.isBlank()) {
                        // Caller ID not available yet during ringing, record tentatively
                        handleStartPendingRecording(isOutgoing = false)
                    } else {
                        Log.i(TAG, "Incoming number $normalized not in whitelist. Ignoring.")
                    }
                } else if (lastCallState == TelephonyManager.CALL_STATE_IDLE) {
                    // Outgoing call initiated!
                    Log.i(TAG, "State: CALL_STATE_OFFHOOK (Outgoing call initiated)")
                    // Start tentative recording immediately during call, verify whitelist at hangup
                    handleStartPendingRecording(isOutgoing = true)
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                Log.i(TAG, "State: CALL_STATE_IDLE (Call ended or missed)")
                handleStopRecording()
                currentIncomingNumber = null
            }
        }

        lastCallState = state
    }

    // ------------------------------------------------------------------------
    // DYNAMIC RECEIVERS & TELEPHONY CALLBACKS (Android 12 & 13+ compliant)
    // ------------------------------------------------------------------------

    private fun registerDynamicTelephonyListeners() {
        // 1. Dynamic BroadcastReceiver with RECEIVER_EXPORTED (Required on Android 13+)
        try {
            dynamicReceiver = CallStateReceiver()
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            ContextCompat.registerReceiver(
                this,
                dynamicReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            Log.i(TAG, "Dynamic CallStateReceiver registered successfully with RECEIVER_EXPORTED")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register dynamic CallStateReceiver: ${e.message}", e)
        }

        // 2. TelephonyCallback (API 31+) or PhoneStateListener (API 29-30)
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
                Log.i(TAG, "TelephonyCallback registered successfully on Android 12+")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException registering TelephonyCallback: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Exception registering TelephonyCallback: ${e.message}")
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
                Log.i(TAG, "PhoneStateListener registered successfully on API < 31")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException registering PhoneStateListener: ${e.message}")
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
            Log.e(TAG, "Error starting foreground service: ${e.message}")
        }
    }
}
