package com.personal.familysafety.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.JsonObject
import com.personal.familysafety.MainActivity
import com.personal.familysafety.firebase.DeviceStateReporter
import com.personal.familysafety.websocket.CameraStreamer
import com.personal.familysafety.websocket.ScreenStreamer
import com.personal.familysafety.websocket.WebSocketStreamManager

class AssistanceMediaService : Service(), WebSocketStreamManager.WebSocketListener {

    companion object {
        private const val TAG = "AssistanceMediaService"
        const val CHANNEL_ID = "family_safety_media_stream_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START_SCREEN = "com.personal.familysafety.START_SCREEN"
        const val ACTION_START_CAMERA = "com.personal.familysafety.START_CAMERA"
        const val ACTION_STOP_SERVICE = "com.personal.familysafety.STOP_SERVICE"
        const val ACTION_SWITCH_CAMERA = "com.personal.familysafety.SWITCH_CAMERA"
        const val ACTION_CONNECT_WS = "com.personal.familysafety.CONNECT_WS"

        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_WS_URL = "extra_ws_url"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val EXTRA_CAMERA_FACING_FRONT = "extra_camera_facing_front"
    }

    private var stateReporter: DeviceStateReporter? = null
    private var wsManager: WebSocketStreamManager = WebSocketStreamManager.getInstance()
    private var screenStreamer: ScreenStreamer? = null
    private var cameraStreamer: CameraStreamer? = null
    private var activeMediaProjection: MediaProjection? = null

    private var deviceId: String = "child_device_001"
    private var isFrontCamera: Boolean = true

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wsManager.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        wsManager.removeListener(this)
        stopStream()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val inputDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        if (!inputDeviceId.isNullOrEmpty()) {
            deviceId = inputDeviceId
        }

        val wsUrl = intent.getStringExtra(EXTRA_WS_URL)
        if (!wsUrl.isNullOrEmpty()) {
            wsManager.connect(wsUrl, deviceId)
        }

        if (stateReporter == null) {
            stateReporter = DeviceStateReporter(this, deviceId)
        }

        when (action) {
            ACTION_CONNECT_WS -> {
                if (!wsUrl.isNullOrEmpty()) {
                    wsManager.connect(wsUrl, deviceId)
                }
            }

            ACTION_START_SCREEN -> {
                try {
                    val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                    }
                    if (projectionData != null) {
                        val fgsStarted = startForegroundWithTypes(
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                            "Screen Sharing Active"
                        )
                        if (fgsStarted) {
                            startWebSocketScreenStream(projectionData)
                        } else {
                            stopSelf()
                        }
                    } else {
                        stateReporter?.updateStatus("error_no_projection_data")
                        stopSelf()
                    }
                } catch (e: Throwable) {
                    stateReporter?.updateStatus("error_start_screen")
                    stopSelf()
                }
            }

            ACTION_START_CAMERA -> {
                isFrontCamera = intent.getBooleanExtra(EXTRA_CAMERA_FACING_FRONT, true)
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForegroundWithTypes(types, if (isFrontCamera) "Front Camera Active" else "Rear Camera Active")
                startWebSocketCameraStream(isFrontCamera)
            }

            ACTION_SWITCH_CAMERA -> {
                isFrontCamera = !isFrontCamera
                cameraStreamer?.stop()
                startWebSocketCameraStream(isFrontCamera)
            }

            ACTION_STOP_SERVICE -> {
                stopStream()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startWebSocketScreenStream(projectionData: Intent) {
        stopStream()
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpManager.getMediaProjection(Activity.RESULT_OK, projectionData)
            activeMediaProjection = projection

            val metrics = resources.displayMetrics
            val width = 720
            val height = 1280
            val dpi = metrics.densityDpi

            val streamer = ScreenStreamer(
                mediaProjection = projection,
                width = width,
                height = height,
                densityDpi = dpi,
                targetFps = 18,
                jpegQuality = 65
            )
            screenStreamer = streamer
            streamer.start()

            wsManager.sendStatus("screen", "${width}x${height}", 18)
            stateReporter?.updateStatus("streaming_screen")
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun startWebSocketCameraStream(frontFacing: Boolean) {
        stopStream()
        try {
            val streamer = CameraStreamer(
                context = this,
                isFrontCamera = frontFacing,
                targetWidth = 640,
                targetHeight = 480,
                targetFps = 20
            )
            cameraStreamer = streamer
            streamer.start()

            val camType = if (frontFacing) "camera_front" else "camera_back"
            wsManager.sendStatus(camType, "640x480", 20)
            stateReporter?.updateStatus("streaming_camera")
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun stopStream() {
        screenStreamer?.stop()
        screenStreamer = null

        cameraStreamer?.stop()
        cameraStreamer = null

        activeMediaProjection?.stop()
        activeMediaProjection = null

        wsManager.sendStatus("idle")
        stateReporter?.updateStatus("idle")
    }

    override fun onConnected() {}
    override fun onDisconnected(reason: String) {}

    override fun onCommandReceived(action: String, payload: JsonObject?) {
        when (action) {
            "START_CAMERA_FRONT" -> {
                val intent = Intent(this, AssistanceMediaService::class.java).apply {
                    this.action = ACTION_START_CAMERA
                    putExtra(EXTRA_CAMERA_FACING_FRONT, true)
                    putExtra(EXTRA_DEVICE_ID, deviceId)
                }
                startService(intent)
            }
            "START_CAMERA_BACK" -> {
                val intent = Intent(this, AssistanceMediaService::class.java).apply {
                    this.action = ACTION_START_CAMERA
                    putExtra(EXTRA_CAMERA_FACING_FRONT, false)
                    putExtra(EXTRA_DEVICE_ID, deviceId)
                }
                startService(intent)
            }
            "STOP_STREAM" -> {
                stopStream()
            }
            "START_SCREEN" -> {
                // Explicit broadcast targeted only to this app package to satisfy Android 14 Lint
                val broadcastIntent = Intent("com.personal.familysafety.PROMPT_SCREEN_SHARE").apply {
                    setPackage(packageName)
                }
                sendBroadcast(broadcastIntent)
            }
        }
    }

    private fun startForegroundWithTypes(serviceType: Int, message: String): Boolean {
        return try {
            val notification = buildNotification(message)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun buildNotification(message: String): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AssistanceMediaService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Safety Assistance Active")
            .setContentText(message)
            .setSubText("Transparent Stream (Option 1 WebSocket)")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Stream", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Family Safety Stream Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when real-time family safety screen or camera stream is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}