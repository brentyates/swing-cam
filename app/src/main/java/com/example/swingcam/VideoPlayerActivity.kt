package com.example.swingcam

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.swingcam.databinding.ActivityVideoPlayerBinding
import com.example.swingcam.ui.BallTrackerProcessor
import com.example.swingcam.ui.DrawingOverlayView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

@UnstableApi
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var ballTracker: BallTrackerProcessor
    private var ballTrackingJob: Job? = null

    private val playbackSpeeds = floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
    private var currentSpeedIndex = 4 // Start at 1.0x

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_VIDEO_FILENAME = "video_filename"
        private const val TAG = "VideoPlayerActivity"
        private const val FRAME_STEP_MS = 33L // ~30fps
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
        if (videoPath == null) {
            Toast.makeText(this, "No video path provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ballTracker = BallTrackerProcessor()
        setupPlayer(videoPath)
        setupControls()
        setupDrawingTools()
        setupColorPickers()
        setupBallTracking()
    }

    private fun setupPlayer(videoPath: String) {
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer

            val videoFile = File(videoPath)
            if (!videoFile.exists()) {
                Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            val videoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                videoFile
            )

            val mediaItem = MediaItem.fromUri(videoUri)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true

            // Set up listener for player state changes
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updatePlayPauseButton()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseButton()
                }
            })
        }
    }

    private fun setupControls() {
        // Play/Pause
        binding.playPauseButton.setOnClickListener {
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                } else {
                    it.play()
                }
            }
        }

        // Speed controls
        binding.speedUpButton.setOnClickListener {
            if (currentSpeedIndex < playbackSpeeds.size - 1) {
                currentSpeedIndex++
                updatePlaybackSpeed()
            }
        }

        binding.speedDownButton.setOnClickListener {
            if (currentSpeedIndex > 0) {
                currentSpeedIndex--
                updatePlaybackSpeed()
            }
        }

        // Frame-by-frame controls
        binding.frameBackButton.setOnClickListener {
            seekFrames(-1)
        }

        binding.frameForwardButton.setOnClickListener {
            seekFrames(1)
        }

        // Close button
        binding.closeButton.setOnClickListener {
            finish()
        }

        updatePlaybackSpeed()
    }

    private fun setupDrawingTools() {
        val toolButtons = mapOf(
            binding.toolNoneButton to DrawingOverlayView.DrawingTool.NONE,
            binding.toolLineButton to DrawingOverlayView.DrawingTool.LINE,
            binding.toolAngleButton to DrawingOverlayView.DrawingTool.LINE_WITH_ANGLE,
            binding.toolCircleButton to DrawingOverlayView.DrawingTool.CIRCLE,
            binding.toolFreehandButton to DrawingOverlayView.DrawingTool.FREEHAND,
            binding.toolArrowButton to DrawingOverlayView.DrawingTool.ARROW
        )

        toolButtons.forEach { (button, tool) ->
            button.setOnClickListener {
                binding.drawingOverlay.currentTool = tool
                updateToolButtonsState(toolButtons, button)
            }
        }

        // Undo/Redo/Clear
        binding.undoButton.setOnClickListener {
            binding.drawingOverlay.undo()
            updateUndoRedoButtons()
        }

        binding.redoButton.setOnClickListener {
            binding.drawingOverlay.redo()
            updateUndoRedoButtons()
        }

        binding.clearButton.setOnClickListener {
            binding.drawingOverlay.clearAll()
            updateUndoRedoButtons()
        }

        // Save frame
        binding.saveFrameButton.setOnClickListener {
            saveAnnotatedFrame()
        }

        updateUndoRedoButtons()
    }

    private fun setupColorPickers() {
        val colorButtons = mapOf(
            binding.colorRedButton to Color.RED,
            binding.colorYellowButton to Color.YELLOW,
            binding.colorGreenButton to Color.GREEN,
            binding.colorBlueButton to Color.BLUE,
            binding.colorWhiteButton to Color.WHITE
        )

        colorButtons.forEach { (button, color) ->
            button.setOnClickListener {
                binding.drawingOverlay.drawingColor = color
            }
        }

        // Width controls
        binding.widthUpButton.setOnClickListener {
            val currentWidth = binding.drawingOverlay.strokeWidth
            if (currentWidth < 50f) {
                binding.drawingOverlay.strokeWidth = currentWidth + 2f
                updateWidthText()
            }
        }

        binding.widthDownButton.setOnClickListener {
            val currentWidth = binding.drawingOverlay.strokeWidth
            if (currentWidth > 2f) {
                binding.drawingOverlay.strokeWidth = currentWidth - 2f
                updateWidthText()
            }
        }

        updateWidthText()
    }

    private fun setupBallTracking() {
        ballTracker.onBallDetected = { position ->
            binding.ballTrackerOverlay.updateBallPosition(position)
        }

        ballTracker.onTrailUpdated = { trail ->
            binding.ballTrackerOverlay.updateTrail(trail)
        }

        binding.toggleBallTrackingButton.setOnClickListener {
            if (ballTracker.isTracking) {
                stopBallTracking()
            } else {
                startBallTracking()
            }
        }

        binding.clearBallTrailButton.setOnClickListener {
            ballTracker.clearTrail()
            binding.ballTrackerOverlay.clearTrail()
        }
    }

    private fun startBallTracking() {
        player?.pause()
        ballTracker.startTracking()
        binding.ballTrackerOverlay.visibility = View.VISIBLE
        binding.ballTrackerOverlay.clearTrail()
        binding.toggleBallTrackingButton.text = "Stop Ball Tracking"
        binding.toggleBallTrackingButton.setBackgroundColor(Color.RED)
        binding.trackingStatusText.text = "Tracking..."

        // Start processing frames
        ballTrackingJob = lifecycleScope.launch {
            player?.seekTo(0) // Start from beginning
            delay(100)
            player?.play()

            while (isActive && ballTracker.isTracking) {
                try {
                    val bitmap = captureCurrentFrame()
                    bitmap?.let {
                        val timestamp = player?.currentPosition ?: 0
                        ballTracker.processFrame(it, timestamp)
                    }
                    delay(50) // Process every 50ms (20fps processing rate)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing frame for ball tracking", e)
                }
            }
        }
    }

    private fun stopBallTracking() {
        ballTrackingJob?.cancel()
        ballTracker.stopTracking()
        binding.toggleBallTrackingButton.text = "Start Ball Tracking"
        binding.toggleBallTrackingButton.setBackgroundColor(Color.parseColor("#FF9800"))
        binding.trackingStatusText.text = "Tracking stopped"
    }

    private fun captureCurrentFrame(): Bitmap? {
        return try {
            val playerView = binding.playerView
            val bitmap = Bitmap.createBitmap(
                playerView.width,
                playerView.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            playerView.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing frame", e)
            null
        }
    }

    private fun updateToolButtonsState(toolButtons: Map<View, DrawingOverlayView.DrawingTool>, selectedButton: View) {
        toolButtons.keys.forEach { button ->
            if (button == selectedButton) {
                button.setBackgroundColor(Color.parseColor("#4CAF50"))
            } else {
                button.setBackgroundColor(Color.parseColor("#6C757D"))
            }
        }
    }

    private fun updateUndoRedoButtons() {
        binding.undoButton.isEnabled = binding.drawingOverlay.canUndo()
        binding.redoButton.isEnabled = binding.drawingOverlay.canRedo()
    }

    private fun updateWidthText() {
        binding.widthText.text = binding.drawingOverlay.strokeWidth.toInt().toString()
    }

    private fun updatePlayPauseButton() {
        val isPlaying = player?.isPlaying ?: false
        binding.playPauseButton.text = if (isPlaying) "Pause" else "Play"
    }

    private fun updatePlaybackSpeed() {
        val speed = playbackSpeeds[currentSpeedIndex]
        player?.setPlaybackSpeed(speed)
        binding.speedText.text = "${speed}x"
    }

    private fun seekFrames(frameCount: Int) {
        player?.let { player ->
            player.pause()
            val currentPosition = player.currentPosition
            val newPosition = currentPosition + (frameCount * FRAME_STEP_MS)
            val clampedPosition = newPosition.coerceIn(0, player.duration)
            player.seekTo(clampedPosition)
        }
    }

    private fun saveAnnotatedFrame() {
        try {
            // Capture the player view
            val playerBitmap = captureCurrentFrame()
            if (playerBitmap == null) {
                Toast.makeText(this, "Failed to capture frame", Toast.LENGTH_SHORT).show()
                return
            }

            // Create a composite bitmap with drawings
            val compositeBitmap = Bitmap.createBitmap(
                playerBitmap.width,
                playerBitmap.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(compositeBitmap)

            // Draw video frame
            canvas.drawBitmap(playerBitmap, 0f, 0f, null)

            // Draw ball tracking overlay if visible
            if (binding.ballTrackerOverlay.visibility == View.VISIBLE) {
                binding.ballTrackerOverlay.draw(canvas)
            }

            // Draw annotations
            binding.drawingOverlay.draw(canvas)

            // Save to Pictures directory
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "swing_annotated_$timestamp.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SwingCam")
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    compositeBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                Toast.makeText(this, "Frame saved to Pictures/SwingCam", Toast.LENGTH_LONG).show()
            } else {
                // Fallback to app-specific storage
                val picturesDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "SwingCam")
                picturesDir.mkdirs()
                val file = File(picturesDir, filename)

                FileOutputStream(file).use { outputStream ->
                    compositeBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                Toast.makeText(this, "Frame saved to app storage", Toast.LENGTH_LONG).show()
            }

            playerBitmap.recycle()
            compositeBitmap.recycle()

        } catch (e: IOException) {
            Log.e(TAG, "Error saving frame", e)
            Toast.makeText(this, "Failed to save frame: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ballTrackingJob?.cancel()
        ballTracker.release()
        player?.release()
        player = null
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }
}
