package com.example.swingcam.ui

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.*

/**
 * Processor for tracking golf ball using ML Kit object detection.
 * Analyzes video frames to detect small, circular objects (golf balls) and tracks their path.
 */
class BallTrackerProcessor {

    data class BallPosition(
        val x: Float,
        val y: Float,
        val timestamp: Long,
        val confidence: Float
    )

    private val objectDetector: ObjectDetector
    private val ballTrail = mutableListOf<BallPosition>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var isTracking = false
        private set

    var onBallDetected: ((BallPosition) -> Unit)? = null
    var onTrailUpdated: ((List<BallPosition>) -> Unit)? = null

    init {
        // Configure object detector for small objects (golf balls)
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification() // Enable classification to help identify ball-like objects
            .enableMultipleObjects()
            .build()

        objectDetector = ObjectDetection.getClient(options)
    }

    /**
     * Start tracking the ball in video frames
     */
    fun startTracking() {
        isTracking = true
        ballTrail.clear()
        Log.d(TAG, "Ball tracking started")
    }

    /**
     * Stop tracking the ball
     */
    fun stopTracking() {
        isTracking = false
        Log.d(TAG, "Ball tracking stopped")
    }

    /**
     * Clear the ball trail
     */
    fun clearTrail() {
        ballTrail.clear()
        onTrailUpdated?.invoke(emptyList())
    }

    /**
     * Get the current ball trail
     */
    fun getTrail(): List<BallPosition> = ballTrail.toList()

    /**
     * Process a video frame to detect the ball
     */
    fun processFrame(bitmap: Bitmap, timestamp: Long) {
        if (!isTracking) return

        scope.launch {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)

                objectDetector.process(image)
                    .addOnSuccessListener { detectedObjects ->
                        // Filter for small, likely spherical objects (golf balls)
                        val ballCandidates = detectedObjects.filter { obj ->
                            val width = obj.boundingBox.width()
                            val height = obj.boundingBox.height()

                            // Golf ball characteristics:
                            // 1. Small size (relative to frame)
                            // 2. Roughly circular (width ≈ height)
                            // 3. High confidence
                            val isSmall = width < bitmap.width * 0.15 && height < bitmap.height * 0.15
                            val isCircular = kotlin.math.abs(width - height).toFloat() / kotlin.math.max(width, height) < 0.3
                            val hasConfidence = obj.trackingId != null ||
                                               (obj.labels.isNotEmpty() && obj.labels[0].confidence > 0.5)

                            isSmall && isCircular && hasConfidence
                        }

                        // Find the most likely ball (highest confidence, best circular shape)
                        val ball = ballCandidates.maxByOrNull { obj ->
                            val width = obj.boundingBox.width()
                            val height = obj.boundingBox.height()
                            val circularityScore = 1.0f - (kotlin.math.abs(width - height).toFloat() / kotlin.math.max(width, height))
                            val confidenceScore = if (obj.labels.isNotEmpty()) obj.labels[0].confidence else 0.5f
                            circularityScore * confidenceScore
                        }

                        ball?.let { detectedBall ->
                            val centerX = detectedBall.boundingBox.centerX().toFloat()
                            val centerY = detectedBall.boundingBox.centerY().toFloat()
                            val confidence = if (detectedBall.labels.isNotEmpty()) {
                                detectedBall.labels[0].confidence
                            } else {
                                0.7f // Default confidence if no label
                            }

                            val position = BallPosition(centerX, centerY, timestamp, confidence)

                            // Only add if it's a reasonable movement from last position
                            // (filters out noise and false positives)
                            if (ballTrail.isEmpty() || isReasonableMovement(ballTrail.last(), position, bitmap)) {
                                ballTrail.add(position)

                                withContext(Dispatchers.Main) {
                                    onBallDetected?.invoke(position)
                                    onTrailUpdated?.invoke(ballTrail.toList())
                                }

                                Log.d(TAG, "Ball detected at ($centerX, $centerY) with confidence $confidence")
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Object detection failed", e)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            }
        }
    }

    /**
     * Check if the movement between two positions is reasonable
     * (not too far to be a different object, not too close to be noise)
     */
    private fun isReasonableMovement(last: BallPosition, current: BallPosition, bitmap: Bitmap): Boolean {
        val distance = kotlin.math.sqrt(
            (current.x - last.x) * (current.x - last.x) +
            (current.y - last.y) * (current.y - last.y)
        )

        val timeDelta = current.timestamp - last.timestamp
        val frameWidth = bitmap.width

        // Max movement: ball shouldn't move more than 1/4 of frame width between detections
        val maxDistance = frameWidth * 0.25f

        // Min movement: should move at least a few pixels to not be noise
        val minDistance = 2f

        // Consider time between frames - if more time passed, allow more movement
        val timeAdjustedMaxDistance = if (timeDelta > 0) {
            maxDistance * (timeDelta / 33f) // Assuming ~30fps, 33ms per frame
        } else {
            maxDistance
        }

        return distance >= minDistance && distance <= timeAdjustedMaxDistance
    }

    /**
     * Clean up resources
     */
    fun release() {
        scope.cancel()
        objectDetector.close()
    }

    companion object {
        private const val TAG = "BallTrackerProcessor"
    }
}
