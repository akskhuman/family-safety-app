package com.personal.familysafety.firebase

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log

class DeviceStateReporter(
    private val context: Context,
    private val deviceId: String
) {
    companion object {
        private const val TAG = "DeviceStateReporter"
    }

    private val database = FirebaseConfig.getDatabase()
    private val deviceRef = database.getReference("devices").child(deviceId)

    fun updateStatus(status: String, onResult: ((Boolean, String?) -> Unit)? = null) {
        val (battery, isCharging) = getBatteryInfo()
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

        val updates = mapOf(
            "status" to status,
            "battery" to battery,
            "isCharging" to isCharging,
            "lastPing" to System.currentTimeMillis(),
            "deviceName" to deviceName
        )

        deviceRef.updateChildren(updates)
            .addOnSuccessListener {
                Log.i(TAG, "Successfully synced device state to Realtime DB: $status ($deviceId)")
                onResult?.invoke(true, null)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Failed to write to Firebase Realtime Database: ${error.message}", error)
                onResult?.invoke(false, error.message)
            }
    }

    fun setOffline() {
        val updates = mapOf(
            "status" to "offline",
            "lastPing" to System.currentTimeMillis()
        )
        deviceRef.updateChildren(updates)
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 100

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }
}