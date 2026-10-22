package com.recall.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.recall.app.R
import com.recall.app.ai.*
import com.recall.app.data.AiSettingsStore
import com.recall.app.data.PhoneNumberStore
import com.recall.app.service.CallRecorderService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var phoneStore: PhoneNumberStore
    private lateinit var aiSettings: AiSettingsStore
    private lateinit var downloadManager: ModelDownloadManager
    private lateinit var transcriptionEngine: LocalTranscriptionEngine

    private lateinit var listAdapter: ArrayAdapter<String>
    private val whitelistItems = mutableListOf<String>()

    private lateinit var summaryAdapter: ArrayAdapter<String>
    private val summaryItems = mutableListOf<String>()

    private lateinit var tvStatus: TextView
    private lateinit var btnPermissions: Button
    private lateinit var btnBatteryOpt: Button
    private lateinit var etPhoneNumber: EditText
    private lateinit var btnAddNumber: Button
    private lateinit var lvWhitelist: ListView
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        checkPermissionsAndStartService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        phoneStore = PhoneNumberStore(this)
        aiSettings = AiSettingsStore(this)
        downloadManager = ModelDownloadManager(this)
        transcriptionEngine = LocalTranscriptionEngine(this)

        initViews()
        setupWhitelistSection()
        setupAiSection()
        setupSummarySection()

        checkPermissionsAndStartService()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvServiceStatus)
        btnPermissions = findViewById(R.id.btnPermissions)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)
        etPhoneNumber = findViewById(R.id.etPhoneNumber)
        btnAddNumber = findViewById(R.id.btnAddNumber)
        lvWhitelist = findViewById(R.id.lvWhitelist)
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
    }

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

    private fun setupAiSection() {
        // Default is disabled as required
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
            // Collect live download progress flow
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
                            // Automatically start transcribing existing audio
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

            tvProcessingStatus.text = "Completed! Transcribed $count new recording(s)."
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

        androidx.appcompat.app.AlertDialog.Builder(this)
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
            tvStatus.text = "Status: ACTIVE (Running in Background)"
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
}
