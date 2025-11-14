package com.example.swingcam.audio

import android.util.Log
import com.example.swingcam.camera.CameraManager
import com.example.swingcam.data.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * State machine for sound-triggered recording
 */
enum class SoundTriggerState {
    IDLE,           // Not active
    LISTENING,      // Listening for sound, camera armed
    PROCESSING      // Shot detected, processing video
}

/**
 * High-level orchestrator for sound-based automatic recording
 *
 * This manager coordinates between SoundDetector (audio monitoring) and CameraManager
 * (video recording) to automatically capture golf swings when a sound is detected.
 *
 * Flow:
 * 1. start() - Begin sound detection mode
 * 2. Arm camera (continuous recording to buffer)
 * 3. Listen for loud sound (golf ball strike)
 * 4. On sound detected: trigger shot extraction
 * 5. Auto-rearm and repeat (continuous mode)
 *
 * This is completely separate from the API-based launch monitor mode, but internally
 * uses the same CameraManager launch monitor API (armLaunchMonitor/shotDetected).
 */
class SoundTriggerManager(
    private val cameraManager: CameraManager,
    private val recordingsDir: File,
    private val recordingConfig: Config
) {

    data class Config(
        val soundThreshold: Double = 0.3,       // Amplitude threshold for detection
        val debounceMs: Long = 2000,            // Min time between shots (2 seconds)
        val postShotDelayMs: Int = 500,         // Delay after sound before stopping
        val rearmDelayMs: Long = 1000,          // Delay before rearming (1 second)
        val enableFrequencyFiltering: Boolean = true,  // Enable frequency analysis to filter voice
        val highFreqThreshold: Double = 0.6,    // High-freq energy ratio threshold
        val enableLogging: Boolean = true
    )

    private var config = Config()
    private var soundDetector: SoundDetector? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var state = SoundTriggerState.IDLE
        private set

    // Statistics
    private var shotsDetected = 0
    private var lastShotTime = 0L
    private var startTime = 0L

    // Callbacks
    var onStateChanged: ((SoundTriggerState) -> Unit)? = null
    var onShotDetected: ((shotNumber: Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Start sound-triggered recording mode
     */
    fun start(customConfig: Config = Config()): Map<String, Any> {
        if (state != SoundTriggerState.IDLE) {
            Log.w(TAG, "Already running in state: $state")
            return mapOf(
                "status" to "error",
                "message" to "Already running"
            )
        }

        config = customConfig
        shotsDetected = 0
        startTime = System.currentTimeMillis()

        // Initialize sound detector
        val soundConfig = SoundDetector.Config(
            threshold = config.soundThreshold,
            debounceMs = config.debounceMs,
            enableFrequencyFiltering = config.enableFrequencyFiltering,
            highFreqThreshold = config.highFreqThreshold,
            enableLogging = config.enableLogging
        )

        soundDetector = SoundDetector(soundConfig).apply {
            onSoundDetected = { amplitude ->
                handleSoundDetected(amplitude)
            }
        }

        // Start listening
        soundDetector?.start()

        // Arm camera for first shot
        scope.launch {
            armCamera()
        }

        if (config.enableLogging) {
            val filterInfo = if (config.enableFrequencyFiltering) " with frequency filtering" else ""
            Log.i(TAG, "Sound trigger mode started with threshold: ${config.soundThreshold}$filterInfo")
        }

        return mapOf(
            "status" to "started",
            "config" to mapOf(
                "threshold" to config.soundThreshold,
                "debounce_ms" to config.debounceMs,
                "frequency_filtering" to config.enableFrequencyFiltering,
                "high_freq_threshold" to config.highFreqThreshold
            )
        )
    }

    /**
     * Stop sound-triggered recording mode
     */
    fun stop(): Map<String, Any> {
        if (state == SoundTriggerState.IDLE) {
            return mapOf(
                "status" to "ok",
                "message" to "Already stopped"
            )
        }

        val duration = (System.currentTimeMillis() - startTime) / 1000.0

        // Stop sound detector
        soundDetector?.stop()
        soundDetector = null

        // Cancel camera if armed
        if (state == SoundTriggerState.LISTENING) {
            cameraManager.cancelLaunchMonitor()
        }

        setState(SoundTriggerState.IDLE)

        if (config.enableLogging) {
            Log.i(TAG, "Sound trigger mode stopped. Duration: ${"%.1f".format(duration)}s, Shots: $shotsDetected")
        }

        return mapOf(
            "status" to "stopped",
            "shots_detected" to shotsDetected,
            "duration_seconds" to duration
        )
    }

    /**
     * Get current status and statistics
     */
    fun getStatus(): Map<String, Any> {
        val duration = if (startTime > 0) {
            (System.currentTimeMillis() - startTime) / 1000.0
        } else {
            0.0
        }

        val timeSinceLastShot = if (lastShotTime > 0) {
            (System.currentTimeMillis() - lastShotTime) / 1000.0
        } else {
            0.0
        }

        return mapOf(
            "state" to state.name.lowercase(),
            "active" to (state != SoundTriggerState.IDLE),
            "shots_detected" to shotsDetected,
            "duration_seconds" to duration,
            "time_since_last_shot" to timeSinceLastShot,
            "config" to mapOf(
                "threshold" to config.soundThreshold,
                "debounce_ms" to config.debounceMs,
                "post_shot_delay_ms" to config.postShotDelayMs,
                "frequency_filtering" to config.enableFrequencyFiltering,
                "high_freq_threshold" to config.highFreqThreshold
            )
        )
    }

    /**
     * Update configuration (only when idle)
     */
    fun updateConfig(newConfig: Config): Map<String, Any> {
        if (state != SoundTriggerState.IDLE) {
            return mapOf(
                "status" to "error",
                "message" to "Cannot update config while running"
            )
        }

        config = newConfig

        return mapOf(
            "status" to "updated",
            "config" to mapOf(
                "threshold" to config.soundThreshold,
                "debounce_ms" to config.debounceMs,
                "frequency_filtering" to config.enableFrequencyFiltering,
                "high_freq_threshold" to config.highFreqThreshold
            )
        )
    }

    /**
     * Arm camera for recording (launch monitor mode)
     */
    private suspend fun armCamera() {
        setState(SoundTriggerState.LISTENING)

        val result = cameraManager.armLaunchMonitor(recordingsDir)

        if (result["status"] != "armed") {
            val error = "Failed to arm camera: ${result["message"]}"
            Log.e(TAG, error)
            onError?.invoke(error)
            setState(SoundTriggerState.IDLE)
        } else {
            if (config.enableLogging) {
                Log.d(TAG, "Camera armed, listening for sound...")
            }
        }
    }

    /**
     * Handle sound detection event
     */
    private fun handleSoundDetected(amplitude: Double) {
        if (state != SoundTriggerState.LISTENING) {
            Log.w(TAG, "Sound detected but not in LISTENING state (state: $state)")
            return
        }

        setState(SoundTriggerState.PROCESSING)
        shotsDetected++
        lastShotTime = System.currentTimeMillis()

        if (config.enableLogging) {
            Log.i(TAG, "Shot #$shotsDetected detected! Amplitude: ${"%.3f".format(amplitude)}")
        }

        onShotDetected?.invoke(shotsDetected)

        // Trigger shot extraction in background
        scope.launch {
            try {
                // Wait for post-shot delay (capture follow-through)
                delay(config.postShotDelayMs.toLong())

                // Trigger extraction (this returns immediately, extraction happens in background)
                val result = cameraManager.shotDetected(
                    File(recordingsDir, "swing_${System.currentTimeMillis()}.mp4"),
                    recordingConfig.duration,
                    0  // No additional delay, already waited above
                )

                if (result["status"] == "success") {
                    if (config.enableLogging) {
                        Log.d(TAG, "Shot extraction initiated successfully")
                    }
                } else {
                    val error = "Shot extraction failed: ${result["message"]}"
                    Log.e(TAG, error)
                    onError?.invoke(error)
                }

                // Wait before rearming to allow extraction to start
                delay(config.rearmDelayMs)

                // Auto-rearm for next shot (only if still active)
                if (state == SoundTriggerState.PROCESSING) {
                    if (config.enableLogging) {
                        Log.d(TAG, "Rearming for next shot...")
                    }
                    armCamera()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in shot processing", e)
                onError?.invoke("Shot processing error: ${e.message}")
                setState(SoundTriggerState.IDLE)
            }
        }
    }

    /**
     * Update state and notify listeners
     */
    private fun setState(newState: SoundTriggerState) {
        if (state != newState) {
            state = newState
            onStateChanged?.invoke(newState)

            if (config.enableLogging) {
                Log.d(TAG, "State changed to: $newState")
            }
        }
    }

    /**
     * Get current audio level (for UI calibration)
     */
    fun getCurrentAudioLevel(): Double {
        return soundDetector?.getCurrentLevel() ?: 0.0
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val TAG = "SoundTriggerManager"
    }
}
