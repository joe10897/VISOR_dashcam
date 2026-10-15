package com.visor.dashcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.visor.dashcam.recording.DashcamRecordingService
import com.visor.dashcam.recording.RecordingState
import com.visor.dashcam.sensors.TelemetryData
import com.visor.dashcam.ui.components.VisorHUD
import com.visor.dashcam.ui.screens.ClipLibraryScreen
import com.visor.dashcam.ui.theme.PureBlack
import com.visor.dashcam.ui.theme.TextWhite
import com.visor.dashcam.ui.theme.VisorTheme

class MainActivity : ComponentActivity() {

    private var recordingService: DashcamRecordingService? = null
    private var isBound by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as DashcamRecordingService.LocalBinder
            recordingService = localBinder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while dashcam is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            VisorTheme {
                MainScreen()
            }
        }
    }

    @Composable
    private fun MainScreen() {
        var hasPermissions by remember { mutableStateOf(checkAllPermissions()) }
        var currentScreen by remember { mutableStateOf("HUD") } // "HUD" or "LIBRARY"

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            hasPermissions = results.values.all { it }
            if (hasPermissions) {
                startAndBindRecordingService()
            }
        }

        LaunchedEffect(Unit) {
            if (!hasPermissions) {
                val perms = mutableListOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(perms.toTypedArray())
            } else {
                startAndBindRecordingService()
            }
        }

        if (!hasPermissions) {
            PermissionRequestScreen(
                onRequestPermissions = {
                    val perms = mutableListOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionLauncher.launch(perms.toTypedArray())
                }
            )
        } else {
            val service = recordingService
            val telemetry by service?.telemetryManager?.telemetry?.collectAsState()
                ?: remember { mutableStateOf(TelemetryData()) }
            val recordingState by service?.recordingState?.collectAsState()
                ?: remember { mutableStateOf(RecordingState()) }
            val detections by service?.latestDetections?.collectAsState()
                ?: remember { mutableStateOf(emptyList()) }
            val currentAlert by service?.adasManager?.currentAlert?.collectAsState()
                ?: remember { mutableStateOf(null) }

            if (currentScreen == "HUD") {
                VisorHUD(
                    telemetry = telemetry,
                    recordingState = recordingState,
                    detections = detections,
                    currentAlert = currentAlert,
                    onLockClicked = {
                        service?.lockCurrentRecording("MANUAL_TOUCH_LOCK")
                    },
                    onStopClicked = {
                        stopRecordingService()
                    },
                    onLibraryClicked = {
                        currentScreen = "LIBRARY"
                    }
                )
            } else {
                ClipLibraryScreen(
                    onBackClicked = {
                        currentScreen = "HUD"
                    }
                )
            }
        }
    }

    @Composable
    private fun PermissionRequestScreen(onRequestPermissions: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "VISOR 離線 AI 行車記錄器",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextWhite
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "為實現完全離線循環錄影與前車防撞預警，需要授予相機、麥克風與 GPS 權限。\n本 App 100% 離線運行，絕不上傳任何數據。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onRequestPermissions) {
                    Text("授權並啟動")
                }
            }
        }
    }

    private fun checkAllPermissions(): Boolean {
        val required = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startAndBindRecordingService() {
        val intent = Intent(this, DashcamRecordingService::class.java).apply {
            action = DashcamRecordingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopRecordingService() {
        val intent = Intent(this, DashcamRecordingService::class.java).apply {
            action = DashcamRecordingService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
