package com.recall.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DailySummaryGenerator {

    /**
     * Extracts structured action items and decisions from conversation transcripts
     * covering Hindi, English, and mixed Hinglish.
     */
    fun summarizeDay(
        phoneNumber: String,
        date: String,
        transcripts: List<String>,
        recordingFiles: List<String>,
        totalDurationSeconds: Long
    ): DailySummary {
        val combinedText = transcripts.joinToString("\n\n").trim()

        val decisions = mutableListOf<String>()
        val actionItems = mutableListOf<String>()

        val decisionKeywords = listOf(
            "agree", "agreed", "decided", "final", "finalized", "done", "pakka",
            "theek hai", "deal", "confirm", "confirmed", "settled"
        )

        val actionKeywords = listOf(
            "will send", "bhej dunga", "bhej dena", "kar dena", "karna hai",
            "share", "call back", "payment", "transfer", "meeting", "submit",
            "remind", "follow up", "email", "quotation", "invoice"
        )

        // Sentence-by-sentence analysis
        val sentences = combinedText.split(Regex("[.!?\\n]+")).map { it.trim() }.filter { it.length > 5 }

        for (sentence in sentences) {
            val lower = sentence.lowercase(Locale.ROOT)
            val isDecision = decisionKeywords.any { lower.contains(it) }
            val isAction = actionKeywords.any { lower.contains(it) }

            if (isDecision && !decisions.contains(sentence)) {
                decisions.add(sentence)
            }
            if (isAction && !actionItems.contains(sentence)) {
                actionItems.add(sentence)
            }
        }

        val overview = if (combinedText.isBlank()) {
            "No audible speech detected across calls on $date."
        } else {
            "Recorded ${recordingFiles.size} conversation(s) with $phoneNumber on $date. Extracted ${decisions.size} decision(s) and ${actionItems.size} action item(s)."
        }

        return DailySummary(
            phoneNumber = phoneNumber,
            date = date,
            callCount = recordingFiles.size,
            totalDurationSeconds = totalDurationSeconds,
            recordingFiles = recordingFiles,
            overview = overview,
            decisions = decisions,
            actionItems = actionItems,
            fullTranscript = combinedText
        )
    }

    /**
     * Persists the DailySummary as both structured JSON and readable text.
     */
    fun saveSummary(context: Context, summary: DailySummary): File {
        val summariesDir = context.getExternalFilesDir("Summaries")
            ?: File(context.filesDir, "Summaries").apply { mkdirs() }
        if (!summariesDir.exists()) summariesDir.mkdirs()

        val jsonFile = File(summariesDir, "${summary.phoneNumber}_${summary.date}_summary.json")
        val txtFile = File(summariesDir, "${summary.phoneNumber}_${summary.date}_summary.txt")

        // 1. JSON output
        val jsonObject = JSONObject().apply {
            put("phoneNumber", summary.phoneNumber)
            put("date", summary.date)
            put("callCount", summary.callCount)
            put("totalDurationSeconds", summary.totalDurationSeconds)
            put("recordings", JSONArray(summary.recordingFiles))
            put("overview", summary.overview)
            put("decisions", JSONArray(summary.decisions))
            put("actionItems", JSONArray(summary.actionItems))
            put("fullTranscript", summary.fullTranscript)
        }
        jsonFile.writeText(jsonObject.toString(2))

        // 2. Readable text output
        val readableText = buildString {
            appendLine("==========================================")
            appendLine("DAILY CALL SUMMARY: ${summary.phoneNumber}")
            appendLine("Date: ${summary.date} | Calls: ${summary.callCount}")
            appendLine("==========================================")
            appendLine()
            appendLine("OVERVIEW:")
            appendLine(summary.overview)
            appendLine()
            appendLine("DECISIONS (${summary.decisions.size}):")
            if (summary.decisions.isEmpty()) appendLine("  - None detected")
            summary.decisions.forEach { appendLine("  ✓ $it") }
            appendLine()
            appendLine("ACTION ITEMS & COMMITMENTS (${summary.actionItems.size}):")
            if (summary.actionItems.isEmpty()) appendLine("  - None detected")
            summary.actionItems.forEach { appendLine("  ☐ $it") }
            appendLine()
            appendLine("RECORDINGS:")
            summary.recordingFiles.forEach { appendLine("  • $it") }
        }
        txtFile.writeText(readableText)

        return jsonFile
    }

    fun loadAllSummaries(context: Context): List<DailySummary> {
        val summariesDir = context.getExternalFilesDir("Summaries") ?: return emptyList()
        if (!summariesDir.exists()) return emptyList()

        val summaries = mutableListOf<DailySummary>()
        summariesDir.listFiles()?.filter { it.name.endsWith("_summary.json") }?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val recordingsJson = json.optJSONArray("recordings") ?: JSONArray()
                val decisionsJson = json.optJSONArray("decisions") ?: JSONArray()
                val actionsJson = json.optJSONArray("actionItems") ?: JSONArray()

                val recordings = (0 until recordingsJson.length()).map { recordingsJson.getString(it) }
                val decisions = (0 until decisionsJson.length()).map { decisionsJson.getString(it) }
                val actions = (0 until actionsJson.length()).map { actionsJson.getString(it) }

                summaries.add(
                    DailySummary(
                        phoneNumber = json.getString("phoneNumber"),
                        date = json.getString("date"),
                        callCount = json.optInt("callCount", 1),
                        totalDurationSeconds = json.optLong("totalDurationSeconds", 0),
                        recordingFiles = recordings,
                        overview = json.optString("overview", ""),
                        decisions = decisions,
                        actionItems = actions,
                        fullTranscript = json.optString("fullTranscript", "")
                    )
                )
            } catch (e: Exception) {
                // Skip corrupted
            }
        }
        return summaries.sortedByDescending { it.date }
    }
}
