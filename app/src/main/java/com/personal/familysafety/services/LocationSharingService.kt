package com.personal.familysafety.services

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.personal.familysafety.MainActivity
import com.personal.familysafety.firebase.FirebaseConfig
import com.personal.familysafety.models.DeviceLocation

class LocationSharingService : Service() {

    companion object {
        private const val TAG = "LocationSharingService"
        const val CHANNEL_ID = "family_safety_location_channel"
        const val NOTIFICATION_ID = 3001

        const val ACTION_START = "com.personal.familysafety.LOCATION_START"
        const val ACTION_STOP = "com.personal.familysafety.LOCATION_STOP"
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var deviceId: String = "child_device_001"
    private val database by lazy { FirebaseConfig.getDatabase() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        } catch (e: Throwable) {
            Log.e(TAG, "Error initializing LocationServices: ${e.message}", e)
        }
        Log.i(TAG, "LocationSharingService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val inputId = intent.getStringExtra(EXTRA_DEVICE_ID)
        if (!inputId.isNullOrEmpty()) {
            deviceId = inputId
        }

        when (action) {
            ACTION_START -> {
                val fineGranted = ActivityCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val coarseGranted = ActivityCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (!fineGranted && !coarseGranted) {
                    Log.e(TAG, "Cannot start location foreground service: Location permissions are not granted.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                val started = startForegroundServiceWithNotification()
                if (started) {
                    requestLocationUpdates()
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP -> {
                stopLocationUpdates()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification(): Boolean {
        return try {
            val notification = buildNotification("Real-time safety location updates active")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting location foreground service: ${e.message}", e)
            false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start location foreground service: ${e.message}", e)
            false
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LocationSharingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = android.R.drawable.sym_def_app_icon

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Safety: Location Active")
            .setContentText(text)
            .setSubText("GPS Sharing")
            .setSmallIcon(iconRes)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Sharing", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Family Safety Location",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows notifications when family safety location sharing is active."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permissions not granted when requesting updates.")
            stopSelf()
            return
        }

        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
                .setMinUpdateIntervalMillis(5000L)
                .setMinUpdateDistanceMeters(2.0f)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    val payload = DeviceLocation(
                        lat = loc.latitude,
                        lng = loc.longitude,
                        accuracy = loc.accuracy,
                        altitude = loc.altitude,
                        speed = loc.speed,
                        bearing = loc.bearing,
                        timestamp = loc.time
                    )
                    Log.d(TAG, "Location captured: ${payload.lat}, ${payload.lng} (±${payload.accuracy}m)")
                    publishLocationToFirebase(payload)
                }
            }

            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            Log.i(TAG, "FusedLocationProviderClient updates requested successfully.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException requesting location updates: ${e.message}", e)
        } catch (e: Throwable) {
            Log.e(TAG, "Error requesting location updates: ${e.message}", e)
        }
    }

    private fun publishLocationToFirebase(location: DeviceLocation) {
        try {
            database.getReference("devices")
                .child(deviceId)
                .child("location")
                .setValue(location)
                .addOnFailureListener {
                    Log.w(TAG, "Failed to upload location: ${it.message}")
                }
        } catch (e: Throwable) {
            Log.e(TAG, "Error publishing location to Firebase: ${e.message}", e)
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationCallback?.let {
                fusedLocationClient?.removeLocationUpdates(it)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error removing location updates: ${e.message}", e)
        }
        locationCallback = null
        Log.i(TAG, "Location updates stopped.")
    }

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
        Log.i(TAG, "LocationSharingService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}