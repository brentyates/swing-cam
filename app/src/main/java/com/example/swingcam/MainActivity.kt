package com.example.swingcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.swingcam.camera.CameraManager
import com.example.swingcam.data.Config
import com.example.swingcam.data.RecordingMetadata
import com.example.swingcam.data.RecordingRepository
import com.example.swingcam.databinding.ActivityMainBinding
import com.example.swingcam.server.HttpServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var repository: RecordingRepository
    private var config: Config = Config()
    private var player: ExoPlayer? = null
    private var isShowingReplay = false

    private var httpServerService: HttpServerService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HttpServerService.LocalBinder
            httpServerService = binder.getService()
            httpServerService?.serverCallback = createServerCallback()
            serviceBound = true
            updateServerStatus()
            Log.d(TAG, "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            httpServerService = null
            serviceBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on while app is active
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        repository = RecordingRepository(this)
        config = Config.load(filesDir)

        setupButtons()
        checkPermissions()

        // Auto-start HTTP server
        startHttpServer()
    }

    private fun setupButtons() {
        // Menu button to open recordings/settings
        binding.menuButton.setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }

        // Manual record button
        binding.recordButton.setOnClickListener {
            lifecycleScope.launch {
                startRecording()
            }
        }

        // Tap anywhere to switch between camera and replay
        binding.tapToSwitch.setOnClickListener {
            if (isShowingReplay) {
                showCameraPreview()
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_CODE_PERMISSIONS
            )
        } else {
            initializeCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeCamera()
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun initializeCamera() {
        // Clean up any old launch monitor temp files
        cleanupTempFiles()

        // Check for orphaned videos (videos without metadata)
        val orphanedVideos = repository.getOrphanedVideos()
        if (orphanedVideos.isNotEmpty()) {
            Log.w(TAG, "Found ${orphanedVideos.size} orphaned video(s) without metadata:")
            orphanedVideos.forEach { video ->
                Log.w(TAG, "  - ${video.name} (${video.length() / 1024}KB)")
            }
        }

        cameraManager = CameraManager(this, this, binding.cameraPreview)

        cameraManager.onExtractionComplete = { outputFile ->
            // Called when background extraction finishes
            try {
                // Load existing metadata and update with actual file size
                val existingMetadata = repository.getRecording(outputFile.name)
                if (existingMetadata != null) {
                    val updatedMetadata = existingMetadata.copy(fileSize = outputFile.length())
                    repository.saveMetadata(updatedMetadata)
                    Log.d(TAG, "Metadata updated with file size for ${outputFile.name}")

                    runOnUiThread {
                        playRecordingInline(updatedMetadata)
                    }
                } else {
                    // Fallback: create metadata from scratch (for regular recordings)
                    val metadata = RecordingMetadata.fromVideoFile(outputFile, config)
                    repository.saveMetadata(metadata)
                    Log.d(TAG, "Metadata created for ${outputFile.name}")

                    runOnUiThread {
                        playRecordingInline(metadata)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save metadata after extraction", e)
            }
        }

        cameraManager.onExtractionError = { error ->
            Log.e(TAG, "Extraction failed: $error")
            runOnUiThread {
                Toast.makeText(this, "Extraction failed: $error", Toast.LENGTH_SHORT).show()
            }
        }

        cameraManager.onRecordingStarted = {
            runOnUiThread {
                binding.statusText.text = getString(R.string.recording)
                binding.recordButton.isEnabled = false
                binding.recordingIndicator.visibility = View.VISIBLE
            }
        }

        cameraManager.onRecordingFinished = { videoFile ->
            runOnUiThread {
                binding.statusText.text = getString(R.string.ready)
                binding.recordButton.isEnabled = true
                binding.recordingIndicator.visibility = View.GONE

                // Save metadata
                val metadata = RecordingMetadata.fromVideoFile(videoFile, config)
                repository.saveMetadata(metadata)

                // Auto-play the recording in the same view
                playRecordingInline(metadata)
            }
        }

        cameraManager.onRecordingError = { error ->
            runOnUiThread {
                binding.statusText.text = getString(R.string.ready)
                binding.recordButton.isEnabled = true
                binding.recordingIndicator.visibility = View.GONE
                Toast.makeText(this, "Error: $error", Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch {
            try {
                // Ensure camera preview is visible before setup
                withContext(Dispatchers.Main) {
                    binding.cameraPreview.visibility = View.VISIBLE
                    binding.playerView.visibility = View.GONE
                    isShowingReplay = false

                    // Request layout to ensure PreviewView is measured
                    binding.cameraPreview.requestLayout()
                    Log.d(TAG, "Camera preview view made visible")
                }

                // Wait for PreviewView to be laid out
                withContext(Dispatchers.Main) {
                    binding.cameraPreview.post {
                        lifecycleScope.launch {
                            try {
                                cameraManager.setupCamera(config)
                                Log.d(TAG, "Camera setup completed successfully")

                                withContext(Dispatchers.Main) {
                                    binding.statusText.text = getString(R.string.ready)
                                    binding.recordButton.isEnabled = true

                                    // Check if there's a last recording to show
                                    val lastRecording = repository.getAllRecordings().firstOrNull()
                                    if (lastRecording != null) {
                                        Log.d(TAG, "Found last recording, playing inline")
                                        playRecordingInline(lastRecording)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Camera setup failed", e)
                                withContext(Dispatchers.Main) {
                                    binding.statusText.text = "Camera error"
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Camera setup failed: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.statusText.text = getString(R.string.ready)
                    binding.recordButton.isEnabled = true

                    // Check if there's a last recording to show
                    val lastRecording = repository.getAllRecordings().firstOrNull()
                    if (lastRecording != null) {
                        Log.d(TAG, "Found last recording, playing inline")
                        playRecordingInline(lastRecording)
                    } else {
                        Log.d(TAG, "No last recording, showing live camera preview")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Camera initialization failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showCameraPreview() {
        isShowingReplay = false
        player?.pause()
        binding.playerView.visibility = View.GONE
        binding.cameraPreview.visibility = View.VISIBLE
        binding.replayIndicator.visibility = View.GONE
        binding.recordButton.isEnabled = true
    }

    private fun playRecordingInline(metadata: RecordingMetadata) {
        isShowingReplay = true

        // Hide camera preview, show player
        binding.cameraPreview.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        binding.replayIndicator.visibility = View.VISIBLE

        // Release old player if exists
        player?.release()

        // Create new player
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer

            val videoFile = File(metadata.filePath)
            val videoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                videoFile
            )

            val mediaItem = MediaItem.fromUri(videoUri)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            exoPlayer.repeatMode = Player.REPEAT_MODE_ONE // Loop
        }
    }

    private fun startHttpServer() {
        val intent = Intent(this, HttpServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopHttpServer() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        stopService(Intent(this, HttpServerService::class.java))
    }

    private fun updateServerStatus() {
        // Get local IP address
        val ipAddress = getLocalIpAddress()
        binding.serverStatusText.text = getString(R.string.server_url, ipAddress)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                        return address.hostAddress ?: "unknown"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return "unknown"
    }

    private suspend fun startRecording() {
        if (cameraManager.isRecording) {
            Toast.makeText(this, "Already recording", Toast.LENGTH_SHORT).show()
            return
        }

        // Switch to camera view if currently showing replay
        withContext(Dispatchers.Main) {
            if (isShowingReplay) {
                showCameraPreview()
            }
        }

        val filename = RecordingMetadata.generateFilename()
        val outputFile = File(repository.recordingsDir, filename)

        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "Starting recording...", Toast.LENGTH_SHORT).show()
        }

        try {
            cameraManager.startRecording(outputFile, config)
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Recording failed: ${e.message}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createServerCallback(): HttpServerService.ServerCallback {
        return object : HttpServerService.ServerCallback {
            override suspend fun startRecording(): Map<String, Any> {
                if (cameraManager.isRecording) {
                    return mapOf(
                        "success" to false,
                        "message" to "Already recording"
                    )
                }

                try {
                    startRecording()
                    return mapOf(
                        "success" to true,
                        "message" to "Recording started",
                        "duration" to config.duration
                    )
                } catch (e: Exception) {
                    return mapOf(
                        "success" to false,
                        "error" to (e.message ?: "Unknown error")
                    )
                }
            }

            override fun getStatus(): Map<String, Any> {
                return mapOf(
                    "recording" to cameraManager.isRecording,
                    "config" to mapOf(
                        "duration" to config.duration
                    )
                )
            }

            override fun getRecordings(): List<Map<String, Any>> {
                return repository.getAllRecordings().map { metadata ->
                    val baseMap = mutableMapOf<String, Any>(
                        "filename" to metadata.filename,
                        "timestamp" to metadata.timestamp,
                        "duration" to metadata.duration,
                        "fileSize" to metadata.fileSize
                    )

                    // Include shotMetadata if present
                    metadata.shotMetadata?.let { shotMetadata ->
                        baseMap["shotMetadata"] = shotMetadata
                    }

                    baseMap
                }
            }

            override fun deleteRecording(filename: String): Boolean {
                return repository.deleteRecording(filename)
            }

            override fun deleteAllRecordings(): Boolean {
                return repository.deleteAllRecordings()
            }

            override fun getVideoFile(filename: String): File? {
                val file = repository.getVideoFile(filename)
                return if (file.exists()) file else null
            }

            override fun captureAndGetPreviewFrame(): ByteArray? {
                // Trigger a new frame capture
                cameraManager.capturePreviewFrame()
                // Return the cached frame (may be from previous capture)
                return cameraManager.getLatestPreviewFrame()
            }

            override suspend fun armLaunchMonitor(): Map<String, Any> {
                return cameraManager.armLaunchMonitor(repository.recordingsDir)
            }

            override suspend fun shotDetected(ballData: com.example.swingcam.data.BallData?): Map<String, Any> {
                val filename = RecordingMetadata.generateFilename()
                val outputFile = File(repository.recordingsDir, filename)

                try {
                    // Create and save metadata immediately with ball data (fileSize=0 placeholder)
                    val metadata = RecordingMetadata(
                        filename = filename,
                        timestamp = RecordingMetadata.generateTimestamp(),
                        duration = config.duration,
                        fileSize = 0, // Will be updated when extraction completes
                        filePath = outputFile.absolutePath,
                        shotMetadata = if (ballData != null) com.example.swingcam.data.ShotMetadata(ballData = ballData) else null
                    )
                    repository.saveMetadata(metadata)
                    Log.d(TAG, "Metadata saved immediately for ${filename}")

                    // shotDetected now returns immediately and extracts in background
                    // File size will be updated via onExtractionComplete callback
                    val result = cameraManager.shotDetected(outputFile, config.duration, config.postShotDelay)

                    if (result["status"] != "success") {
                        Log.e(TAG, "Shot detection failed: ${result["message"]}")
                        // Delete the metadata since video failed
                        repository.deleteRecording(filename)
                    }

                    return result
                } catch (e: Exception) {
                    Log.e(TAG, "Shot detection crashed", e)
                    return mapOf(
                        "status" to "error",
                        "message" to "Unexpected error: ${e.message}"
                    )
                }
            }

            override fun cancelLaunchMonitor(): Map<String, Any> {
                return cameraManager.cancelLaunchMonitor()
            }

            override fun getLMStatus(): Map<String, Any> {
                return cameraManager.getLMStatus()
            }

            override fun updateShotMetadata(filename: String, clubData: com.example.swingcam.data.ClubData?): Boolean {
                return try {
                    val metadata = repository.getRecording(filename)
                    if (metadata != null) {
                        val updatedShotMetadata = if (metadata.shotMetadata != null) {
                            metadata.shotMetadata.copy(clubData = clubData)
                        } else {
                            com.example.swingcam.data.ShotMetadata(clubData = clubData)
                        }
                        val updatedMetadata = metadata.copy(shotMetadata = updatedShotMetadata)
                        repository.saveMetadata(updatedMetadata)
                        Log.d(TAG, "Updated shot metadata for $filename with club data")
                        true
                    } else {
                        Log.e(TAG, "Recording not found: $filename")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update shot metadata", e)
                    false
                }
            }
        }
    }


    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()

        // Reload config in case it was changed in RecordingsActivity
        config = Config.load(filesDir)

        if (isShowingReplay) {
            player?.play()
        }
    }

    private fun cleanupTempFiles() {
        try {
            val tempFiles = repository.recordingsDir.listFiles()
                ?.filter { it.name.startsWith("temp_lm_") && it.extension == "mp4" }
                ?: emptyList()

            if (tempFiles.isNotEmpty()) {
                Log.w(TAG, "Found ${tempFiles.size} old temp file(s) to clean up:")
                tempFiles.forEach { file ->
                    val sizeMB = file.length() / (1024.0 * 1024.0)
                    Log.w(TAG, "  - Deleting ${file.name} (${String.format("%.1f", sizeMB)}MB)")
                    file.delete()
                }
                Log.i(TAG, "Temp files cleaned up successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean up temp files", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        if (serviceBound) {
            unbindService(serviceConnection)
        }
        if (::cameraManager.isInitialized) {
            cameraManager.cleanup()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}
