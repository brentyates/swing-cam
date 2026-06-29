package com.example.swingcam.data

import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Metadata for each recording
 * Simple tracking - camera settings handled by Pixel's slow-motion mode
 */
data class RecordingMetadata(
    val filename: String,
    val timestamp: String,
    val duration: Int,
    val fileSize: Long = 0,
    val filePath: String,
    val shotMetadata: ShotMetadata? = null  // Optional shot data from launch monitor
) {
    companion object {
        private const val TAG = "RecordingMetadata"
        private val gson = Gson()

        // SimpleDateFormat is not thread-safe, so instantiate per call rather than
        // sharing a companion-level instance across threads. Millisecond precision
        // avoids filename collisions when two recordings start within the same second.
        fun generateFilename(): String {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            return "swing_$stamp.mp4"
        }

        fun generateTimestamp(): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        }

        fun fromVideoFile(videoFile: File, config: Config): RecordingMetadata {
            return RecordingMetadata(
                filename = videoFile.name,
                timestamp = generateTimestamp(),
                duration = config.duration,
                fileSize = videoFile.length(),
                filePath = videoFile.absolutePath
            )
        }

        fun save(metadata: RecordingMetadata, metadataFile: File) {
            metadataFile.writeText(gson.toJson(metadata))
        }

        fun load(metadataFile: File): RecordingMetadata? {
            return try {
                gson.fromJson(metadataFile.readText(), RecordingMetadata::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load metadata from ${metadataFile.name}", e)
                null
            }
        }
    }
}
