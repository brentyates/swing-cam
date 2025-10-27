package com.example.swingcam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.camera.view.PreviewView
import com.example.swingcam.data.Config
import com.example.swingcam.data.ShutterMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Camera2-based camera manager with high-speed video support (240 fps)
 * Uses CameraConstrainedHighSpeedCaptureSession for slow-motion recording
 */
class Camera2Manager(
    private val context: Context,
    private val previewView: PreviewView
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var imageReader: ImageReader? = null
    private var currentOutputFile: File? = null

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cached preview frame for web interface
    @Volatile
    private var cachedPreviewFrame: ByteArray? = null

    // Camera configuration
    private var cameraId: String = ""
    private var characteristics: CameraCharacteristics? = null
    private var previewSize: Size = Size(1920, 1080)
    private var videoSize: Size = Size(1920, 1080)
    private val targetFps = 240

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

    suspend fun setupCamera(config: Config) {
        Log.d(TAG, "Starting Camera2 setup for 240fps recording")

        try {
            // Find back camera with high-speed support
            cameraId = findHighSpeedCamera()
            characteristics = cameraManager.getCameraCharacteristics(cameraId)

            Log.i(TAG, "Selected camera: $cameraId for 240fps recording")

            // Open camera
            openCamera()

        } catch (e: Exception) {
            Log.e(TAG, "Camera setup failed", e)
            throw e
        }
    }

    private fun findHighSpeedCamera(): String {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)

            // Check for back camera
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val highSpeedSizes = configMap?.highSpeedVideoSizes

                if (!highSpeedSizes.isNullOrEmpty()) {
                    // Check if 240fps is supported
                    for (size in highSpeedSizes) {
                        val fpsRanges = configMap.getHighSpeedVideoFpsRangesFor(size)
                        if (fpsRanges.any { it.upper == 240 }) {
                            // Prefer 1080p if available, otherwise use largest size
                            val preferred1080p = highSpeedSizes.find { it.width == 1920 && it.height == 1080 }
                            videoSize = preferred1080p ?: size
                            previewSize = videoSize

                            Log.i(TAG, "Found 240fps support at ${videoSize.width}x${videoSize.height}")
                            return id
                        }
                    }
                }
            }
        }
        throw IllegalStateException("No camera with 240fps support found")
    }

    private suspend fun openCamera() = suspendCoroutine<Unit> { continuation ->
        var resumed = false

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened successfully")
                    cameraDevice = camera

                    // Setup preview
                    setupPreviewSurface()

                    synchronized(this) {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(Unit)
                        }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null

                    synchronized(this) {
                        if (!resumed) {
                            resumed = true
                            continuation.resumeWithException(Exception("Camera disconnected"))
                        }
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error (likely camera disconnected during recording)")
                    camera.close()
                    cameraDevice = null

                    synchronized(this) {
                        if (!resumed) {
                            resumed = true
                            continuation.resumeWithException(Exception("Camera error: $error"))
                        }
                    }
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception opening camera", e)
            synchronized(this) {
                if (!resumed) {
                    resumed = true
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private fun setupPreviewSurface() {
        // Get TextureView from PreviewView
        val textureView = getTextureView(previewView)

        if (textureView != null) {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    Log.d(TAG, "Preview surface available: ${width}x${height}")
                    setupPreviewSession()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    Log.d(TAG, "Preview surface size changed: ${width}x${height}")
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    Log.d(TAG, "Preview surface destroyed")
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    // Frame updated
                }
            }

            // If surface already available, setup preview
            if (textureView.isAvailable) {
                setupPreviewSession()
            }
        } else {
            Log.w(TAG, "Could not get TextureView from PreviewView, creating ImageReader for preview")
            setupImageReaderPreview()
        }
    }

    private fun getTextureView(previewView: PreviewView): TextureView? {
        // Try to find TextureView in PreviewView hierarchy
        for (i in 0 until previewView.childCount) {
            val child = previewView.getChildAt(i)
            if (child is TextureView) {
                return child
            }
        }
        return null
    }

    private fun setupImageReaderPreview() {
        // Create ImageReader for preview snapshots
        imageReader = ImageReader.newInstance(
            previewSize.width,
            previewSize.height,
            ImageFormat.JPEG,
            2
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    cachedPreviewFrame = bytes
                    Log.d(TAG, "Preview frame captured: ${bytes.size / 1024}KB")
                } finally {
                    image.close()
                }
            }
        }, cameraHandler)

        setupPreviewSession()
    }

    private fun setupPreviewSession() {
        val camera = cameraDevice ?: return

        try {
            val textureView = getTextureView(previewView)
            val previewSurface = if (textureView != null && textureView.isAvailable) {
                val texture = textureView.surfaceTexture!!
                texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                Surface(texture)
            } else {
                imageReader?.surface
            }

            if (previewSurface == null) {
                Log.e(TAG, "No preview surface available")
                return
            }

            // Create preview request
            val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface)

            // Set to 30fps for preview (lower FPS to reduce load and prevent camera errors)
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(30, 30)
            )

            // Create capture session
            camera.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "Preview session configured")
                        captureSession = session

                        try {
                            session.setRepeatingRequest(
                                previewRequestBuilder.build(),
                                null,
                                cameraHandler
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start preview", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Preview session configuration failed")
                    }
                },
                cameraHandler
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup preview session", e)
        }
    }

    suspend fun startRecording(outputFile: File, config: Config) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        val camera = cameraDevice ?: throw IllegalStateException("Camera not initialized")

        try {
            // Store output file for later callback
            currentOutputFile = outputFile

            // Stop preview session
            captureSession?.close()
            captureSession = null

            // Setup MediaRecorder
            setupMediaRecorder(outputFile)

            // Create high-speed capture session
            createHighSpeedSession(outputFile, config)

            isRecording = true
            onRecordingStarted?.invoke()

            Log.d(TAG, "Recording started: ${outputFile.name}")

            // Auto-stop after configured duration
            scope.launch {
                delay((config.duration * 1000).toLong())
                stopRecording()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            isRecording = false
            onRecordingError?.invoke(e.message ?: "Unknown error")
        }
    }

    private fun setupMediaRecorder(outputFile: File) {
        // Encode at 240fps to preserve all captured frames
        // Playback speed will be controlled by the player (0.125x = 1/8 speed for slow-motion)

        mediaRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoFrameRate(targetFps)  // Encode at 240fps to preserve all frames
            setVideoEncodingBitRate(100_000_000)  // 100 Mbps for high quality 240fps
            setOutputFile(outputFile.absolutePath)
            prepare()
        }

        Log.i(TAG, "MediaRecorder configured: ${videoSize.width}x${videoSize.height} @ ${targetFps}fps (playback will be slowed via player)")
    }

    private fun createHighSpeedSession(outputFile: File, config: Config) {
        val camera = cameraDevice ?: return
        val recorder = mediaRecorder ?: return

        try {
            val recordSurface = recorder.surface

            // Get the FIXED 240fps range (240-240, not 30-240)
            val configMap = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val fpsRange = configMap?.getHighSpeedVideoFpsRangesFor(videoSize)
                ?.find { it.lower == targetFps && it.upper == targetFps } // Fixed framerate only
                ?: configMap?.getHighSpeedVideoFpsRangesFor(videoSize)
                    ?.find { it.upper == targetFps } // Fallback to variable if fixed not available
                ?: throw IllegalStateException("240fps not supported for ${videoSize.width}x${videoSize.height}")

            Log.i(TAG, "Creating high-speed session with FPS range: ${fpsRange.lower}-${fpsRange.upper}")

            // Create high-speed capture request
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            requestBuilder.addTarget(recordSurface)
            requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

            // Apply shutter mode settings
            applyShutterMode(requestBuilder, config.shutterMode)

            // Create high-speed session configuration
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_HIGH_SPEED,
                listOf(OutputConfiguration(recordSurface)),
                { command -> cameraHandler.post(command) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "High-speed capture session configured")
                        captureSession = session

                        if (session is CameraConstrainedHighSpeedCaptureSession) {
                            try {
                                // Get high-speed request list
                                val requestList = session.createHighSpeedRequestList(requestBuilder.build())

                                // Start recording
                                recorder.start()

                                // Start high-speed capture
                                session.setRepeatingBurst(requestList, null, cameraHandler)

                                Log.i(TAG, "High-speed recording started at ${fpsRange.upper}fps")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start high-speed capture", e)
                                onRecordingError?.invoke(e.message ?: "Failed to start capture")
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "High-speed session configuration failed")
                        onRecordingError?.invoke("Session configuration failed")
                    }
                }
            )

            camera.createCaptureSession(sessionConfig)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create high-speed session", e)
            throw e
        }
    }

    private fun applyShutterMode(requestBuilder: CaptureRequest.Builder, shutterMode: ShutterMode) {
        when (shutterMode) {
            ShutterMode.AUTO -> {
                Log.i(TAG, "Shutter mode: AUTO")
                // Use auto-exposure
            }
            ShutterMode.FAST_MOTION -> {
                // 1/2000s = 500,000 ns
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 500_000L)
                requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 1200)
                Log.i(TAG, "Shutter mode: FAST_MOTION (1/2000s, ISO 1200)")
            }
            ShutterMode.ULTRA_FAST -> {
                // 1/4000s = 250,000 ns
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 250_000L)
                requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 2400)
                Log.i(TAG, "Shutter mode: ULTRA_FAST (1/4000s, ISO 2400)")
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) {
            return
        }

        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null

            captureSession?.close()
            captureSession = null

            isRecording = false
            Log.d(TAG, "Recording stopped")

            // Call finished callback with output file
            val outputFile = currentOutputFile
            currentOutputFile = null

            if (outputFile != null && outputFile.exists()) {
                Log.d(TAG, "Recording saved: ${outputFile.absolutePath}")
                onRecordingFinished?.invoke(outputFile)
            } else {
                Log.e(TAG, "Output file not found or null")
                onRecordingError?.invoke("Recording failed: output file not found")
            }

            // Check if camera is still open, reopen if needed
            if (cameraDevice == null) {
                Log.w(TAG, "Camera was closed, reopening...")
                scope.launch {
                    try {
                        openCamera()
                        Log.d(TAG, "Camera reopened successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reopen camera", e)
                    }
                }
            } else {
                // Restart preview
                setupPreviewSession()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            currentOutputFile = null
            onRecordingError?.invoke(e.message ?: "Unknown error")
        }
    }

    fun cleanup() {
        stopRecording()

        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null

        imageReader?.close()
        imageReader = null

        scope.cancel()
        cameraThread.quitSafely()

        Log.d(TAG, "Camera cleanup complete")
    }

    // Launch Monitor API - TODO: Implement similar to CameraX version
    suspend fun armLaunchMonitor(tempDir: File): Map<String, Any> {
        return mapOf("status" to "error", "message" to "Launch monitor not yet implemented for Camera2")
    }

    suspend fun shotDetected(outputFile: File, duration: Int, postShotDelay: Int): Map<String, Any> {
        return mapOf("status" to "error", "message" to "Launch monitor not yet implemented for Camera2")
    }

    fun cancelLaunchMonitor(): Map<String, Any> {
        return mapOf("status" to "error", "message" to "Launch monitor not yet implemented for Camera2")
    }

    fun getLMStatus(): Map<String, Any> {
        return mapOf("state" to "idle", "message" to "Launch monitor not yet implemented for Camera2")
    }

    // Preview snapshot for web interface
    fun capturePreviewFrame() {
        // If using ImageReader, frames are automatically captured
        // If using TextureView, we'd need to capture from the texture
        Log.d(TAG, "Preview frame capture requested (handled by ImageReader)")
    }

    fun getLatestPreviewFrame(): ByteArray? {
        return cachedPreviewFrame
    }

    companion object {
        private const val TAG = "Camera2Manager"
    }
}
