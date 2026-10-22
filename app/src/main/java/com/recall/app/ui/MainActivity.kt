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
import com.recall.app.R
import com.recall.app.data.PhoneNumberStore
import com.recall.app.service.CallRecorderService

class MainActivity : AppCompatActivity() {

    private lateinit var phoneStore: PhoneNumberStore
    private lateinit var listAdapter: ArrayAdapter<String>
    private val whitelistItems = mutableListOf<String>()

    private lateinit var tvStatus: TextView
    private lateinit var btnPermissions: Button
    private lateinit var btnBatteryOpt: Button
    private lateinit var etPhoneNumber: EditText
    private lateinit var btnAddNumber: Button
    private lateinit var lvWhitelist: ListView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        checkPermissionsAndStartService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        phoneStore = PhoneNumberStore(this)

        tvStatus = findViewById(R.id.tvServiceStatus)
        btnPermissions = findViewById(R.id.btnPermissions)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)
        etPhoneNumber = findViewById(R.id.etPhoneNumber)
        btnAddNumber = findViewById(R.id.btnAddNumber)
        lvWhitelist = findViewById(R.id.lvWhitelist)

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

        // Long press to remove a number
        lvWhitelist.setOnItemLongClickListener { _, _, position, _ ->
            val number = whitelistItems[position]
            phoneStore.removeNumber(number)
            refreshWhitelistUI()
            true
        }

        btnPermissions.setOnClickListener {
            requestRequiredPermissions()
        }

        btnBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        checkPermissionsAndStartService()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStartService()
    }

    private fun refreshWhitelistUI() {
        whitelistItems.clear()
        whitelistItems.addAll(phoneStore.getWhitelist().sorted())
        listAdapter.notifyDataSetChanged()
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

            // Start persistent monitor service
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
            // Fallback to battery optimization settings
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ignored: Exception) {}
        }
    }
}
