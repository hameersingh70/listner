package com.recall.app.data

import android.content.Context
import android.content.SharedPreferences

class AiSettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "ai_settings_prefs"
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_MODEL_DOWNLOADED = "model_downloaded"
        private const val KEY_MODEL_PATH = "model_path"
    }

    /**
     * Whether on-device AI transcription & summarization is enabled.
     * Default: FALSE (optional, disabled by default as specified).
     */
    var isAiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_ENABLED, value).apply()

    var isModelDownloaded: Boolean
        get() = prefs.getBoolean(KEY_MODEL_DOWNLOADED, false)
        set(value) = prefs.edit().putBoolean(KEY_MODEL_DOWNLOADED, value).apply()

    var modelPath: String?
        get() = prefs.getString(KEY_MODEL_PATH, null)
        set(value) = prefs.edit().putString(KEY_MODEL_PATH, value).apply()
}
