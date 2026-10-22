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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.recall.app.R
import com.recall.app.data.PhoneNumberStore
import com.recall.app.receiver.CallStateReceiver
import com.recall.app.util.CallLogHelper
import kotlinx.coroutines.*
import java.io.File

class CallRecorderService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentRecordingFile: File? = null

    private lateinit var phoneStore: PhoneNumberStore
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var dynamicReceiver: CallStateReceiver? = null
    private var telephonyCallback: Any? = null // TelephonyCallback on API 31+
    private var phoneStateListener: PhoneStateListener? = null // on API 29-30

    // Tracking call state
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var currentIncomingNumber: String? = null

    companion object {
        const val ACTION_START_RECORDING = "com.recall.app.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.recall.app.action.STOP_RECORDING"
        const val ACTION_START_MONITOR = "com.recall.app.action.START_MONITOR"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"

        private const val CHANNEL_ID = "call_monitor_silent_channel"
        private const val NOTIFICATION_ID = 2001

        fun startMonitor(context: Context) {
            val intent = Intent(context, CallRecorderService::class.java).apply {
                action = ACTION_START_MONITOR
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startRecording(context: Context, phoneNumber: String) {
            val intent = Intent(context, CallRecorderService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, CallRecorderService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        phoneStore = PhoneNumberStore(this)
        createSilentNotificationChannel()
        startMinimalForeground()

        registerDynamicTelephonyListeners()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMinimalForeground()

        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val number = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                handleStartRecording(number)
            }
            ACTION_STOP_RECORDING -> {
                handleStopRecording()
            }
            ACTION_START_MONITOR -> {
                // Service is running in foreground, maintaining listeners
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handleStopRecording()
        unregisterDynamicTelephonyListeners()
        serviceJob.cancel()
    }

    // ------------------------------------------------------------------------
    // RECORDING LOGIC
    // ------------------------------------------------------------------------
    @Synchronized
    private fun handleStartRecording(rawNumber: String) {
        val normalized = PhoneNumberStore.normalize(rawNumber)
        if (!phoneStore.isWhitelisted(normalized)) {
            return
        }

        if (isRecording) {
            return
        }

        try {
            val recordingsDir = getExternalFilesDir("Recordings")
                ?: File(filesDir, "Recordings").apply { mkdirs() }
            if (!recordingsDir.exists()) recordingsDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val fileName = "${normalized}_${timestamp}.m4a"
            val outputFile = File(recordingsDir, fileName)
            currentRecordingFile = outputFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(64000)
                setAudioChannels(1)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
        } catch (e: Exception) {
            currentRecordingFile?.delete()
            currentRecordingFile = null
            mediaRecorder = null
            isRecording = false
        }
    }

    @Synchronized
    private fun handleStopRecording() {
        if (!isRecording && mediaRecorder == null) return

        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            currentRecordingFile?.delete()
        } finally {
            mediaRecorder = null
            isRecording = false
            currentRecordingFile = null
        }
    }

    // ------------------------------------------------------------------------
    // TELEPHONY CALL STATE HANDLING
    // ------------------------------------------------------------------------
    fun onCallStateChanged(state: Int, incomingNumber: String?) {
        if (!incomingNumber.isNullOrBlank()) {
            currentIncomingNumber = incomingNumber
        }

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Incoming call ringing
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    // Incoming call answered
                    val number = currentIncomingNumber ?: ""
                    handleStartRecording(number)
                } else if (lastState == TelephonyManager.CALL_STATE_IDLE) {
                    // Outgoing call initiated
                    serviceScope.launch {
                        val outgoingNumber = CallLogHelper.getLatestOutgoingNumber(this@CallRecorderService)
                        if (!outgoingNumber.isNullOrBlank()) {
                            withContext(Dispatchers.Main) {
                                handleStartRecording(outgoingNumber)
                            }
                        }
                    }
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended or missed
                handleStopRecording()
                currentIncomingNumber = null
            }
        }

        lastState = state
    }

    // ------------------------------------------------------------------------
    // DYNAMIC RECEIVERS & TELEPHONY CALLBACKS (Android 12+ requirement)
    // ------------------------------------------------------------------------
    private fun registerDynamicTelephonyListeners() {
        // 1. Dynamic BroadcastReceiver
        try {
            dynamicReceiver = CallStateReceiver()
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            registerReceiver(dynamicReceiver, filter)
        } catch (e: Exception) {
            // Ignore
        }

        // 2. TelephonyCallback (API 31+) or PhoneStateListener (API 29-30)
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    this@CallRecorderService.onCallStateChanged(state, currentIncomingNumber)
                }
            }
            telephonyCallback = callback
            try {
                telephonyManager.registerTelephonyCallback(mainExecutor, callback)
            } catch (e: SecurityException) {
                // Permissions handled at runtime
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    this@CallRecorderService.onCallStateChanged(state, phoneNumber)
                }
            }
            phoneStateListener = listener
            try {
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            } catch (e: SecurityException) {
                // Permissions handled at runtime
            }
        }
    }

    private fun unregisterDynamicTelephonyListeners() {
        dynamicReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore
            }
            dynamicReceiver = null
        }

        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let {
                try {
                    telephonyManager?.unregisterTelephonyCallback(it)
                } catch (e: Exception) {
                    // Ignore
                }
                telephonyCallback = null
            }
        } else {
            phoneStateListener?.let {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
                phoneStateListener = null
            }
        }
    }

    // ------------------------------------------------------------------------
    // SILENT MINIMAL NOTIFICATION (Anti-Popup, IMPORTANCE_MIN)
    // ------------------------------------------------------------------------
    private fun startMinimalForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Call Monitor Active")
            .setContentText("Monitoring configured calls")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .build()

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
    }

    private fun createSilentNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Monitor Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent background call monitor"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
