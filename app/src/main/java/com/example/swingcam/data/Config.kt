package com.example.swingcam.data

import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * Simple configuration for recording
 * Only duration is configurable - everything else uses Pixel's slow-motion mode
 */
data class Config(
    val duration: Int = 2,  // Recording duration in seconds
    val postShotDelay: Int = 500  // Delay in milliseconds after shot detection before stopping recording
) {
    companion object {
        private const val TAG = "Config"
        private const val CONFIG_FILENAME = "config.json"
        private val gson = Gson()

        fun load(filesDir: File): Config {
            val configFile = File(filesDir, CONFIG_FILENAME)
            return if (configFile.exists()) {
                try {
                    gson.fromJson(configFile.readText(), Config::class.java) ?: Config()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse $CONFIG_FILENAME, using defaults", e)
                    Config() // Return default if parsing fails
                }
            } else {
                Config() // Return default if file doesn't exist
            }
        }

        fun save(filesDir: File, config: Config) {
            val configFile = File(filesDir, CONFIG_FILENAME)
            configFile.writeText(gson.toJson(config))
        }
    }
}
