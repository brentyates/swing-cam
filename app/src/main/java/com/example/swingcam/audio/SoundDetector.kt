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
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Low-level audio monitoring component for detecting loud sounds (e.g., golf ball strikes)
 *
 * Features:
 * - Simple amplitude detection (RMS)
 * - Advanced frequency analysis to filter out voice (optional)
 * - Detects high-frequency transients characteristic of impacts
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
        val enableFrequencyFiltering: Boolean = true,  // Enable frequency analysis to filter voice
        val highFreqThreshold: Double = 0.6,    // High-freq energy ratio threshold (0.0 - 1.0)
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

                    // Check if amplitude exceeds threshold
                    if (normalizedAmplitude > config.threshold) {
                        // Apply frequency filtering if enabled
                        val passesFrequencyFilter = if (config.enableFrequencyFiltering) {
                            val highFreqRatio = calculateHighFrequencyRatio(buffer, bytesRead)
                            val passes = highFreqRatio > config.highFreqThreshold

                            if (config.enableLogging && !passes) {
                                Log.d(TAG, "Filtered out: amplitude=${"%.3f".format(normalizedAmplitude)}, " +
                                        "highFreqRatio=${"%.3f".format(highFreqRatio)} " +
                                        "(threshold=${config.highFreqThreshold}) - likely voice/low-frequency noise")
                            }

                            passes
                        } else {
                            true // No filtering, pass all loud sounds
                        }

                        if (passesFrequencyFilter) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastDetectionTime >= config.debounceMs) {
                                lastDetectionTime = currentTime

                                if (config.enableLogging) {
                                    val freqInfo = if (config.enableFrequencyFiltering) {
                                        ", highFreq=${"%.3f".format(calculateHighFrequencyRatio(buffer, bytesRead))}"
                                    } else ""
                                    Log.i(TAG, "Impact detected! Amplitude: ${"%.3f".format(normalizedAmplitude)}$freqInfo")
                                }

                                onSoundDetected?.invoke(normalizedAmplitude)
                            }
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
     * Calculate high-frequency energy ratio to filter out voice
     *
     * Ball strikes have:
     * - High zero-crossing rate (rapid signal changes)
     * - Sharp transients (high derivative)
     * - High-frequency content (2-10 kHz)
     *
     * Voice has:
     * - Lower zero-crossing rate
     * - Gradual changes (lower derivative)
     * - Energy concentrated in low frequencies (85-4000 Hz)
     *
     * Returns: Ratio 0.0-1.0, higher values indicate impact-like sounds
     */
    private fun calculateHighFrequencyRatio(buffer: ShortArray, length: Int): Double {
        if (length < 2) return 0.0

        // 1. Calculate zero-crossing rate (ZCR)
        var zeroCrossings = 0
        for (i in 0 until length - 1) {
            if ((buffer[i] >= 0 && buffer[i + 1] < 0) || (buffer[i] < 0 && buffer[i + 1] >= 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toDouble() / length

        // Normalize ZCR: voice typically 0.05-0.15, impacts 0.3-0.6
        val normalizedZCR = (zcr - 0.05) / (0.6 - 0.05)

        // 2. Calculate maximum derivative (transient sharpness)
        var maxDerivative = 0.0
        for (i in 0 until length - 1) {
            val derivative = abs(buffer[i + 1].toDouble() - buffer[i].toDouble())
            if (derivative > maxDerivative) {
                maxDerivative = derivative
            }
        }

        // Normalize derivative: voice typically < 5000, impacts > 10000
        val normalizedDerivative = (maxDerivative - 5000.0) / (20000.0 - 5000.0)

        // 3. Simple high-frequency energy estimation using differences
        // High-frequency signals have large sample-to-sample differences
        var highFreqEnergy = 0.0
        var totalEnergy = 0.0
        for (i in 0 until length - 1) {
            val diff = abs(buffer[i + 1].toDouble() - buffer[i].toDouble())
            highFreqEnergy += diff * diff
            totalEnergy += buffer[i].toDouble() * buffer[i].toDouble()
        }

        val highFreqRatio = if (totalEnergy > 0) {
            sqrt(highFreqEnergy / totalEnergy)
        } else {
            0.0
        }

        // Combine metrics (weighted average)
        // ZCR: 30%, Derivative: 30%, High-freq ratio: 40%
        val combinedScore = (normalizedZCR * 0.3 + normalizedDerivative * 0.3 + highFreqRatio * 0.4)
            .coerceIn(0.0, 1.0)

        return combinedScore
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
