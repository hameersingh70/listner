package com.recall.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.recall.app.R
import com.recall.app.ai.*
import com.recall.app.data.AiSettingsStore
import com.recall.app.data.PhoneNumberStore
import com.recall.app.service.CallRecorderService
import com.recall.app.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private lateinit var phoneStore: PhoneNumberStore
    private lateinit var aiSettings: AiSettingsStore
    private lateinit var downloadManager: ModelDownloadManager
    private lateinit var transcriptionEngine: LocalTranscriptionEngine

    private lateinit var listAdapter: ArrayAdapter<String>
    private val whitelistItems = mutableListOf<String>()

    private lateinit var recordingsAdapter: ArrayAdapter<String>
    private val recordingsItems = mutableListOf<String>()
    private val recordingFiles = mutableListOf<File>()

    private lateinit var summaryAdapter: ArrayAdapter<String>
    private val summaryItems = mutableListOf<String>()

    private lateinit var tvStatus: TextView
    private lateinit var btnPermissions: Button
    private lateinit var btnBatteryOpt: Button
    private lateinit var btnAutostart: Button
    private lateinit var etPhoneNumber: EditText
    private lateinit var btnAddNumber: Button
    private lateinit var lvWhitelist: ListView
    private lateinit var lvRecordings: ListView
    private lateinit var btnRefreshRecordings: Button
    private lateinit var tvRecordingsPath: TextView
    private lateinit var lvDailySummaries: ListView

    // AI UI Components
    private lateinit var switchAiEnabled: MaterialSwitch
    private lateinit var layoutDownloadProgress: LinearLayout
    private lateinit var tvDownloadCounter: TextView
    private lateinit var pbDownload: ProgressBar
    private lateinit var layoutAiControls: LinearLayout
    private lateinit var tvAiModelStatus: TextView
    private lateinit var btnProcessRecordings: Button
    private lateinit var tvProcessingStatus: TextView

    private var downloadJob: Job? = null
    private var activeMediaPlayer: MediaPlayer? = null

    private val recordingsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Recordings update broadcast received! Refreshing UI.")
            refreshRecordingsUI()
            refreshSummariesUI()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        checkPermissionsAndStartService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppLogger.init(this)
        AppLogger.log("MainActivity onCreate")

        // Ensure storage directories exist immediately
        getExternalFilesDir("Recordings")?.mkdirs()
        getExternalFilesDir("Summaries")?.mkdirs()

        phoneStore = PhoneNumberStore(this)
        aiSettings = AiSettingsStore(this)
        downloadManager = ModelDownloadManager(this)
        transcriptionEngine = LocalTranscriptionEngine(this)

        initViews()
        setupRecordingsSection()
        setupWhitelistSection()
        setupAiSection()
        setupSummarySection()

        checkPermissionsAndStartService()

        // Register local update receiver
        val filter = IntentFilter(CallRecorderService.ACTION_RECORDINGS_UPDATED)
        ContextCompat.registerReceiver(this, recordingsUpdateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(recordingsUpdateReceiver)
        } catch (ignored: Exception) {}
        activeMediaPlayer?.release()
        activeMediaPlayer = null
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvServiceStatus)
        btnPermissions = findViewById(R.id.btnPermissions)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)
        btnAutostart = findViewById(R.id.btnAutostart)
        etPhoneNumber = findViewById(R.id.etPhoneNumber)
        btnAddNumber = findViewById(R.id.btnAddNumber)
        lvWhitelist = findViewById(R.id.lvWhitelist)
        lvRecordings = findViewById(R.id.lvRecordings)
        btnRefreshRecordings = findViewById(R.id.btnRefreshRecordings)
        tvRecordingsPath = findViewById(R.id.tvRecordingsPath)
        lvDailySummaries = findViewById(R.id.lvDailySummaries)

        switchAiEnabled = findViewById(R.id.switchAiEnabled)
        layoutDownloadProgress = findViewById(R.id.layoutDownloadProgress)
        tvDownloadCounter = findViewById(R.id.tvDownloadCounter)
        pbDownload = findViewById(R.id.pbDownload)
        layoutAiControls = findViewById(R.id.layoutAiControls)
        tvAiModelStatus = findViewById(R.id.tvAiModelStatus)
        btnProcessRecordings = findViewById(R.id.btnProcessRecordings)
        tvProcessingStatus = findViewById(R.id.tvProcessingStatus)

        btnPermissions.setOnClickListener { requestRequiredPermissions() }
        btnBatteryOpt.setOnClickListener { requestIgnoreBatteryOptimization() }
        btnAutostart.setOnClickListener { openAutostartSettings() }
        btnRefreshRecordings.setOnClickListener { refreshRecordingsUI() }
    }

    // ------------------------------------------------------------------------
    // RECORDED CALLS UI & PLAYBACK
    // ------------------------------------------------------------------------

    private fun setupRecordingsSection() {
        recordingsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, recordingsItems)
        lvRecordings.adapter = recordingsAdapter

        refreshRecordingsUI()

        // Tap to play audio
        lvRecordings.setOnItemClickListener { _, _, position, _ ->
            if (position in recordingFiles.indices) {
                val file = recordingFiles[position]
                showPlaybackDialog(file)
            }
        }

        // Long-tap to share file via WhatsApp / Drive / etc.
        lvRecordings.setOnItemLongClickListener { _, _, position, _ ->
            if (position in recordingFiles.indices) {
                val file = recordingFiles[position]
                shareRecordingFile(file)
                true
            } else {
                false
            }
        }
    }

    private fun refreshRecordingsUI() {
        recordingsItems.clear()
        recordingFiles.clear()

        val recordingsDir = getExternalFilesDir("Recordings")
        val files = recordingsDir?.listFiles()
            ?.filter { it.extension.lowercase(Locale.ROOT) == "m4a" && !it.name.startsWith("pending_") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        recordingFiles.addAll(files)

        if (files.isEmpty()) {
            recordingsItems.add("No recordings captured yet.\n(Call a whitelisted number to test)")
            tvRecordingsPath.text = "Path: /sdcard/Android/data/${packageName}/files/Recordings/"
        } else {
            val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            files.forEach { file ->
                val parts = file.nameWithoutExtension.split("_")
                val number = if (parts.isNotEmpty()) parts[0] else "Unknown"
                val dateStr = dateFormat.format(Date(file.lastModified()))
                val sizeKb = file.length() / 1024
                recordingsItems.add("📞 $number  •  $dateStr  (${sizeKb} KB)")
            }
            tvRecordingsPath.text = "${files.size} recording(s) in /Recordings/ • Tap to play"
        }

        recordingsAdapter.notifyDataSetChanged()
    }

    private fun showPlaybackDialog(file: File) {
        activeMediaPlayer?.release()
        activeMediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Playing: ${file.nameWithoutExtension}")
            .setMessage("Audio playback in progress...")
            .setPositiveButton("Stop") { d, _ ->
                activeMediaPlayer?.stop()
                activeMediaPlayer?.release()
                activeMediaPlayer = null
                d.dismiss()
            }
            .setNeutralButton("Share File") { _, _ ->
                shareRecordingFile(file)
            }
            .setOnDismissListener {
                activeMediaPlayer?.release()
                activeMediaPlayer = null
            }
            .create()

        activeMediaPlayer?.setOnCompletionListener {
            dialog.setMessage("Playback completed.")
        }

        dialog.show()
    }

    private fun shareRecordingFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Call Recording"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------------
    // WHITELIST SECTION
    // ------------------------------------------------------------------------

    private fun setupWhitelistSection() {
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, whitelistItems)
        lvWhitelist.adapter = listAdapter

        refreshWhitelistUI()

        btnAddNumber.setOnClickListener {
            val input = etPhoneNumber.text.toString().trim()
            if (input.isNotEmpty()) {
                val added = phoneStore.addNumber(input)
                if (added) {
                    etPhoneNumber.setText("")
                    refreshWhitelistUI()
                }
            }
        }

        lvWhitelist.setOnItemLongClickListener { _, _, position, _ ->
            val number = whitelistItems[position]
            phoneStore.removeNumber(number)
            refreshWhitelistUI()
            true
        }
    }

    // ------------------------------------------------------------------------
    // ON-DEVICE AI SECTION
    // ------------------------------------------------------------------------

    private fun setupAiSection() {
        switchAiEnabled.isChecked = aiSettings.isAiEnabled
        updateAiUIState()

        switchAiEnabled.setOnCheckedChangeListener { _, isChecked ->
            aiSettings.isAiEnabled = isChecked
            updateAiUIState()

            if (isChecked) {
                if (!downloadManager.isModelReady()) {
                    startModelDownload()
                } else {
                    processExistingRecordings()
                }
            } else {
                downloadJob?.cancel()
                layoutDownloadProgress.visibility = View.GONE
            }
        }

        btnProcessRecordings.setOnClickListener {
            processExistingRecordings()
        }
    }

    private fun updateAiUIState() {
        if (!aiSettings.isAiEnabled) {
            layoutDownloadProgress.visibility = View.GONE
            layoutAiControls.visibility = View.GONE
            return
        }

        if (downloadManager.isModelReady()) {
            layoutDownloadProgress.visibility = View.GONE
            layoutAiControls.visibility = View.VISIBLE
            tvAiModelStatus.text = "Status: AI Model Ready (~75 MB)"
            tvAiModelStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            layoutDownloadProgress.visibility = View.VISIBLE
            layoutAiControls.visibility = View.GONE
        }
    }

    private fun startModelDownload() {
        layoutDownloadProgress.visibility = View.VISIBLE
        layoutAiControls.visibility = View.GONE

        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch {
            val progressJob = launch {
                downloadManager.downloadState.collect { state ->
                    when (state) {
                        is DownloadState.Progress -> {
                            tvDownloadCounter.text = String.format(
                                "Downloading Whisper Model: %.1f MB / %.1f MB (%d%%)",
                                state.downloadedMb,
                                state.totalMb,
                                state.percent
                            )
                            pbDownload.progress = state.percent
                        }
                        is DownloadState.Success -> {
                            tvDownloadCounter.text = "Download Complete: 100%"
                            pbDownload.progress = 100
                            aiSettings.isModelDownloaded = true
                            updateAiUIState()
                            processExistingRecordings()
                        }
                        is DownloadState.Error -> {
                            tvDownloadCounter.text = state.message
                        }
                        is DownloadState.Idle -> {}
                    }
                }
            }

            downloadManager.startDownload()
            progressJob.cancel()
        }
    }

    private fun processExistingRecordings() {
        lifecycleScope.launch {
            tvProcessingStatus.text = "Scanning recordings..."
            btnProcessRecordings.isEnabled = false

            val count = transcriptionEngine.processAllRecordings { current, total, fileName ->
                tvProcessingStatus.text = "Processing ($current/$total): $fileName"
            }

            tvProcessingStatus.text = "Completed! Processed $count recording(s)."
            btnProcessRecordings.isEnabled = true
            refreshSummariesUI()
        }
    }

    private fun setupSummarySection() {
        summaryAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, summaryItems)
        lvDailySummaries.adapter = summaryAdapter
        refreshSummariesUI()

        lvDailySummaries.setOnItemClickListener { _, _, position, _ ->
            val allSummaries = DailySummaryGenerator.loadAllSummaries(this)
            if (position in allSummaries.indices) {
                val summary = allSummaries[position]
                showSummaryDialog(summary)
            }
        }
    }

    private fun showSummaryDialog(summary: DailySummary) {
        val message = buildString {
            appendLine("Phone: ${summary.phoneNumber}")
            appendLine("Date: ${summary.date}")
            appendLine("Total Calls: ${summary.callCount}")
            appendLine()
            appendLine("OVERVIEW:")
            appendLine(summary.overview)
            appendLine()
            appendLine("DECISIONS:")
            if (summary.decisions.isEmpty()) appendLine("  - None")
            summary.decisions.forEach { appendLine("  ✓ $it") }
            appendLine()
            appendLine("ACTION ITEMS:")
            if (summary.actionItems.isEmpty()) appendLine("  - None")
            summary.actionItems.forEach { appendLine("  ☐ $it") }
        }

        AlertDialog.Builder(this)
            .setTitle("Daily Summary: ${summary.phoneNumber}")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun refreshWhitelistUI() {
        whitelistItems.clear()
        whitelistItems.addAll(phoneStore.getWhitelist().sorted())
        listAdapter.notifyDataSetChanged()
    }

    private fun refreshSummariesUI() {
        summaryItems.clear()
        val allSummaries = DailySummaryGenerator.loadAllSummaries(this)
        if (allSummaries.isEmpty()) {
            summaryItems.add("No daily summaries generated yet.")
        } else {
            allSummaries.forEach { s ->
                summaryItems.add("${s.phoneNumber} (${s.date}) — ${s.callCount} call(s) | ${s.decisions.size} decisions, ${s.actionItems.size} actions")
            }
        }
        summaryAdapter.notifyDataSetChanged()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStartService()
        refreshRecordingsUI()
        refreshWhitelistUI()
        refreshSummariesUI()
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        val needed = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun checkPermissionsAndStartService() {
        if (hasAllPermissions()) {
            tvStatus.text = "Status: ACTIVE (Monitoring Phone Calls)"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            btnPermissions.visibility = View.GONE

            CallRecorderService.startMonitor(this)
        } else {
            tvStatus.text = "Status: Permissions Required"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            btnPermissions.visibility = View.VISIBLE
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(packageName)) {
            btnBatteryOpt.visibility = View.GONE
        } else {
            btnBatteryOpt.visibility = View.VISIBLE
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ignored: Exception) {}
        }
    }

    private fun openAutostartSettings() {
        // Deep-link to MIUI / HyperOS Autostart management
        val miuiIntents = listOf(
            Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") }
        )

        for (intent in miuiIntents) {
            try {
                startActivity(intent)
                return
            } catch (ignored: Exception) {}
        }
    }
}
