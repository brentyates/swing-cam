package com.example.swingcam.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.swingcam.MainActivity
import com.example.swingcam.R
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.net.NetworkInterface

/**
 * Foreground service running the HTTP API server
 * Similar to the Flask web_interface.py in golf-cam
 */
class HttpServerService : Service() {

    private var server: NettyApplicationEngine? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var serverCallback: ServerCallback? = null

    interface ServerCallback {
        suspend fun startRecording(): Map<String, Any>
        fun getStatus(): Map<String, Any>
        fun getRecordings(): List<Map<String, Any>>
        fun deleteRecording(filename: String): Boolean
        fun deleteAllRecordings(): Boolean
        fun getVideoFile(filename: String): File?
        fun captureAndGetPreviewFrame(): ByteArray?

        // Launch Monitor API
        suspend fun armLaunchMonitor(): Map<String, Any>
        suspend fun shotDetected(ballData: com.example.swingcam.data.BallData? = null): Map<String, Any>
        fun cancelLaunchMonitor(): Map<String, Any>
        fun getLMStatus(): Map<String, Any>
        fun updateShotMetadata(filename: String, clubData: com.example.swingcam.data.ClubData? = null): Boolean
    }

    inner class LocalBinder : Binder() {
        fun getService(): HttpServerService = this@HttpServerService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        startServer()

        return START_STICKY
    }

    private fun startServer() {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return
        }

