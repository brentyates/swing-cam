package com.example.swingcam.data

import android.content.Context
import java.io.File

/**
 * Repository for managing recordings and their metadata
 */
class RecordingRepository(private val context: Context) {

    val recordingsDir: File by lazy {
        File(context.filesDir, "recordings").apply {
            if (!exists()) mkdirs()
        }
    }

    fun getAllRecordings(): List<RecordingMetadata> {
        return recordingsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { RecordingMetadata.load(it) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    fun getRecording(filename: String): RecordingMetadata? {
        val metadataFile = File(recordingsDir, "${filename.removeSuffix(".mp4")}.json")
        return if (metadataFile.exists()) {
            RecordingMetadata.load(metadataFile)
        } else {
            null
        }
    }

    fun deleteRecording(filename: String): Boolean {
        val videoFile = File(recordingsDir, filename)
        val metadataFile = File(recordingsDir, "${filename.removeSuffix(".mp4")}.json")

        var success = true
        if (videoFile.exists()) {
            success = success && videoFile.delete()
        }
        if (metadataFile.exists()) {
            success = success && metadataFile.delete()
        }

        return success
    }

    fun deleteAllRecordings(): Boolean {
        val recordings = getAllRecordings()
        var allDeleted = true

        recordings.forEach { metadata ->
            if (!deleteRecording(metadata.filename)) {
                allDeleted = false
            }
        }

        return allDeleted
    }

    fun saveMetadata(metadata: RecordingMetadata) {
        val metadataFile = File(recordingsDir, "${metadata.filename.removeSuffix(".mp4")}.json")
        RecordingMetadata.save(metadata, metadataFile)
    }

    fun getVideoFile(filename: String): File {
        return File(recordingsDir, filename)
    }

    /**
     * Find video files that have no corresponding metadata
     */
    fun getOrphanedVideos(): List<File> {
        val videoFiles = recordingsDir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?: emptyList()

        val metadataFiles = recordingsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()

        return videoFiles.filter { video ->
            video.nameWithoutExtension !in metadataFiles
        }
    }
}
