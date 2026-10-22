package com.recall.app.data

import org.junit.Assert.*
import org.junit.Test

class PhoneNumberStoreTest {

    @Test
    fun testNormalizeVariousPhoneFormats() {
        // Standard 10-digit
        assertEquals("9876543210", PhoneNumberStore.normalize("9876543210"))

        // With country code +91
        assertEquals("9876543210", PhoneNumberStore.normalize("+91 9876543210"))

        // With leading 0
        assertEquals("9876543210", PhoneNumberStore.normalize("09876543210"))

        // With spaces, dashes, parentheses
        assertEquals("9876543210", PhoneNumberStore.normalize("+91 (987) 65-43210"))

        // US standard 10 digit
        assertEquals("5551234567", PhoneNumberStore.normalize("+1 (555) 123-4567"))

        // Blank and null handling
        assertEquals("", PhoneNumberStore.normalize(null))
        assertEquals("", PhoneNumberStore.normalize("   "))

        // Short numbers
        assertEquals("123", PhoneNumberStore.normalize("123"))
    }

    @Test
    fun testNormalizationAlwaysTakesLast10Digits() {
        val longInternational = "+4420794609991234567890"
        val normalized = PhoneNumberStore.normalize(longInternational)
        assertEquals(10, normalized.length)
        assertEquals("1234567890", normalized)
    }
}
