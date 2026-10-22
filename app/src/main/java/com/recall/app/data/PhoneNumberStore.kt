package com.recall.app.data

import android.content.Context
import android.content.SharedPreferences

class PhoneNumberStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "whitelist_prefs"
        private const val KEY_WHITELIST = "whitelist_numbers"

        /**
         * Normalizes phone number: strips all non-digits, keeps the LAST 10 digits.
         * Example: "+91 98765-43210" -> "9876543210"
         * Example: "09876543210" -> "9876543210"
         */
        fun normalize(rawNumber: String?): String {
            if (rawNumber.isNullOrBlank()) return ""
            val digits = rawNumber.filter { it.isDigit() }
            return if (digits.length >= 10) {
                digits.takeLast(10)
            } else {
                digits
            }
        }
    }

    /**
     * Checks if the given raw or normalized number matches the whitelist in O(1).
     */
    fun isWhitelisted(rawNumber: String?): Boolean {
        val normalized = normalize(rawNumber)
        if (normalized.length < 10) return false
        val currentSet = getWhitelist()
        return currentSet.contains(normalized)
    }

    /**
     * Retrieves the set of all normalized numbers in the whitelist.
     */
    fun getWhitelist(): Set<String> {
        return prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
    }

    /**
     * Adds a normalized number to the whitelist.
     */
    fun addNumber(rawNumber: String): Boolean {
        val normalized = normalize(rawNumber)
        if (normalized.length < 10) return false
        val updatedSet = getWhitelist().toMutableSet()
        updatedSet.add(normalized)
        prefs.edit().putStringSet(KEY_WHITELIST, updatedSet).apply()
        return true
    }

    /**
     * Removes a number from the whitelist.
     */
    fun removeNumber(rawNumber: String): Boolean {
        val normalized = normalize(rawNumber)
        val updatedSet = getWhitelist().toMutableSet()
        val removed = updatedSet.remove(normalized)
        if (removed) {
            prefs.edit().putStringSet(KEY_WHITELIST, updatedSet).apply()
        }
        return removed
    }

    /**
     * Replaces the entire whitelist set.
     */
    fun setWhitelist(numbers: Collection<String>) {
        val normalizedSet = numbers.mapNotNull {
            val norm = normalize(it)
            if (norm.length >= 10) norm else null
        }.toSet()
        prefs.edit().putStringSet(KEY_WHITELIST, normalizedSet).apply()
    }
}
