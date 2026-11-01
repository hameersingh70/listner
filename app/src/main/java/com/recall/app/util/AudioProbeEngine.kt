package com.recall.app.util

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlin.math.sqrt

/**
 * Intelligently selects the best AudioSource for in-call recording by
 * actually probing each source with a short PCM burst and measuring
 * signal energy (RMS amplitude).
 *
 * On Android 13 with MediaTek chipsets, MIC (1) is silenced during
 * calls. VOICE_RECOGNITION (6) and VOICE_COMMUNICATION (7) are the
 * sources left unblocked for accessibility-style apps.
 *
 * The last working source is cached in SharedPreferences so subsequent
 * calls start immediately without re-probing.
 */
object AudioProbeEngine {

    private const val PREFS_NAME = "audio_probe_prefs"
    private const val KEY_CACHED_SOURCE = "cached_audio_source"
    private const val KEY_CACHED_SOURCE_VALID = "cached_audio_source_valid"

    private const val SAMPLE_RATE = 44100
    private const val PROBE_DURATION_MS = 600L
    private const val SILENCE_RMS_THRESHOLD = 30.0  // amplitude units out of 32768

    /**
     * Priority-ordered list of audio sources to probe.
     * VOICE_RECOGNITION (6) is the primary candidate on Android 13 Xiaomi/MTK
     * when an Accessibility Service is active — it bypasses the call-time mute.
     * UNPROCESSED (9) gives raw microphone frames without AGC/NS interference.
     */
    private val SOURCES_TO_PROBE: List<Int> = buildList {
        // Accessibility-unlocked sources first (highest chance of real audio)
        add(MediaRecorder.AudioSource.VOICE_RECOGNITION)        // 6
        add(MediaRecorder.AudioSource.VOICE_COMMUNICATION)      // 7
        // Raw/unprocessed — skips Android's in-call mute on some MTK HALs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            add(MediaRecorder.AudioSource.UNPROCESSED)          // 9
        }
        add(MediaRecorder.AudioSource.CAMCORDER)                // 5
        add(MediaRecorder.AudioSource.MIC)                      // 1 (usually muted, last resort)
        add(MediaRecorder.AudioSource.DEFAULT)                  // 0
    }

    /**
     * Returns the best AudioSource int for the current call context.
     *
     * 1. If a cached working source exists, validate it quickly (fast path).
     * 2. Otherwise probe all candidates and pick the loudest non-silent one.
     * 3. Falls back to VOICE_RECOGNITION if everything reads as silence
     *    (better than MIC which returns pure zeros on Android 13).
     */
    fun selectBestSource(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Fast path: try cached source first
        if (prefs.getBoolean(KEY_CACHED_SOURCE_VALID, false)) {
            val cached = prefs.getInt(KEY_CACHED_SOURCE, MediaRecorder.AudioSource.VOICE_RECOGNITION)
            val rms = probeSource(cached)
            AppLogger.log("AudioProbe: Fast-path cached source=$cached, RMS=${"%.1f".format(rms)}")
            if (rms > SILENCE_RMS_THRESHOLD) {
                AppLogger.log("AudioProbe: Cached source $cached still active. Using it.")
                return cached
            } else {
                AppLogger.log("AudioProbe: Cached source $cached now reads silence. Re-probing all.")
                invalidateCache(prefs)
            }
        }

        // Full probe cycle
        var bestSource = MediaRecorder.AudioSource.VOICE_RECOGNITION  // safe default
        var bestRms = -1.0

        for (source in SOURCES_TO_PROBE) {
            val rms = probeSource(source)
            AppLogger.log("AudioProbe: Source=$source RMS=${"%.1f".format(rms)}")
            if (rms > SILENCE_RMS_THRESHOLD && rms > bestRms) {
                bestRms = rms
                bestSource = source
                // Stop early if we find strong signal (>500 RMS = clear voice)
                if (rms > 500.0) break
            }
        }

        if (bestRms > SILENCE_RMS_THRESHOLD) {
            AppLogger.log("AudioProbe: Selected source=$bestSource (RMS=${"%.1f".format(bestRms)}). Caching.")
            prefs.edit()
                .putInt(KEY_CACHED_SOURCE, bestSource)
                .putBoolean(KEY_CACHED_SOURCE_VALID, true)
                .apply()
        } else {
            AppLogger.log("AudioProbe: All sources silent. Defaulting to VOICE_RECOGNITION ($bestSource). Will retry next call.")
            // Don't cache — retry next call in case accessibility service activates
        }

        return bestSource
    }

    /**
     * Clears the cached source (call this if a recording turns out silent
     * after the full call, so we re-probe on the next call).
     */
    fun invalidateCache(context: Context) {
        invalidateCache(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    private fun invalidateCache(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_CACHED_SOURCE_VALID, false).apply()
    }

    /**
     * Opens an AudioRecord on [source], reads [PROBE_DURATION_MS] of PCM,
     * computes RMS amplitude, and releases the AudioRecord.
     * Returns 0.0 if the source cannot be opened or only delivers silence.
     */
    private fun probeSource(source: Int): Double {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return 0.0

        val bufferSize = maxOf(minBuf, SAMPLE_RATE / 2)  // at least 500ms worth

        var audioRecord: AudioRecord? = null
        return try {
            audioRecord = AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.log("AudioProbe: Source=$source failed to initialize AudioRecord")
                return 0.0
            }

            audioRecord.startRecording()

            val samples = ShortArray(bufferSize / 2)
            val startMs = System.currentTimeMillis()
            var sumSq = 0.0
            var count = 0L

            while (System.currentTimeMillis() - startMs < PROBE_DURATION_MS) {
                val read = audioRecord.read(samples, 0, samples.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        val s = samples[i].toDouble()
                        sumSq += s * s
                        count++
                    }
                }
            }

            if (count == 0L) 0.0 else sqrt(sumSq / count)
        } catch (e: Exception) {
            AppLogger.log("AudioProbe: Exception probing source=$source: ${e.message}")
            0.0
        } finally {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (ignored: Exception) {}
        }
    }
}
