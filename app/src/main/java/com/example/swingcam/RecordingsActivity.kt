package com.example.swingcam

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.swingcam.data.Config
import com.example.swingcam.data.RecordingMetadata
import com.example.swingcam.data.RecordingRepository
import com.example.swingcam.data.ShutterMode
import com.example.swingcam.databinding.ActivityRecordingsBinding
import com.example.swingcam.databinding.ItemRecordingBinding
import java.io.File

class RecordingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var repository: RecordingRepository
    private var config: Config = Config()
    private lateinit var adapter: RecordingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = RecordingRepository(this)
        config = Config.load(filesDir)

        setupSettings()
        setupRecordingsList()
        setupButtons()
    }

    private fun setupSettings() {
        binding.durationInput.setText(config.duration.toString())
        binding.postShotDelayInput.setText(config.postShotDelay.toString())

        binding.saveDurationButton.setOnClickListener {
            val durationText = binding.durationInput.text.toString()
            val duration = durationText.toIntOrNull()

            if (duration == null || duration < 1 || duration > 60) {
                Toast.makeText(this, "Duration must be between 1-60 seconds", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            config = config.copy(duration = duration)
            Config.save(filesDir, config)
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        binding.savePostShotDelayButton.setOnClickListener {
            val delayText = binding.postShotDelayInput.text.toString()
            val delay = delayText.toIntOrNull()

            if (delay == null || delay < 0 || delay > 2000) {
                Toast.makeText(this, "Delay must be between 0-2000 milliseconds", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            config = config.copy(postShotDelay = delay)
            Config.save(filesDir, config)
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        // Setup shutter mode spinner
        val shutterModes = ShutterMode.values()
        val shutterModeNames = shutterModes.map { mode ->
            when (mode) {
                ShutterMode.AUTO -> "AUTO - Default (may have blur)"
                ShutterMode.FAST_MOTION -> "FAST MOTION - 1/2000s (outdoor/bright)"
                ShutterMode.ULTRA_FAST -> "ULTRA FAST - 1/4000s (pro lighting)"
            }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, shutterModeNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.shutterModeSpinner.adapter = adapter

        // Set current selection
        val currentModeIndex = shutterModes.indexOf(config.shutterMode)
        binding.shutterModeSpinner.setSelection(currentModeIndex)
        updateShutterModeDescription(config.shutterMode)

        // Update description when selection changes
        binding.shutterModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateShutterModeDescription(shutterModes[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.saveShutterModeButton.setOnClickListener {
            val selectedMode = shutterModes[binding.shutterModeSpinner.selectedItemPosition]
            config = config.copy(shutterMode = selectedMode)
            Config.save(filesDir, config)
            Toast.makeText(this, "Shutter mode saved. Restart app to apply.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateShutterModeDescription(mode: ShutterMode) {
        val description = when (mode) {
            ShutterMode.AUTO -> "Camera controls exposure (~1/480s). Best for indoor/low-light. May have ~3-4 inches of motion blur on club head."
            ShutterMode.FAST_MOTION -> "Fixed 1/2000s shutter. Sharp frames (~0.85\" blur). Requires bright outdoor or well-lit indoor lighting. May be grainy in dim light."
            ShutterMode.ULTRA_FAST -> "Fixed 1/4000s shutter. Professional quality (~0.4\" blur). Requires very bright lighting (15,000+ lumens). Will be dark without proper lighting."
        }
        binding.shutterModeDescription.text = description
    }

    private fun setupRecordingsList() {
        adapter = RecordingsAdapter(
            onPlay = { recording -> playRecording(recording) },
            onDelete = { recording -> deleteRecording(recording) }
        )

        binding.recordingsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.recordingsRecyclerView.adapter = adapter

        loadRecordings()
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.deleteAllButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete All Recordings")
                .setMessage("Are you sure you want to delete all recordings? This cannot be undone.")
                .setPositiveButton("Delete All") { _, _ ->
                    if (repository.deleteAllRecordings()) {
                        Toast.makeText(this, "All recordings deleted", Toast.LENGTH_SHORT).show()
                        loadRecordings()
                    } else {
                        Toast.makeText(this, "Failed to delete recordings", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadRecordings() {
        val recordings = repository.getAllRecordings()
        adapter.submitList(recordings)

        if (recordings.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            binding.recordingsRecyclerView.visibility = View.GONE
            binding.deleteAllButton.isEnabled = false
        } else {
            binding.emptyText.visibility = View.GONE
            binding.recordingsRecyclerView.visibility = View.VISIBLE
            binding.deleteAllButton.isEnabled = true
        }
    }

    private fun playRecording(recording: RecordingMetadata) {
        try {
            val videoFile = File(recording.filePath)
            if (!videoFile.exists()) {
                Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show()
                return
            }

            val videoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                videoFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(videoUri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to play video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteRecording(recording: RecordingMetadata) {
        AlertDialog.Builder(this)
            .setTitle("Delete Recording")
            .setMessage("Delete recording from ${recording.timestamp}?")
            .setPositiveButton("Delete") { _, _ ->
                if (repository.deleteRecording(recording.filename)) {
                    Toast.makeText(this, "Recording deleted", Toast.LENGTH_SHORT).show()
                    loadRecordings()
                } else {
                    Toast.makeText(this, "Failed to delete recording", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Reload recordings in case any were added
        loadRecordings()
    }
}

class RecordingsAdapter(
    private val onPlay: (RecordingMetadata) -> Unit,
    private val onDelete: (RecordingMetadata) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    private var recordings = listOf<RecordingMetadata>()

    fun submitList(newRecordings: List<RecordingMetadata>) {
        recordings = newRecordings
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(recordings[position])
    }

    override fun getItemCount() = recordings.size

    inner class ViewHolder(private val binding: ItemRecordingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(recording: RecordingMetadata) {
            binding.timestampText.text = recording.timestamp
            binding.detailsText.text = "${recording.duration}s • ${formatFileSize(recording.fileSize)}"

            // Display shot metadata if available
            val shotMetadata = recording.shotMetadata
            if (shotMetadata != null && (shotMetadata.ballData != null || shotMetadata.clubData != null)) {
                val metadataLines = mutableListOf<String>()

                shotMetadata.ballData?.let { ball ->
                    val ballInfo = buildList {
                        ball.ballSpeed?.let { add("Ball: ${String.format("%.1f", it)} mph") }
                        ball.carryDistance?.let { add("Carry: ${String.format("%.0f", it)} yds") }
                        ball.launchAngle?.let { add("Launch: ${String.format("%.1f", it)}°") }
                        // Prefer showing backspin/sidespin over total spin
                        ball.backSpin?.let { add("Back: $it rpm") }
                        ball.sideSpin?.let { add("Side: $it rpm") }
                        // Fallback to total spin with axis if back/side not available
                        if (ball.backSpin == null && ball.sideSpin == null) {
                            ball.spinRate?.let {
                                if (ball.spinAxis != null) {
                                    add("Spin: $it rpm (${String.format("%.1f", ball.spinAxis)}°)")
                                } else {
                                    add("Spin: $it rpm")
                                }
                            }
                        }
                    }
                    if (ballInfo.isNotEmpty()) {
                        metadataLines.add(ballInfo.joinToString(" | "))
                    }
                }

                shotMetadata.clubData?.let { club ->
                    val clubInfo = buildList {
                        club.clubType?.let { add(it) }
                        club.clubSpeed?.let { add("${String.format("%.1f", it)} mph") }
                        club.smashFactor?.let { add("Smash: ${String.format("%.2f", it)}") }
                        club.clubPath?.let {
                            val pathDir = if (it >= 0) "I-O" else "O-I"
                            add("Path: ${String.format("%.1f", kotlin.math.abs(it))}° $pathDir")
                        }
                        club.faceAngle?.let {
                            val faceDir = if (it >= 0) "O" else "C"
                            add("Face: ${String.format("%.1f", kotlin.math.abs(it))}° $faceDir")
                        }
                        club.faceToPath?.let {
                            val ftpDir = if (it >= 0) "C" else "O"
                            add("F2P: ${String.format("%.1f", kotlin.math.abs(it))}° $ftpDir")
                        }
                        club.attackAngle?.let {
                            val attackDir = if (it >= 0) "U" else "D"
                            add("Attack: ${String.format("%.1f", kotlin.math.abs(it))}° $attackDir")
                        }
                        club.dynamicLoft?.let { add("Loft: ${String.format("%.1f", it)}°") }
                        club.lowPoint?.let { add("Low: ${String.format("%.1f", it)}\"") }
                    }
                    if (clubInfo.isNotEmpty()) {
                        metadataLines.add(clubInfo.joinToString(" | "))
                    }
                }

                if (metadataLines.isNotEmpty()) {
                    binding.shotMetadataText.text = metadataLines.joinToString("\n")
                    binding.shotMetadataText.visibility = View.VISIBLE
                } else {
                    binding.shotMetadataText.visibility = View.GONE
                }
            } else {
                binding.shotMetadataText.visibility = View.GONE
            }

            binding.playButton.setOnClickListener {
                onPlay(recording)
            }

            binding.deleteButton.setOnClickListener {
                onDelete(recording)
            }
        }

        private fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }
    }
}
