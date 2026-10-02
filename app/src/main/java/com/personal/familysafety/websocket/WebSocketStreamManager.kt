package com.personal.familysafety.websocket

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketStreamManager private constructor() {

    companion object {
        private const val TAG = "WebSocketStreamManager"

        @Volatile
        private var instance: WebSocketStreamManager? = null

        fun getInstance(): WebSocketStreamManager {
            return instance ?: synchronized(this) {
                instance ?: WebSocketStreamManager().also { instance = it }
            }
        }
    }

    interface WebSocketListener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onCommandReceived(action: String, payload: JsonObject?)
    }

    private var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentServerUrl: String = ""
    private var currentDeviceId: String = "child_device_001"
    private val isConnecting = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private var shouldReconnect = true
    private var reconnectAttempts = 0

    private val listeners = mutableSetOf<WebSocketListener>()

    fun addListener(listener: WebSocketListener) {
        synchronized(listeners) {
            listeners.add(listener)
            if (isConnected.get()) {
                mainHandler.post { listener.onConnected() }
            }
        }
    }

    fun removeListener(listener: WebSocketListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun isConnected(): Boolean = isConnected.get()

    fun connect(serverUrl: String, deviceId: String) {
        currentServerUrl = serverUrl.trim()
        currentDeviceId = deviceId.trim()
        shouldReconnect = true
        reconnectAttempts = 0

        startConnection()
    }

    private fun startConnection() {
        if (isConnecting.get() || isConnected.get()) return

        var wsUrl = currentServerUrl
        if (wsUrl.startsWith("http://")) {
            wsUrl = "ws://" + wsUrl.substring(7)
        } else if (wsUrl.startsWith("https://")) {
            wsUrl = "wss://" + wsUrl.substring(8)
        } else if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
            wsUrl = "wss://$wsUrl"
        }

        if (!wsUrl.contains("/ws")) {
            wsUrl = wsUrl.trimEnd('/') + "/ws"
        }
        val fullUrl = if (wsUrl.contains("?")) {
            "$wsUrl&deviceId=$currentDeviceId&role=streamer"
        } else {
            "$wsUrl?deviceId=$currentDeviceId&role=streamer"
        }

        isConnecting.set(true)
        Log.i(TAG, "Connecting to WebSocket Media Relay: $fullUrl")

        try {
            val request = Request.Builder()
                .url(fullUrl)
                .build()

            webSocket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.i(TAG, "Connected to WebSocket Media Relay successfully!")
                    isConnecting.set(false)
                    isConnected.set(true)
                    reconnectAttempts = 0

                    val regMsg = JsonObject().apply {
                        addProperty("type", "register")
                        addProperty("role", "streamer")
                        addProperty("deviceId", currentDeviceId)
                    }
                    ws.send(gson.toJson(regMsg))

                    synchronized(listeners) {
                        listeners.forEach { listener ->
                            mainHandler.post { listener.onConnected() }
                        }
                    }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleTextMessage(text)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(1000, null)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    handleDisconnection(reason)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    handleDisconnection(t.message ?: "Connection error")
                }
            })
        } catch (e: Exception) {
            handleDisconnection(e.message ?: "Invalid URL")
        }
    }

    private fun handleTextMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString

            when (type) {
                "command" -> {
                    val action = json.get("action")?.asString ?: return
                    val payload = json.getAsJsonObject("payload")
                    synchronized(listeners) {
                        listeners.forEach { listener ->
                            mainHandler.post { listener.onCommandReceived(action, payload) }
                        }
                    }
                }
                "ping" -> {
                    val ts = json.get("timestamp")?.asLong ?: System.currentTimeMillis()
                    val pong = JsonObject().apply {
                        addProperty("type", "pong")
                        addProperty("timestamp", ts)
                    }
                    webSocket?.send(gson.toJson(pong))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming WS message: ${e.message}")
        }
    }

    private fun handleDisconnection(reason: String) {
        isConnecting.set(false)
        isConnected.set(false)
        webSocket = null

        synchronized(listeners) {
            listeners.forEach { listener ->
                mainHandler.post { listener.onDisconnected(reason) }
            }
        }

        if (shouldReconnect) {
            reconnectAttempts++
            val delayMs = (Math.min(reconnectAttempts * 2000, 15000)).toLong()
            mainHandler.postDelayed({
                if (shouldReconnect && !isConnected.get()) {
                    startConnection()
                }
            }, delayMs)
        }
    }

    fun sendBinaryFrame(frameBytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected.get()) return false
        return try {
            ws.send(frameBytes.toByteString())
        } catch (e: Exception) {
            false
        }
    }

    fun sendStatus(streamState: String, resolution: String? = null, fps: Int? = null) {
        val ws = webSocket ?: return
        if (!isConnected.get()) return
        val msg = JsonObject().apply {
            addProperty("type", "status")
            addProperty("deviceId", currentDeviceId)
            addProperty("streamState", streamState)
            resolution?.let { addProperty("resolution", it) }
            fps?.let { addProperty("fps", it) }
            addProperty("timestamp", System.currentTimeMillis())
        }
        ws.send(gson.toJson(msg))
    }

    fun sendTelemetry(battery: Int, isCharging: Boolean, lat: Double? = null, lng: Double? = null) {
        val ws = webSocket ?: return
        if (!isConnected.get()) return
        val msg = JsonObject().apply {
            addProperty("type", "telemetry")
            addProperty("deviceId", currentDeviceId)
            addProperty("battery", battery)
            addProperty("isCharging", isCharging)
            lat?.let { addProperty("lat", it) }
            lng?.let { addProperty("lng", it) }
            addProperty("timestamp", System.currentTimeMillis())
        }
        ws.send(gson.toJson(msg))
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "User stopped stream manager")
        webSocket = null
        isConnected.set(false)
        isConnecting.set(false)
    }
}