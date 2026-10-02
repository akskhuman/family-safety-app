package com.personal.familysafety.models

import androidx.annotation.Keep
import com.google.firebase.database.IgnoreExtraProperties
import com.google.gson.annotations.SerializedName

@Keep
enum class StreamAction {
    @SerializedName("IDLE")
    IDLE,

    @SerializedName("START_SCREEN")
    START_SCREEN,

    @SerializedName("START_CAMERA_FRONT")
    START_CAMERA_FRONT,

    @SerializedName("START_CAMERA_BACK")
    START_CAMERA_BACK,

    @SerializedName("STOP_STREAM")
    STOP_STREAM
}

@Keep
@IgnoreExtraProperties
data class RemoteCommand(
    @SerializedName("action")
    val action: String = StreamAction.IDLE.name,

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @SerializedName("requestedBy")
    val requestedBy: String = "ParentDashboard"
)

@Keep
@IgnoreExtraProperties
data class SdpPayload(
    @SerializedName("type")
    val type: String = "", // "offer" or "answer"

    @SerializedName("sdp")
    val sdp: String = "",

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)

@Keep
@IgnoreExtraProperties
data class IceCandidatePayload(
    @SerializedName("sdpMid")
    val sdpMid: String = "",

    @SerializedName("sdpMLineIndex")
    val sdpMLineIndex: Int = 0,

    @SerializedName("candidate")
    val candidate: String = "",

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)

@Keep
@IgnoreExtraProperties
data class SessionPayload(
    @SerializedName("offer")
    val offer: SdpPayload? = null,

    @SerializedName("answer")
    val answer: SdpPayload? = null,

    @SerializedName("childCandidates")
    val childCandidates: Map<String, IceCandidatePayload>? = null,

    @SerializedName("parentCandidates")
    val parentCandidates: Map<String, IceCandidatePayload>? = null
)

@Keep
@IgnoreExtraProperties
data class DeviceLocation(
    @SerializedName("lat")
    val lat: Double = 0.0,

    @SerializedName("lng")
    val lng: Double = 0.0,

    @SerializedName("accuracy")
    val accuracy: Float = 0.0f,

    @SerializedName("altitude")
    val altitude: Double = 0.0,

    @SerializedName("speed")
    val speed: Float = 0.0f,

    @SerializedName("bearing")
    val bearing: Float = 0.0f,

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)

@Keep
@IgnoreExtraProperties
data class DeviceStatus(
    @SerializedName("status")
    val status: String = "online",

    @SerializedName("battery")
    val battery: Int = 100,

    @SerializedName("isCharging")
    val isCharging: Boolean = false,

    @SerializedName("lastPing")
    val lastPing: Long = System.currentTimeMillis(),

    @SerializedName("deviceName")
    val deviceName: String = "Android Device",

    @SerializedName("location")
    val location: DeviceLocation? = null,

    @SerializedName("command")
    val command: RemoteCommand? = null
)