package com.example.swingcam.data

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
    val filePath: String
) {
    companion object {
        private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        fun generateFilename(): String {
            return "swing_${dateFormat.format(Date())}.mp4"
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
            metadataFile.writeText(Gson().toJson(metadata))
        }

        fun load(metadataFile: File): RecordingMetadata? {
            return try {
                Gson().fromJson(metadataFile.readText(), RecordingMetadata::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}
