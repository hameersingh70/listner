package com.recall.app.util

import android.content.Context
import android.provider.CallLog
import android.util.Log
import kotlinx.coroutines.delay

object CallLogHelper {

    private const val TAG = "CallLogHelper"

    /**
     * Queries the CallLog for the most recent outgoing call number.
     * Retries up to maxRetries with delayMs between attempts to allow the
     * dialer/telecom subsystem time to write the entry to the SQLite database.
     */
    suspend fun getLatestOutgoingNumber(
        context: Context,
        maxRetries: Int = 8,
        delayMs: Long = 400L
    ): String? {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
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
                        val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)

                        if (numberIndex >= 0) {
                            val number = cursor.getString(numberIndex)
                            val callStartDate = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                            val durationSec = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L

                            val now = System.currentTimeMillis()
                            val callEndTime = callStartDate + (durationSec * 1000L)

                            // Accept if the call was active or ended within the last 3 minutes
                            val isRecent = Math.abs(now - callEndTime) < 180_000L || Math.abs(now - callStartDate) < 180_000L
                            Log.d(TAG, "Attempt $attempt: Found outgoing number: $number, date=$callStartDate, duration=${durationSec}s, isRecent=$isRecent")

                            if (!number.isNullOrBlank() && isRecent) {
                                return number
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException reading CallLog: ${e.message}")
                return null
            } catch (e: Exception) {
                Log.w(TAG, "Error querying CallLog on attempt $attempt: ${e.message}")
            }

            if (attempt < maxRetries - 1) {
                delay(delayMs)
            }
        }
        return null
    }

    /**
     * Queries the CallLog for the most recent incoming or missed call number.
     */
    suspend fun getLatestIncomingNumber(
        context: Context,
        maxRetries: Int = 4,
        delayMs: Long = 400L
    ): String? {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        val selection = "${CallLog.Calls.TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            CallLog.Calls.INCOMING_TYPE.toString(),
            CallLog.Calls.MISSED_TYPE.toString()
        )
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
                        val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)

                        if (numberIndex >= 0) {
                            val number = cursor.getString(numberIndex)
                            val callStartDate = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                            val durationSec = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L

                            val now = System.currentTimeMillis()
                            val callEndTime = callStartDate + (durationSec * 1000L)
                            val isRecent = Math.abs(now - callEndTime) < 180_000L || Math.abs(now - callStartDate) < 180_000L

                            if (!number.isNullOrBlank() && isRecent) {
                                return number
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                return null
            } catch (e: Exception) {
                // Ignore
            }

            if (attempt < maxRetries - 1) {
                delay(delayMs)
            }
        }
        return null
    }
}
