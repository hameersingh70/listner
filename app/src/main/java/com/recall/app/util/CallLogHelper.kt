package com.recall.app.util

import android.content.Context
import android.provider.CallLog
import kotlinx.coroutines.delay

object CallLogHelper {

    /**
     * Queries the CallLog for the most recent outgoing call number.
     * Retries for up to 25 seconds to account for slow asynchronous
     * database writes by OEM dialers/telecom services.
     */
    suspend fun getLatestOutgoingNumber(
        context: Context,
        callStartTime: Long = System.currentTimeMillis() - 60_000L,
        maxRetries: Int = 25,
        delayMs: Long = 800L
    ): String? {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        val selection = "${CallLog.Calls.TYPE} = ?"
        val selectionArgs = arrayOf(CallLog.Calls.OUTGOING_TYPE.toString())
        val sortOrder = "${CallLog.Calls.DATE} DESC LIMIT 5"

        AppLogger.log("CallLogHelper: Querying outgoing calls (minStartTime = $callStartTime)")

        for (attempt in 1..maxRetries) {
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                        val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                        val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)

                        if (numberIndex >= 0) {
                            val number = cursor.getString(numberIndex)
                            val callStartDate = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                            val durationSec = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L

                            val now = System.currentTimeMillis()
                            val callEndTime = callStartDate + (durationSec * 1000L)

                            // Match if the call started or ended close to our call window
                            val isCallFromThisSession = callStartDate >= (callStartTime - 15_000L) ||
                                    (now - callEndTime) < 120_000L

                            if (!number.isNullOrBlank() && isCallFromThisSession) {
                                AppLogger.log("CallLogHelper: Found matching outgoing call on attempt $attempt: $number (date=$callStartDate, duration=${durationSec}s)")
                                return number
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                AppLogger.log("CallLogHelper: SecurityException reading CallLog: ${e.message}")
                return null
            } catch (e: Exception) {
                AppLogger.log("CallLogHelper: Error querying CallLog on attempt $attempt: ${e.message}")
            }

            if (attempt < maxRetries) {
                delay(delayMs)
            }
        }

        AppLogger.log("CallLogHelper: Exhausted all $maxRetries attempts without finding outgoing call.")
        return null
    }

    /**
     * Queries the CallLog for the most recent incoming or missed call number.
     */
    suspend fun getLatestIncomingNumber(
        context: Context,
        callStartTime: Long = System.currentTimeMillis() - 60_000L,
        maxRetries: Int = 10,
        delayMs: Long = 800L
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
        val sortOrder = "${CallLog.Calls.DATE} DESC LIMIT 5"

        for (attempt in 1..maxRetries) {
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                        val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                        val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)

                        if (numberIndex >= 0) {
                            val number = cursor.getString(numberIndex)
                            val callStartDate = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                            val durationSec = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L

                            val now = System.currentTimeMillis()
                            val callEndTime = callStartDate + (durationSec * 1000L)
                            val isRecent = callStartDate >= (callStartTime - 15_000L) || (now - callEndTime) < 120_000L

                            if (!number.isNullOrBlank() && isRecent) {
                                AppLogger.log("CallLogHelper: Found incoming call on attempt $attempt: $number")
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

            if (attempt < maxRetries) {
                delay(delayMs)
            }
        }
        return null
    }
}
