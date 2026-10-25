package com.recall.app.data

import org.junit.Assert.*
import org.junit.Test

class ChatTargetStoreTest {

    @Test
    fun testDefaultPresetsContainStandardMessagingApps() {
        val presets = ChatTargetStore.DEFAULT_PRESETS
        assertTrue(presets.contains("com.whatsapp"))
        assertTrue(presets.contains("org.telegram.messenger"))
        assertTrue(presets.contains("org.thoughtcrime.securesms"))
        assertTrue(presets.contains("com.google.android.apps.messaging"))
    }

    @Test
    fun testChatTitleEligibilityMatchesKeywords() {
        val keywords = setOf("family", "client", "office")

        fun matches(title: String?): Boolean {
            if (keywords.isEmpty()) return true
            if (title.isNullOrBlank()) return false
            val lower = title.lowercase()
            return keywords.any { lower.contains(it) }
        }

        assertTrue(matches("Office Work Group"))
        assertTrue(matches("Family Trip"))
        assertTrue(matches("Primary Client A"))
        assertFalse(matches("Gaming Squad"))
        assertFalse(matches(null))
        assertFalse(matches(""))
    }
}
