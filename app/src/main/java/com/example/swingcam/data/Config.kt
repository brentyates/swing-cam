package com.example.swingcam.data

import com.google.gson.Gson
import java.io.File

/**
 * Simple configuration for recording
 * Only duration is configurable - everything else uses Pixel's slow-motion mode
 */
data class Config(
    val duration: Int = 2500,  // Recording duration in milliseconds
    val postShotDelay: Int = 500  // Delay in milliseconds after shot detection before stopping recording
) {
    companion object {
        private const val CONFIG_FILENAME = "config.json"

        fun load(filesDir: File): Config {
            val configFile = File(filesDir, CONFIG_FILENAME)
            return if (configFile.exists()) {
                try {
                    Gson().fromJson(configFile.readText(), Config::class.java)
                } catch (e: Exception) {
                    Config() // Return default if parsing fails
                }
            } else {
                Config() // Return default if file doesn't exist
            }
        }

        fun save(filesDir: File, config: Config) {
            val configFile = File(filesDir, CONFIG_FILENAME)
            configFile.writeText(Gson().toJson(config))
        }
    }
}
