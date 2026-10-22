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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.visor.dashcam.ai.adas.ADASAlert
import com.visor.dashcam.ai.model.DetectionResult
import com.visor.dashcam.recording.DashcamRecordingService
import com.visor.dashcam.recording.RecordingState
import com.visor.dashcam.sensors.TelemetryData
import com.visor.dashcam.sensors.ThermalState
import com.visor.dashcam.ui.components.VisorHUD
import com.visor.dashcam.ui.screens.ClipLibraryScreen
import com.visor.dashcam.ui.screens.SettingsScreen
import com.visor.dashcam.ui.theme.PureBlack
import com.visor.dashcam.ui.theme.TextWhite
import com.visor.dashcam.ui.theme.VisorTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private var recordingService by mutableStateOf<DashcamRecordingService?>(null)
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? DashcamRecordingService.LocalBinder
            recordingService = localBinder?.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Configure Immersive Sticky Fullscreen mode for permanent landscape dashcam mount
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        // Keep screen on while dashcam is in foreground
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            VisorTheme {
                MainScreen()
            }
        }
    }

    @Composable
    private fun MainScreen() {
        val app = application as VisorApp
        val settingsRepository = app.settingsRepository
        val settings by settingsRepository.settings.collectAsState()

        var hasCameraPermission by remember { mutableStateOf(hasCameraPermissionGranted()) }
        var currentScreen by remember { mutableStateOf("HUD") } // "HUD", "LIBRARY", "SETTINGS"

        BackHandler(enabled = currentScreen != "HUD") {
            currentScreen = "HUD"
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) {
            hasCameraPermission = hasCameraPermissionGranted()
            if (hasCameraPermission) {
                startAndBindRecordingService()
            }
        }

        LaunchedEffect(hasCameraPermission) {
            if (!hasCameraPermission) {
                permissionLauncher.launch(getPermissionsToRequest())
            } else {
                startAndBindRecordingService()
            }
        }

        if (!hasCameraPermission) {
            PermissionRequestScreen(
                onRequestPermissions = {
                    permissionLauncher.launch(getPermissionsToRequest())
                }
            )
        } else {
            val service = recordingService

            // Unconditional StateFlow collection to keep Compose slot table 100% stable
            val telemetryFlow = remember(service) {
                service?.telemetryManager?.telemetry ?: MutableStateFlow(TelemetryData())
            }
            val recordingStateFlow = remember(service) {
                service?.recordingState ?: MutableStateFlow(RecordingState())
            }
            val detectionsFlow = remember(service) {
                service?.latestDetections ?: MutableStateFlow(emptyList<DetectionResult>())
            }
            val alertFlow = remember(service) {
                service?.adasManager?.currentAlert ?: MutableStateFlow<ADASAlert?>(null)
            }
            val thermalFlow = remember(service) {
                service?.thermalManager?.thermalState ?: MutableStateFlow(ThermalState())
            }

            val telemetry by telemetryFlow.collectAsState()
            val recordingState by recordingStateFlow.collectAsState()
            val detections by detectionsFlow.collectAsState()
            val currentAlert by alertFlow.collectAsState()
            val thermalState by thermalFlow.collectAsState()

            when (currentScreen) {
                "HUD" -> {
                    VisorHUD(
                        telemetry = telemetry,
                        recordingState = recordingState,
                        settings = settings,
                        thermalState = thermalState,
                        detections = detections,
                        currentAlert = currentAlert,
                        onAttachPreviewSurface = { surfaceProvider ->
                            service?.attachPreviewSurface(surfaceProvider)
                        },
                        onLockClicked = {
                            service?.lockCurrentRecording("MANUAL_TOUCH_LOCK")
                        },
                        onStopClicked = {
                            try {
                                service?.stopDashcam()
                            } catch (_: Exception) {}
                            stopRecordingService()
                            finishAffinity()
                        },
                        onLibraryClicked = {
                            currentScreen = "LIBRARY"
                        },
                        onSettingsClicked = {
                            currentScreen = "SETTINGS"
                        }
                    )
                }

                "LIBRARY" -> {
                    ClipLibraryScreen(
                        onBackClicked = {
                            currentScreen = "HUD"
                        }
                    )
                }

                "SETTINGS" -> {
                    SettingsScreen(
                        settingsRepository = settingsRepository,
                        onClearUnlockedClips = {
                            service?.storageManager?.clearAllUnlockedClips() ?: 0
                        },
                        onEnforceQuotaNow = {
                            service?.storageManager?.checkAndPurgeOldestClips()
                        },
                        onVideoQualityChanged = {
                            service?.restartCameraWithNewSettings()
                        },
                        onBackClicked = {
                            currentScreen = "HUD"
                        }
                    )
                }
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
                    text = "為實現完全離線循環錄影與前車防撞預警，需要授予相機權限（以及選配的麥克風與 GPS 權限）。\n本 App 100% 離線運行，絕不上傳任何數據。",
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

    private fun hasCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getPermissionsToRequest(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }

    private fun startAndBindRecordingService() {
        try {
            val intent = Intent(this, DashcamRecordingService::class.java).apply {
                action = DashcamRecordingService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRecordingService() {
        try {
            if (isBound) {
                unbindService(serviceConnection)
                isBound = false
            }
            val intent = Intent(this, DashcamRecordingService::class.java).apply {
                action = DashcamRecordingService.ACTION_STOP
            }
            startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {
            }
            isBound = false
        }
        super.onDestroy()
    }
}
