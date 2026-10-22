package com.recall.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailySummaryGeneratorTest {

    @Test
    fun `summarizeDay aggregates transcripts and extracts decisions and actions`() {
        val phoneNumber = "9876543210"
        val date = "2026-10-22"
        val transcripts = listOf(
            "Hello, kal milte hain. Theek hai, 5 baje pakka done. Main quotation bhej dunga shaam ko.",
            "Regarding the contract, we agreed on the payment terms. Deal finalized. Please share invoice."
        )
        val recordingFiles = listOf("9876543210_1729600000.m4a", "9876543210_1729610000.m4a")
        val totalDurationSeconds = 185L

        val summary = DailySummaryGenerator.summarizeDay(
            phoneNumber = phoneNumber,
            date = date,
            transcripts = transcripts,
            recordingFiles = recordingFiles,
            totalDurationSeconds = totalDurationSeconds
        )

        assertEquals("9876543210", summary.phoneNumber)
        assertEquals("2026-10-22", summary.date)
        assertEquals(2, summary.callCount)
        assertEquals(185L, summary.totalDurationSeconds)
        assertEquals(2, summary.recordingFiles.size)

        // Verify decisions extracted
        val decisionsLower = summary.decisions.map { it.lowercase() }
        assertTrue("Should detect theek hai / pakka / done", decisionsLower.any { it.contains("pakka") || it.contains("theek hai") || it.contains("done") })
        assertTrue("Should detect agreed / finalized", decisionsLower.any { it.contains("agreed") || it.contains("finalized") })

        // Verify actions extracted
        val actionsLower = summary.actionItems.map { it.lowercase() }
        assertTrue("Should detect bhej dunga / quotation", actionsLower.any { it.contains("bhej dunga") || it.contains("quotation") })
        assertTrue("Should detect payment / invoice / share", actionsLower.any { it.contains("invoice") || it.contains("share") })
    }

    @Test
    fun `summarizeDay handles empty or non-audible transcripts cleanly`() {
        val summary = DailySummaryGenerator.summarizeDay(
            phoneNumber = "9876543210",
            date = "2026-10-22",
            transcripts = emptyList(),
            recordingFiles = emptyList(),
            totalDurationSeconds = 0L
        )

        assertEquals(0, summary.callCount)
        assertTrue(summary.decisions.isEmpty())
        assertTrue(summary.actionItems.isEmpty())
        assertTrue(summary.overview.contains("No audible speech"))
    }
}
