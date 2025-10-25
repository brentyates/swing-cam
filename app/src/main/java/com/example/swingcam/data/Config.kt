package com.example.swingcam.data

import com.google.gson.Gson
import java.io.File

/**
 * Shutter speed modes for different lighting conditions and motion blur requirements
 */
enum class ShutterMode {
    /**
     * AUTO - Let camera decide exposure (default)
     * Best for: Indoor/low-light, when motion blur is acceptable
     * Shutter: ~1/480s @ 240fps (following 180° rule)
     * Pros: Good brightness, clean image, works in most lighting
     * Cons: 3-4 inches of motion blur on club head
     */
    AUTO,

    /**
     * FAST_MOTION - 1/2000s for sharp golf swings
     * Best for: Outdoor/well-lit indoor, analyzing swing mechanics
     * Shutter: 1/2000s fixed
     * Pros: ~0.85 inches motion blur, sharp club/ball
     * Cons: Requires bright light, higher ISO (some grain)
     */
    FAST_MOTION,

    /**
     * ULTRA_FAST - 1/4000s for tournament-grade capture
     * Best for: Pro setups with 15,000+ lumens lighting
     * Shutter: 1/4000s fixed
     * Pros: ~0.4 inches motion blur, magazine-quality sharpness
     * Cons: Requires very bright light, high ISO (grainy in dim light)
     */
    ULTRA_FAST
}

/**
 * Configuration for recording with high-FPS slow-motion capabilities
 */
data class Config(
    val duration: Int = 2,  // Recording duration in seconds
    val postShotDelay: Int = 500,  // Delay in milliseconds after shot detection before stopping recording
    val shutterMode: ShutterMode = ShutterMode.AUTO  // Shutter speed mode for motion blur control
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
