package com.personal.familysafety

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.personal.familysafety.firebase.DeviceStateReporter
import com.personal.familysafety.services.AssistanceMediaService
import com.personal.familysafety.services.LocationSharingService
import com.personal.familysafety.websocket.WebSocketStreamManager

class MainActivity : ComponentActivity() {

    private var screenCaptureLauncherRef: (() -> Unit)? = null

    private val screenPromptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.personal.familysafety.PROMPT_SCREEN_SHARE") {
                Toast.makeText(context, "Parent requested screen mirroring. Please confirm.", Toast.LENGTH_LONG).show()
                screenCaptureLauncherRef?.invoke()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 14 (API 34) compliant dynamic receiver registration
        val filter = IntentFilter("com.personal.familysafety.PROMPT_SCREEN_SHARE")
        ContextCompat.registerReceiver(
            this,
            screenPromptReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF38BDF8),
                    onPrimary = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B),
                    background = Color(0xFF0F172A),
                    onSurface = Color(0xFFF1F5F9),
                    onBackground = Color(0xFFF1F5F9)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FamilySafetyDashboard(onRegisterLauncher = { launcher ->
                        screenCaptureLauncherRef = launcher
                    })
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenPromptReceiver)
        } catch (ignored: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilySafetyDashboard(onRegisterLauncher: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("family_safety_prefs", Context.MODE_PRIVATE) }
    var deviceId by remember {
        mutableStateOf(sharedPrefs.getString("device_id", "child_device_001") ?: "child_device_001")
    }

    var wsUrl by remember {
        mutableStateOf(sharedPrefs.getString("ws_url", "wss://ais-dev-vfzytoext2ntavlnmspwe3-938514856930.asia-southeast1.run.app/ws") ?: "wss://ais-dev-vfzytoext2ntavlnmspwe3-938514856930.asia-southeast1.run.app/ws")
    }

    var isWsConnected by remember { mutableStateOf(false) }
    var isScreenStreaming by remember { mutableStateOf(false) }
    var isCameraStreaming by remember { mutableStateOf(false) }

    val wsManager = remember { WebSocketStreamManager.getInstance() }

    DisposableEffect(Unit) {
        val listener = object : WebSocketStreamManager.WebSocketListener {
            override fun onConnected() { isWsConnected = true }
            override fun onDisconnected(reason: String) { isWsConnected = false }
            override fun onCommandReceived(action: String, payload: com.google.gson.JsonObject?) {}
        }
        wsManager.addListener(listener)
        onDispose { wsManager.removeListener(listener) }
    }

    LaunchedEffect(wsUrl, deviceId) {
        if (wsUrl.isNotBlank()) {
            wsManager.connect(wsUrl, deviceId)
        }
    }

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    var permissionsGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            Toast.makeText(context, "Permissions granted!", Toast.LENGTH_SHORT).show()
        }
    }

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, AssistanceMediaService::class.java).apply {
                action = AssistanceMediaService.ACTION_START_SCREEN
                putExtra(AssistanceMediaService.EXTRA_DEVICE_ID, deviceId)
                putExtra(AssistanceMediaService.EXTRA_WS_URL, wsUrl)
                putExtra(AssistanceMediaService.EXTRA_PROJECTION_DATA, result.data)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            isScreenStreaming = true
            isCameraStreaming = false
        }
    }

    LaunchedEffect(Unit) {
        onRegisterLauncher {
            try {
                val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                screenCaptureLauncher.launch(captureIntent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error launching screen capture: ${e.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Family Safety Client",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Option 1: 4G/5G-Ready WebSocket Stream",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E293B)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Relay Connection", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color(0xFFF1F5F9))
                        StatusBadge(isGood = isWsConnected, text = if (isWsConnected) "Connected" else "Offline")
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = deviceId,
                        onValueChange = {
                            deviceId = it.trim()
                            sharedPrefs.edit().putString("device_id", deviceId).apply()
                        },
                        label = { Text("Device ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = wsUrl,
                        onValueChange = {
                            wsUrl = it.trim()
                            sharedPrefs.edit().putString("ws_url", wsUrl).apply()
                        },
                        label = { Text("WebSocket Relay URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = { wsManager.connect(wsUrl, deviceId) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Connect / Reconnect Relay")
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Instant Streaming Actions", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color(0xFFF1F5F9))
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (!permissionsGranted) {
                                permissionLauncher.launch(requiredPermissions)
                                return@Button
                            }
                            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                            screenCaptureLauncher.launch(captureIntent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ScreenShare, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Screen Mirror")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val intent = Intent(context, AssistanceMediaService::class.java).apply {
                                    action = AssistanceMediaService.ACTION_START_CAMERA
                                    putExtra(AssistanceMediaService.EXTRA_DEVICE_ID, deviceId)
                                    putExtra(AssistanceMediaService.EXTRA_WS_URL, wsUrl)
                                    putExtra(AssistanceMediaService.EXTRA_CAMERA_FACING_FRONT, true)
                                }
                                ContextCompat.startForegroundService(context, intent)
                                isCameraStreaming = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Front Cam")
                        }

                        Button(
                            onClick = {
                                val intent = Intent(context, AssistanceMediaService::class.java).apply {
                                    action = AssistanceMediaService.ACTION_START_CAMERA
                                    putExtra(AssistanceMediaService.EXTRA_DEVICE_ID, deviceId)
                                    putExtra(AssistanceMediaService.EXTRA_WS_URL, wsUrl)
                                    putExtra(AssistanceMediaService.EXTRA_CAMERA_FACING_FRONT, false)
                                }
                                ContextCompat.startForegroundService(context, intent)
                                isCameraStreaming = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Rear Cam")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(context, AssistanceMediaService::class.java).apply {
                                action = AssistanceMediaService.ACTION_STOP_SERVICE
                            }
                            context.startService(intent)
                            isScreenStreaming = false
                            isCameraStreaming = false
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171))
                    ) {
                        Text("Stop Streaming Service")
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(isGood: Boolean, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isGood) Color(0xFF064E3B) else Color(0xFF451A03))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isGood) Color(0xFF34D399) else Color(0xFFFBBF24))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (isGood) Color(0xFF6EE7B7) else Color(0xFFFDE68A)
        )
    }
}