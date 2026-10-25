package com.recall.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.recall.app.service.CallRecorderService

class CallStateReceiver : BroadcastReceiver() {

    private val TAG = "CallStateReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        Log.i(TAG, "PHONE_STATE broadcast received: state=$stateStr, incomingNumber=$incomingNumber")

        // Ensure service is alive
        CallRecorderService.startMonitor(context)

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Service callback handles ringing state
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // If incomingNumber is present and whitelisted, start directly
                if (!incomingNumber.isNullOrBlank()) {
                    CallRecorderService.startRecording(context, incomingNumber)
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                CallRecorderService.stopRecording(context)
            }
        }
    }
}
