package com.recall.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.recall.app.data.PhoneNumberStore
import com.recall.app.service.CallRecorderService
import com.recall.app.util.CallLogHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var currentIncomingNumber: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (!incomingNumber.isNullOrBlank()) {
            currentIncomingNumber = incomingNumber
        }

        val phoneStore = PhoneNumberStore(context)

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                lastState = TelephonyManager.CALL_STATE_RINGING
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    // Incoming call answered
                    val number = currentIncomingNumber ?: ""
                    if (phoneStore.isWhitelisted(number)) {
                        CallRecorderService.startRecording(context, number)
                    }
                } else if (lastState == TelephonyManager.CALL_STATE_IDLE) {
                    // Outgoing call started
                    CoroutineScope(Dispatchers.IO).launch {
                        val outgoingNumber = CallLogHelper.getLatestOutgoingNumber(context)
                        if (!outgoingNumber.isNullOrBlank() && phoneStore.isWhitelisted(outgoingNumber)) {
                            CallRecorderService.startRecording(context, outgoingNumber)
                        }
                    }
                }
                lastState = TelephonyManager.CALL_STATE_OFFHOOK
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                // Call ended or missed
                CallRecorderService.stopRecording(context)
                currentIncomingNumber = null
                lastState = TelephonyManager.CALL_STATE_IDLE
            }
        }
    }
}