        serviceScope.launch {
            try {
                server = embeddedServer(Netty, port = PORT) {
                    install(ContentNegotiation) {
                        gson {}
                    }

                    routing {
                        // Static web interface files
                        get("/") {
                            val html = assets.open("web/index.html").bufferedReader().use { it.readText() }
                            call.respondText(html, ContentType.Text.Html)
                        }

                        get("/styles.css") {
                            val css = assets.open("web/styles.css").bufferedReader().use { it.readText() }
                            call.respondText(css, ContentType.Text.CSS)
                        }

                        get("/app.js") {
                            val js = assets.open("web/app.js").bufferedReader().use { it.readText() }
                            call.respondText(js, ContentType.Application.JavaScript)
                        }

                        // API endpoints similar to golf-cam Flask routes

                        get("/api/status") {
                            val callback = serverCallback
                            if (callback != null) {
                                call.respond(callback.getStatus())
                            } else {
                                call.respond(HttpStatusCode.ServiceUnavailable,
                                    mapOf("error" to "Service not ready"))
                            }
                        }

                        post("/api/record") {
                            val callback = serverCallback
                            if (callback != null) {
                                try {
                                    val result = callback.startRecording()
                                    call.respond(result)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Recording failed", e)
                                    call.respond(HttpStatusCode.InternalServerError,
                                        mapOf("error" to e.message))
                                }
                            } else {
                                call.respond(HttpStatusCode.ServiceUnavailable,
                                    mapOf("error" to "Service not ready"))
                            }
                        }

                        get("/api/recordings") {
                            val callback = serverCallback
                            if (callback != null) {
                                // Return array directly (not wrapped in "recordings" key)
                                call.respond(callback.getRecordings())
                            } else {
                                call.respond(HttpStatusCode.ServiceUnavailable,
                                    mapOf("error" to "Service not ready"))
                            }
                        }

                        // Video streaming endpoint with HTTP range request support
                        get("/api/recordings/{filename}/stream") {
                            val callback = serverCallback
                            val filename = call.parameters["filename"]

                            if (callback == null || filename == null) {
                                call.respond(HttpStatusCode.BadRequest,
                                    mapOf("error" to "Invalid request"))
                                return@get
                            }

                            val videoFile = callback.getVideoFile(filename)
                            if (videoFile == null || !videoFile.exists()) {
                                call.respond(HttpStatusCode.NotFound,
                                    mapOf("error" to "Video not found"))
                                return@get
                            }

                            try {
                                val fileLength = videoFile.length()
                                val rangeHeader = call.request.header("Range")

                                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                                    // Handle range request for seeking/partial content
                                    val range = rangeHeader.substring(6).split("-")
                                    val start = range[0].toLongOrNull() ?: 0
                                    val end = if (range.size > 1 && range[1].isNotEmpty()) {
                                        range[1].toLongOrNull() ?: (fileLength - 1)
                                    } else {
                                        fileLength - 1
                                    }

                                    val contentLength = end - start + 1

                                    call.response.status(HttpStatusCode.PartialContent)
                                    call.response.header("Content-Type", "video/mp4")
                                    call.response.header("Accept-Ranges", "bytes")
                                    call.response.header("Content-Range", "bytes $start-$end/$fileLength")
                                    call.response.header("Content-Length", contentLength.toString())

                                    // Stream the requested byte range
                                    RandomAccessFile(videoFile, "r").use { raf ->
                                        raf.seek(start)
                                        val buffer = ByteArray(8192)
                                        var remaining = contentLength

                                        call.respondOutputStream(ContentType.Video.MP4) {
                                            while (remaining > 0) {
                                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                                val bytesRead = raf.read(buffer, 0, toRead)
                                                if (bytesRead == -1) break
                                                write(buffer, 0, bytesRead)
                                                remaining -= bytesRead
                                            }
                                        }
                                    }
                                } else {
                                    // Full file request
                                    call.response.header("Content-Type", "video/mp4")
                                    call.response.header("Accept-Ranges", "bytes")
                                    call.response.header("Content-Length", fileLength.toString())
                                    call.respondFile(videoFile)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error streaming video", e)
                                call.respond(HttpStatusCode.InternalServerError,
                                    mapOf("error" to "Failed to stream video"))
                            }
                        }

                        delete("/api/recordings/{filename}") {
                            val callback = serverCallback
                            val filename = call.parameters["filename"]

                            if (callback != null && filename != null) {
                                val success = callback.deleteRecording(filename)
                                if (success) {
                                    call.respond(mapOf("success" to true))
                                } else {
                                    call.respond(HttpStatusCode.NotFound,
                                        mapOf("error" to "Recording not found"))
                                }
                            } else {
                                call.respond(HttpStatusCode.BadRequest,
                                    mapOf("error" to "Invalid request"))
                            }
                        }

                        delete("/api/recordings") {
                            val callback = serverCallback
                            if (callback != null) {
                                callback.deleteAllRecordings()
                                call.respond(mapOf("success" to true))
                            } else {
                                call.respond(HttpStatusCode.ServiceUnavailable,
                                    mapOf("error" to "Service not ready"))
                            }
                        }

                        // Launch Monitor API endpoints
                        post("/api/lm/arm") {
                            Log.i(TAG, "API: POST /api/lm/arm")
                            val callback = serverCallback
                            if (callback != null) {
                                try {
                                    val result = callback.armLaunchMonitor()
                                    Log.i(TAG, "API: arm result: ${result["status"]}")
                                    call.respond(result)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Arm failed", e)
                                    call.respond(HttpStatusCode.InternalServerError,
                                        mapOf("status" to "error", "message" to e.message))
                                }
                            } else {
                                call.respond(HttpStatusCode.ServiceUnavailable,
                                    mapOf("status" to "error", "message" to "Service not ready"))
                            }
                        }

                        post("/api/lm/shot-detected") {
                            Log.i(TAG, "API: POST /api/lm/shot-detected")
                            val callback = serverCallback
                            if (callback != null) {
                                try {
                                    // Parse optional ball data from request body
                                    val ballData = try {
                                        call.receive<com.example.swingcam.data.BallData>()
                                    } catch (e: Exception) {
                                        Log.d(TAG, "No ball data in request body or parse error, continuing without it")
                                        null
                                    }

                                    if (ballData != null) {
                                        Log.i(TAG, "API: Received ball data - speed: ${ballData.ballSpeed}, launch: ${ballData.launchAngle}")
                                    }

                                    val result = callback.shotDetected(ballData)
                                    Log.i(TAG, "API: shot-detected result: ${result["status"]} - ${result["message"] ?: result["filename"]}")
                                    call.respond(result)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Shot detection failed", e)
                                    call.respond(HttpStatusCode.InternalServerError,
                                        mapOf("status" to "error", "message" to e.message))
                                }
                            } else {
                                call.respond(HttpStatusCode.ServiceUnavailable,
                                    mapOf("status" to "error", "message" to "Service not ready"))
                            }
                        }

                        post("/api/lm/cancel") {
                            val callback = serverCallback
                            if (callback != null) {
                                try {
                                    val result = callback.cancelLaunchMonitor()
                                    call.respond(result)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Cancel failed", e)
                                    call.respond(HttpStatusCode.InternalServerError,
                                        mapOf("status" to "error", "message" to e.message))
                                }
                            } else {
                                call.respond(HttpStatusCode.ServiceUnavailable,
                                    mapOf("status" to "error", "message" to "Service not ready"))
                            }
                        }

                        get("/api/lm/status") {
                            val callback = serverCallback
                            if (callback != null) {
                                call.respond(callback.getLMStatus())
                            } else {
                                call.respond(HttpStatusCode.ServiceUnavailable,
                                    mapOf("status" to "error", "message" to "Service not ready"))
                            }
                        }

                        // Live camera preview endpoint (Phase 4)
                        get("/api/camera/preview") {
                            val callback = serverCallback
                            if (callback == null) {
                                call.respond(HttpStatusCode.ServiceUnavailable,
                                    mapOf("error" to "Service not ready"))
                                return@get
                            }

                            try {
                                val jpegBytes = callback.captureAndGetPreviewFrame()
                                if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                                    call.response.header("Content-Type", "image/jpeg")
                                    call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")
                                    call.response.header("Pragma", "no-cache")
                                    call.response.header("Expires", "0")
                                    call.respondBytes(jpegBytes, ContentType.Image.JPEG)
                                } else {
                                    // No frame available yet, return placeholder or error
                                    call.respond(HttpStatusCode.NoContent)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error capturing preview frame", e)
                                call.respond(HttpStatusCode.InternalServerError,
                                    mapOf("error" to "Failed to capture preview"))
                            }
                        }

                        // Update shot metadata (typically club data sent after ball data)
                        patch("/api/recordings/{filename}/metadata") {
                            val callback = serverCallback
                            val filename = call.parameters["filename"]

                            if (callback == null || filename == null) {
                                call.respond(HttpStatusCode.BadRequest,
                                    mapOf("error" to "Invalid request"))
                                return@patch
                            }

                            try {
                                // Parse club data from request body
                                val clubData = try {
                                    call.receive<com.example.swingcam.data.ClubData>()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse club data", e)
                                    null
                                }

                                if (clubData != null) {
                                    Log.i(TAG, "API: Updating metadata for $filename with club data - speed: ${clubData.clubSpeed}, club: ${clubData.clubType}")
                                    val success = callback.updateShotMetadata(filename, clubData)
                                    if (success) {
                                        call.respond(mapOf("success" to true, "message" to "Metadata updated"))
                                    } else {
                                        call.respond(HttpStatusCode.NotFound,
                                            mapOf("error" to "Recording not found"))
                                    }
                                } else {
                                    call.respond(HttpStatusCode.BadRequest,
                                        mapOf("error" to "Invalid or missing club data"))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error updating metadata", e)
                                call.respond(HttpStatusCode.InternalServerError,
                                    mapOf("error" to "Failed to update metadata"))
                            }
                        }
                    }
                }.start(wait = false)

                val ipAddress = getLocalIpAddress()
                Log.i(TAG, "Server started on http://$ipAddress:$PORT")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
            }
        }
    }

    private fun stopServer() {
        server?.stop(1000, 2000)
        server = null
        Log.i(TAG, "Server stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SwingCam Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HTTP API Server for remote recording control"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val ipAddress = getLocalIpAddress()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SwingCam Server Running")
            .setContentText("API: http://$ipAddress:$PORT")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
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

    companion object {
        private const val TAG = "HttpServerService"
        private const val CHANNEL_ID = "swingcam_server_channel"
        private const val NOTIFICATION_ID = 1
        const val PORT = 8080
    }
}
