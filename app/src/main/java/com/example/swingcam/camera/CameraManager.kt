package com.example.swingcam.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import java.nio.ByteBuffer
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import com.example.swingcam.data.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Launch Monitor states
 */
enum class LMState {
    IDLE,
    ARMED,
    PROCESSING
}

/**
 * Manages camera recording using CameraX Video API
 * Supports both regular recording and launch monitor mode (pre-buffering)
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {

    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var activeRecording: Recording? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cached preview frame for web interface
    @Volatile
    private var cachedPreviewFrame: ByteArray? = null

    var isRecording = false
        private set

    // Launch monitor state
    var lmState = LMState.IDLE
        private set
    private var lmTempFile: File? = null
    private var lmStartTime: Long = 0
    private val maxLMDuration = 60  // Maximum 60 seconds of continuous recording

    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingFinished: ((File) -> Unit)? = null
    var onRecordingError: ((String) -> Unit)? = null
    var onExtractionComplete: ((File) -> Unit)? = null
    var onExtractionError: ((String) -> Unit)? = null

    suspend fun setupCamera() {
        Log.d(TAG, "Starting camera setup")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        try {
            val cameraProvider = cameraProviderFuture.get()
            Log.d(TAG, "Got camera provider")

            // Use highest quality available for slow-motion
            // Pixel 9 will automatically use its slow-motion capabilities
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.HIGHEST, Quality.UHD, Quality.FHD, Quality.HD)
            )

            val recorder = Recorder.Builder()
                .setExecutor(executor)
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)
            Log.d(TAG, "Video capture configured")

            // Setup image capture for preview snapshots
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(75) // Increased quality to 75% for better preview consistency
                .build()
            Log.d(TAG, "Image capture configured")

            // Setup preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            Log.d(TAG, "Preview configured with surface provider")

            // Use back camera (rear-facing for recording swings)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                Log.d(TAG, "Unbound all previous camera instances")

                // Bind preview, video capture, and image capture together
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture,
                    imageCapture
                )

                Log.d(TAG, "Camera setup complete with preview, video capture, and image capture. Camera ID: ${camera.cameraInfo.cameraSelector}")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                throw e
            }

        } catch (e: Exception) {
            Log.e(TAG, "Camera setup failed", e)
            throw e
        }
    }

    suspend fun startRecording(outputFile: File, config: Config) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        val videoCaptureInstance = videoCapture
            ?: throw IllegalStateException("Camera not initialized")

        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        isRecording = true
        onRecordingStarted?.invoke()

        activeRecording = videoCaptureInstance.output
            .prepareRecording(context, outputOptions)
            .start(executor, createRecordingListener(outputFile))

        Log.d(TAG, "Recording started: ${outputFile.name}")

        // Auto-stop after configured duration
        delay(config.duration.toLong())
        stopRecording()
    }

    private fun createRecordingListener(outputFile: File): Consumer<VideoRecordEvent> {
        return Consumer { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    Log.d(TAG, "Recording event: Started")
                }
                is VideoRecordEvent.Finalize -> {
                    isRecording = false

                    if (!event.hasError()) {
                        Log.d(TAG, "Recording saved: ${outputFile.absolutePath}")
                        onRecordingFinished?.invoke(outputFile)
                    } else {
                        val error = "Recording error: ${event.error}"
                        Log.e(TAG, error)
                        outputFile.delete()
                        onRecordingError?.invoke(error)
                    }
                }
                is VideoRecordEvent.Status -> {
                    // Progress updates if needed
                }
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) {
            return
        }

        activeRecording?.stop()
        activeRecording = null
        Log.d(TAG, "Recording stopped")
    }

    fun cleanup() {
        activeRecording?.stop()
        activeRecording = null
        isRecording = false
        lmState = LMState.IDLE
        lmTempFile?.delete()
        lmTempFile = null
        cachedPreviewFrame = null
        scope.cancel() // Cancel any background extraction jobs
        executor.shutdown()
    }

    // Launch Monitor API

    suspend fun armLaunchMonitor(tempDir: File): Map<String, Any> {
        // If already armed or processing, discard and restart
        if (lmState == LMState.ARMED || lmState == LMState.PROCESSING) {
            Log.w(TAG, "Already ${lmState.name}, discarding and restarting...")

            // Stop current recording
            if (isRecording) {
                stopRecording()
                delay(100) // Brief pause to ensure recording stopped
            }

            // Delete temp file
            lmTempFile?.let { oldFile ->
                if (oldFile.exists()) {
                    val sizeMB = oldFile.length() / (1024.0 * 1024.0)
                    Log.w(TAG, "Discarding temp file: ${oldFile.name} (${String.format("%.1f", sizeMB)}MB)")
                    oldFile.delete()
                }
            }
            lmTempFile = null
            lmState = LMState.IDLE
            isRecording = false
        }

        // Check for regular recording (manual button press)
        if (isRecording) {
            Log.w(TAG, "Cannot arm: regular recording in progress")
            return mapOf(
                "status" to "error",
                "message" to "Camera busy with regular recording"
            )
        }

        val videoCaptureInstance = videoCapture
            ?: return mapOf(
                "status" to "error",
                "message" to "Camera not initialized"
            )

        // Create temp file
        val timestamp = System.currentTimeMillis()
        lmTempFile = File(tempDir, "temp_lm_$timestamp.mp4")

        val outputOptions = FileOutputOptions.Builder(lmTempFile!!).build()

        lmState = LMState.ARMED
        lmStartTime = System.currentTimeMillis()
        isRecording = true

        try {
            activeRecording = videoCaptureInstance.output
                .prepareRecording(context, outputOptions)
                .start(executor, createLMRecordingListener())

            Log.d(TAG, "Launch monitor armed, recording to ${lmTempFile?.name}")

            return mapOf(
                "status" to "armed",
                "max_duration" to maxLMDuration
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to arm launch monitor", e)
            isRecording = false
            lmState = LMState.IDLE
            lmTempFile?.delete()
            lmTempFile = null
            return mapOf(
                "status" to "error",
                "message" to (e.message ?: "Unknown error")
            )
        }
    }

    suspend fun shotDetected(outputFile: File, duration: Int, postShotDelay: Int): Map<String, Any> {
        if (lmState != LMState.ARMED) {
            Log.w(TAG, "Cannot detect shot: not armed (state: ${lmState.name})")
            return mapOf(
                "status" to "error",
                "message" to "Not armed (state: ${lmState.name.lowercase()})"
            )
        }

        // Check for timeout
        val recordingDuration = (System.currentTimeMillis() - lmStartTime) / 1000
        if (recordingDuration >= maxLMDuration) {
            Log.e(TAG, "Launch monitor recording exceeded timeout (${recordingDuration}s >= ${maxLMDuration}s)")
            cancelLaunchMonitor()
            return mapOf(
                "status" to "error",
                "message" to "Recording timeout - armed too long without shot detection"
            )
        }

        Log.d(TAG, "Shot detected after ${recordingDuration}s, waiting ${postShotDelay}ms to capture follow-through...")

        lmState = LMState.PROCESSING

        // Wait for post-shot delay to capture the full swing follow-through
        delay(postShotDelay.toLong())

        // Stop recording
        stopRecording()

        // Wait a moment for recording to finalize
        delay(500)

        val tempFile = lmTempFile
        if (tempFile == null || !tempFile.exists()) {
            Log.e(TAG, "Temp file not found or null")
            lmState = LMState.IDLE
            lmTempFile = null
            isRecording = false
            return mapOf(
                "status" to "error",
                "message" to "Temp file not found"
            )
        }

        val tempFileSizeMB = tempFile.length() / (1024.0 * 1024.0)
        Log.d(TAG, "Temp file size: ${String.format("%.1f", tempFileSizeMB)}MB, starting async extraction...")

        // Return to IDLE immediately so camera can be re-armed
        lmState = LMState.IDLE
        isRecording = false
        lmTempFile = null

        // Launch extraction in background
        scope.launch {
            try {
                Log.d(TAG, "Background extraction started for ${outputFile.name}")
                val success = extractLastNSeconds(tempFile, outputFile, duration)

                if (success) {
                    Log.d(TAG, "Clip extracted successfully: ${outputFile.absolutePath}")
                    val outputSizeMB = outputFile.length() / (1024.0 * 1024.0)
                    Log.d(TAG, "Output file size: ${String.format("%.1f", outputSizeMB)}MB")

                    onExtractionComplete?.invoke(outputFile)
                } else {
                    Log.e(TAG, "Failed to extract clip from temp file")
                    onExtractionError?.invoke("Failed to extract clip")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background extraction failed", e)
                onExtractionError?.invoke(e.message ?: "Unknown error")
            } finally {
                // Always clean up temp file
                try {
                    if (tempFile.exists()) {
                        Log.d(TAG, "Deleting temp file: ${tempFile.name}")
                        tempFile.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete temp file", e)
                }
            }
        }

        // Return success immediately - extraction happens in background
        return mapOf(
            "status" to "success",
            "filename" to outputFile.name,
            "message" to "Extraction in progress"
        )
    }

    fun cancelLaunchMonitor(): Map<String, Any> {
        if (lmState == LMState.IDLE) {
            return mapOf(
                "status" to "ok",
                "message" to "Already idle"
            )
        }

        Log.w(TAG, "Cancelling launch monitor recording (state: ${lmState.name})...")

        stopRecording()

        // Clean up temp file
        try {
            lmTempFile?.let { file ->
                if (file.exists()) {
                    val sizeMB = file.length() / (1024.0 * 1024.0)
                    Log.d(TAG, "Deleting temp file: ${file.name} (${String.format("%.1f", sizeMB)}MB)")
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete temp file during cancel", e)
        }

        lmTempFile = null
        lmState = LMState.IDLE
        isRecording = false

        Log.i(TAG, "Launch monitor cancelled successfully")

        return mapOf("status" to "cancelled")
    }

    fun getLMStatus(): Map<String, Any> {
        val recordingDuration = if (lmStartTime > 0) {
            (System.currentTimeMillis() - lmStartTime) / 1000.0
        } else {
            0.0
        }

        return mapOf(
            "state" to lmState.name.lowercase(),
            "recording_duration" to recordingDuration,
            "max_duration" to maxLMDuration
        )
    }

    private fun createLMRecordingListener(): Consumer<VideoRecordEvent> {
        return Consumer { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    Log.d(TAG, "LM Recording event: Started")
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        val error = "LM Recording error: ${event.error}"
                        Log.e(TAG, error)
                        lmTempFile?.delete()
                        lmTempFile = null
                        lmState = LMState.IDLE
                        isRecording = false
                    } else {
                        Log.d(TAG, "LM Recording finalized: ${lmTempFile?.absolutePath}")
                    }
                }
                is VideoRecordEvent.Status -> {
                    // Check for timeout
                    val duration = (System.currentTimeMillis() - lmStartTime) / 1000
                    if (duration >= maxLMDuration && lmState == LMState.ARMED) {
                        Log.w(TAG, "Launch monitor timeout ($maxLMDuration s) - cancelling")
                        cancelLaunchMonitor()
                    }
                }
            }
        }
    }

    private fun extractLastNSeconds(inputFile: File, outputFile: File, duration: Int): Boolean {
        try {
            Log.d(TAG, "Extracting last $duration milliseconds from ${inputFile.name}")

            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            // Get video duration in microseconds
            var videoDurationUs = 0L
            val trackCount = extractor.trackCount

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    videoDurationUs = format.getLong(android.media.MediaFormat.KEY_DURATION)
                    break
                }
            }

            if (videoDurationUs == 0L) {
                Log.e(TAG, "Could not determine video duration")
                extractor.release()
                return false
            }

            val durationUs = duration * 1_000L  // Convert milliseconds to microseconds
            val startTimeUs = maxOf(0L, videoDurationUs - durationUs)

            Log.d(TAG, "Video duration: ${videoDurationUs / 1_000_000.0}s, extracting from ${startTimeUs / 1_000_000.0}s")

            // Setup muxer for output
            val muxer = android.media.MediaMuxer(
                outputFile.absolutePath,
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val trackIndexMap = mutableMapOf<Int, Int>()

            // Add all tracks to muxer
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val muxerTrackIndex = muxer.addTrack(format)
                trackIndexMap[i] = muxerTrackIndex
            }

            muxer.start()

            // Extract and write samples from startTimeUs onwards
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)  // 1MB buffer

            for (trackIndex in 0 until trackCount) {
                extractor.selectTrack(trackIndex)
                extractor.seekTo(startTimeUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        break
                    }

                    val sampleTime = extractor.sampleTime
                    val sampleFlags = extractor.sampleFlags

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = sampleTime - startTimeUs  // Adjust timestamp
                    bufferInfo.flags = sampleFlags

                    val muxerTrack = trackIndexMap[trackIndex] ?: break
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)

                    extractor.advance()
                }

                extractor.unselectTrack(trackIndex)
            }

            muxer.stop()
            muxer.release()
            extractor.release()

            Log.d(TAG, "Extraction successful: ${outputFile.absolutePath}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            return false
        }
    }

    /**
     * Capture a preview frame for the web interface
     * This captures a JPEG snapshot and caches it in memory
     */
    fun capturePreviewFrame() {
        val imageCaptureInstance = imageCapture
        if (imageCaptureInstance == null) {
            Log.w(TAG, "Image capture not initialized, cannot capture preview frame")
            return
        }

        imageCaptureInstance.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        // Check image format - might be JPEG already or YUV
                        val jpegBytes = when (image.format) {
                            android.graphics.ImageFormat.JPEG -> {
                                // Already JPEG, just extract bytes
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                bytes
                            }
                            android.graphics.ImageFormat.YUV_420_888 -> {
                                // Convert YUV to JPEG
                                imageProxyToJpeg(image)
                            }
                            else -> {
                                Log.w(TAG, "Unexpected image format: ${image.format}")
                                null
                            }
                        }

                        if (jpegBytes != null) {
                            cachedPreviewFrame = jpegBytes
                            Log.d(TAG, "Preview frame captured: ${jpegBytes.size / 1024}KB (format: ${image.format})")
                        } else {
                            Log.w(TAG, "Failed to convert image to JPEG")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process preview frame", e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Failed to capture preview frame: ${exception.message}")
                }
            }
        )
    }

    /**
     * Convert ImageProxy (YUV_420_888) to JPEG bytes
     */
    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // Copy Y plane
            yBuffer.get(nv21, 0, ySize)

            // Convert U and V planes to NV21 format (interleaved VU)
            val uvPixelStride = image.planes[1].pixelStride
            if (uvPixelStride == 1) {
                // U and V are already tightly packed, just copy them
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)
            } else {
                // U and V are interleaved, need to copy them properly
                val uvRowStride = image.planes[1].rowStride
                val width = image.width
                val height = image.height

                var pos = ySize
                for (row in 0 until height / 2) {
                    for (col in 0 until width / 2) {
                        val vuPos = row * uvRowStride + col * uvPixelStride
                        nv21[pos++] = vBuffer.get(vuPos)
                        nv21[pos++] = uBuffer.get(vuPos)
                    }
                }
            }

            // Convert NV21 to JPEG
            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                image.width,
                image.height,
                null
            )

            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, image.width, image.height),
                75, // Higher quality for better preview
                outputStream
            )

            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error converting ImageProxy to JPEG", e)
            null
        }
    }

    /**
     * Get the latest cached preview frame as JPEG bytes
     * Returns null if no frame has been captured yet
     */
    fun getLatestPreviewFrame(): ByteArray? {
        return cachedPreviewFrame
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}
