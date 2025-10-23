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
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
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

        // Launch Monitor API
        suspend fun armLaunchMonitor(): Map<String, Any>
        suspend fun shotDetected(): Map<String, Any>
        fun cancelLaunchMonitor(): Map<String, Any>
        fun getLMStatus(): Map<String, Any>
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
                                call.respond(mapOf("recordings" to callback.getRecordings()))
                            } else {
                                call.respond(HttpStatusCode.ServiceUnavailable,
                                    mapOf("error" to "Service not ready"))
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
                                    val result = callback.shotDetected()
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

                        // Health check endpoint
                        get("/") {
                            call.respondText("SwingCam HTTP Server Running",
                                ContentType.Text.Plain)
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
