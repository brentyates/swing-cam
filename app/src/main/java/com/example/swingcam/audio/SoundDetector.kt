package com.example.swingcam.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Low-level audio monitoring component for detecting loud sounds (e.g., golf ball strikes)
 *
 * Usage:
 * ```
 * val detector = SoundDetector(config)
 * detector.onSoundDetected = { amplitude ->
 *     Log.d("Sound", "Detected: $amplitude")
 * }
 * detector.start()
 * // Later...
 * detector.stop()
 * ```
 */
class SoundDetector(private val config: Config = Config()) {

    data class Config(
        val sampleRate: Int = 44100,           // Sample rate in Hz
        val threshold: Double = 0.3,            // Amplitude threshold (0.0 - 1.0)
        val debounceMs: Long = 500,             // Minimum time between detections (ms)
        val windowSizeMs: Int = 50,             // Analysis window size (ms)
        val enableLogging: Boolean = true       // Enable debug logging
    )

    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var monitoringJob: Job? = null

    var onSoundDetected: ((amplitude: Double) -> Unit)? = null
    var isRunning = false
        private set

    private var lastDetectionTime = 0L
    private val bufferSize: Int
    private val windowSize: Int

    init {
        // Calculate buffer size for the analysis window
        windowSize = (config.sampleRate * config.windowSizeMs) / 1000
        bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(windowSize * 2)

        if (config.enableLogging) {
            Log.d(TAG, "SoundDetector initialized: " +
                    "sampleRate=${config.sampleRate}, " +
                    "threshold=${config.threshold}, " +
                    "bufferSize=$bufferSize, " +
                    "windowSize=$windowSize")
        }
    }

    /**
     * Start monitoring audio for loud sounds
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isRunning = true

            monitoringJob = scope.launch {
                monitorAudio()
            }

            if (config.enableLogging) {
                Log.i(TAG, "Sound detection started")
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            audioRecord = null
        }
    }

    /**
     * Stop monitoring audio
     */
    fun stop() {
        if (!isRunning) {
            return
        }

        isRunning = false
        monitoringJob?.cancel()
        monitoringJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            if (config.enableLogging) {
                Log.i(TAG, "Sound detection stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording", e)
        }
    }

    /**
     * Main audio monitoring loop
     */
    private suspend fun monitorAudio() {
        val buffer = ShortArray(windowSize)

        while (isActive && isRunning) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, windowSize) ?: 0

                if (bytesRead > 0) {
                    val rms = calculateRMS(buffer, bytesRead)
                    val normalizedAmplitude = rms / Short.MAX_VALUE

                    // Check if amplitude exceeds threshold and debounce period has passed
                    if (normalizedAmplitude > config.threshold) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastDetectionTime >= config.debounceMs) {
                            lastDetectionTime = currentTime

                            if (config.enableLogging) {
                                Log.i(TAG, "Sound detected! Amplitude: ${"%.3f".format(normalizedAmplitude)}")
                            }

                            onSoundDetected?.invoke(normalizedAmplitude)
                        }
                    }
                }

                // Small delay to avoid busy-waiting
                delay(10)

            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Error reading audio", e)
                }
            }
        }
    }

    /**
     * Calculate Root Mean Square (RMS) amplitude of audio buffer
     */
    private fun calculateRMS(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length)
    }

    /**
     * Get current audio level (for UI display or calibration)
     * Returns normalized amplitude 0.0 - 1.0
     */
    fun getCurrentLevel(): Double {
        if (!isRunning || audioRecord == null) {
            return 0.0
        }

        val buffer = ShortArray(windowSize)
        val bytesRead = audioRecord?.read(buffer, 0, windowSize) ?: 0

        if (bytesRead > 0) {
            val rms = calculateRMS(buffer, bytesRead)
            return rms / Short.MAX_VALUE
        }

        return 0.0
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val TAG = "SoundDetector"
    }
}
