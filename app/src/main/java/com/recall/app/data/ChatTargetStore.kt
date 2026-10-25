package com.recall.app.data

import android.content.Context
import android.content.SharedPreferences

class ChatTargetStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "chat_target_prefs"
        private const val KEY_FEATURE_ENABLED = "chat_capture_enabled"
        private const val KEY_TARGET_PACKAGES = "target_packages"
        private const val KEY_CUSTOM_KEYWORDS = "custom_keywords"

        // Default messaging presets
        val DEFAULT_PRESETS = setOf(
            "com.whatsapp",                         // WhatsApp
            "org.telegram.messenger",               // Telegram
            "org.thoughtcrime.securesms",           // Signal
            "com.google.android.apps.messaging"     // Google Messages / SMS
        )
    }

    /**
     * Master switch for the chat accessibility capture feature.
     * Default: false (explicit user opt-in required).
     */
    var isChatCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_FEATURE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FEATURE_ENABLED, value).apply()

    /**
     * Retrieves the set of monitored application package names.
     */
    fun getTargetPackages(): Set<String> {
        return prefs.getStringSet(KEY_TARGET_PACKAGES, DEFAULT_PRESETS) ?: DEFAULT_PRESETS
    }

    /**
     * Adds a package name to the monitored targets list.
     */
    fun addTargetPackage(pkg: String): Boolean {
        val clean = pkg.trim()
        if (clean.isBlank()) return false
        val current = getTargetPackages().toMutableSet()
        val added = current.add(clean)
        if (added) {
            prefs.edit().putStringSet(KEY_TARGET_PACKAGES, current).apply()
        }
        return added
    }

    /**
     * Removes a package name from the monitored targets list.
     */
    fun removeTargetPackage(pkg: String): Boolean {
        val current = getTargetPackages().toMutableSet()
        val removed = current.remove(pkg.trim())
        if (removed) {
            prefs.edit().putStringSet(KEY_TARGET_PACKAGES, current).apply()
        }
        return removed
    }

    /**
     * Checks if a package is currently in the monitored list.
     */
    fun isPackageMonitored(packageName: String?): Boolean {
        if (!isChatCaptureEnabled || packageName.isNullOrBlank()) return false
        return getTargetPackages().contains(packageName)
    }

    /**
     * Retrieves optional chat title filter keywords.
     * If empty, all chats within monitored packages are eligible.
     */
    fun getCustomKeywords(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_KEYWORDS, emptySet()) ?: emptySet()
    }

    fun addCustomKeyword(keyword: String): Boolean {
        val clean = keyword.trim().lowercase()
        if (clean.isBlank()) return false
        val current = getCustomKeywords().toMutableSet()
        val added = current.add(clean)
        if (added) {
            prefs.edit().putStringSet(KEY_CUSTOM_KEYWORDS, current).apply()
        }
        return added
    }

    fun removeCustomKeyword(keyword: String): Boolean {
        val current = getCustomKeywords().toMutableSet()
        val removed = current.remove(keyword.trim().lowercase())
        if (removed) {
            prefs.edit().putStringSet(KEY_CUSTOM_KEYWORDS, current).apply()
        }
        return removed
    }

    /**
     * Evaluates if a chat window title matches configured criteria.
     */
    fun isChatTitleEligible(chatTitle: String?): Boolean {
        val keywords = getCustomKeywords()
        if (keywords.isEmpty()) return true // No filter applied, match all
        if (chatTitle.isNullOrBlank()) return false
        val lower = chatTitle.lowercase()
        return keywords.any { lower.contains(it) }
    }
}
