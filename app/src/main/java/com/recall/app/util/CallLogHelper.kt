package com.recall.app.util

import android.content.Context
import android.provider.CallLog
import kotlinx.coroutines.delay

object CallLogHelper {

    /**
     * Queries the CallLog for the most recent outgoing call number.
     * Retries up to maxRetries with delayMs between attempts to account for
     * slight dialer database write latency.
     */
    suspend fun getLatestOutgoingNumber(
        context: Context,
        maxRetries: Int = 3,
        delayMs: Long = 300L
    ): String? {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE
        )
        val selection = "${CallLog.Calls.TYPE} = ?"
        val selectionArgs = arrayOf(CallLog.Calls.OUTGOING_TYPE.toString())
        val sortOrder = "${CallLog.Calls.DATE} DESC LIMIT 1"

        for (attempt in 0 until maxRetries) {
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                        val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                        if (numberIndex >= 0) {
                            val number = cursor.getString(numberIndex)
                            val callDate = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                            // Only accept if call was initiated within the last 30 seconds
                            val isRecent = (System.currentTimeMillis() - callDate) < 30_000L
                            if (!number.isNullOrBlank() && isRecent) {
                                return number
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                return null
            } catch (e: Exception) {
                // Ignore and retry
            }

            if (attempt < maxRetries - 1) {
                delay(delayMs)
            }
        }
        return null
    }
}
