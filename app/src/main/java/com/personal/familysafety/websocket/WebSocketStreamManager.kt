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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
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
        currentDeviceId = deviceId.trim().ifEmpty { "child_device_001" }
        shouldReconnect = true
        reconnectAttempts = 0

        // Disconnect existing before reconnecting
        try {
            webSocket?.close(1000, "Reconnecting")
        } catch (_: Exception) {}
        webSocket = null
        isConnected.set(false)
        isConnecting.set(false)

        startConnection()
    }

    private fun buildCleanWsUrl(inputUrl: String, deviceId: String): String {
        var url = inputUrl.trim()

        // Protocol normalize
        if (url.startsWith("http://")) {
            url = "ws://" + url.substring(7)
        } else if (url.startsWith("https://")) {
            url = "wss://" + url.substring(8)
        } else if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "wss://$url"
        }

        // Strip existing queries
        val baseWithoutQuery = if (url.contains("?")) url.substringBefore("?") else url
        val cleanBase = baseWithoutQuery.trimEnd('/')

        // Ensure /ws route
        val routeUrl = if (!cleanBase.endsWith("/ws")) "$cleanBase/ws" else cleanBase

        return "$routeUrl?deviceId=$deviceId&role=streamer"
    }

    private fun startConnection() {
        if (isConnecting.get() || isConnected.get()) return

        val fullUrl = buildCleanWsUrl(currentServerUrl, currentDeviceId)
        isConnecting.set(true)
        Log.i(TAG, "Initiating WebSocket connection to: $fullUrl")

        try {
            val origin = if (fullUrl.startsWith("wss://")) "https://localhost" else "http://localhost"
            val request = Request.Builder()
                .url(fullUrl)
                .header("Origin", origin)
                .header("User-Agent", "FamilySafetyAndroid/1.0 (Linux; Android 14)")
                .build()

            webSocket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.i(TAG, "🟢 Connected to WebSocket Relay! Code: ${response.code}")
                    isConnecting.set(false)
                    isConnected.set(true)
                    reconnectAttempts = 0

                    // Send handshake registration payload
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
                    Log.w(TAG, "Server closing WebSocket: code=$code, reason=$reason")
                    ws.close(1000, null)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "WebSocket Closed: code=$code, reason=$reason")
                    handleDisconnection(reason)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    val codeMsg = if (response != null) " HTTP ${response.code}: ${response.message}" else ""
                    val errorDetail = "${t.javaClass.simpleName}: ${t.message}$codeMsg"
                    Log.e(TAG, "❌ WebSocket Failure: $errorDetail", t)
                    handleDisconnection(errorDetail)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket request: ${e.message}", e)
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
                    Log.i(TAG, "Received command: $action")
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
                "registered" -> {
                    Log.i(TAG, "Streamer confirmed registered on relay.")
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
            Log.d(TAG, "Scheduling reconnection attempt #$reconnectAttempts in ${delayMs}ms")
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
        try {
            webSocket?.close(1000, "User stopped stream manager")
        } catch (_: Exception) {}
        webSocket = null
        isConnected.set(false)
        isConnecting.set(false)
    }
}