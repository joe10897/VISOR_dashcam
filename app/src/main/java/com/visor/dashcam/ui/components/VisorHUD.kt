package com.visor.dashcam.ui.components

import android.graphics.Paint
import android.view.ViewGroup
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.visor.dashcam.ai.adas.ADASAlert
import com.visor.dashcam.ai.adas.ADASAlertType
import com.visor.dashcam.ai.model.DetectionResult
import com.visor.dashcam.data.repository.AppLanguage
import com.visor.dashcam.data.repository.DashcamSettings
import com.visor.dashcam.data.repository.SpeedUnit
import com.visor.dashcam.recording.RecordingState
import com.visor.dashcam.sensors.TelemetryData
import com.visor.dashcam.sensors.ThermalState
import com.visor.dashcam.ui.i18n.VisorI18nStrings
import com.visor.dashcam.ui.i18n.VisorStrings
import com.visor.dashcam.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VisorHUD(
    telemetry: TelemetryData,
    recordingState: RecordingState,
    settings: DashcamSettings,
    thermalState: ThermalState = ThermalState(),
    detections: List<DetectionResult>,
    currentAlert: ADASAlert?,
    onAttachPreviewSurface: (Preview.SurfaceProvider?) -> Unit,
    onLockClicked: () -> Unit,
    onStopClicked: () -> Unit,
    onLibraryClicked: () -> Unit,
    onSettingsClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val str = remember(settings.language) { VisorStrings.get(settings.language) }
    var isAmoledSaverEnabled by remember { mutableStateOf(false) }
    var isPureHudMode by remember { mutableStateOf(false) }
    var showShutdownDialog by remember { mutableStateOf(false) }

    val displaySpeed = remember(telemetry.speedKmh, settings.speedUnit) {
        (telemetry.speedKmh * settings.speedUnit.factorFromKmh).toInt()
    }
    val speedUnitLabel = if (settings.speedUnit == SpeedUnit.MPH) "MPH" else "KM/H"

    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    var currentTimeString by remember { mutableStateOf(timeFormat.format(Date())) }

    LaunchedEffect(recordingState.currentSegmentDurationSeconds) {
        currentTimeString = timeFormat.format(Date())
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .clickable(enabled = isPureHudMode) {
                // Tapping anywhere in pure mode exits back to full HUD controls
                isPureHudMode = false
            }
    ) {
        if (!isAmoledSaverEnabled) {
            // 1. Live Camera Preview attached to Service's Preview UseCase (no unbindAll conflict!)
            CameraPreviewSurface(
                onAttachPreviewSurface = onAttachPreviewSurface,
                modifier = Modifier.fillMaxSize()
            )

            // 2. Bounding Box & ADAS Overlay Canvas
            if (settings.aiEnabled) {
                BoundingBoxCanvas(
                    detections = detections,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Real-Time Telemetry Watermark Preview (Mirroring the burned-in MP4 watermark)
            Text(
                text = String.format(
                    Locale.US,
                    "【VISOR AI DASHCAM】%s | GPS: %.4f, %.4f",
                    currentTimeString,
                    telemetry.latitude ?: 0.0,
                    telemetry.longitude ?: 0.0
                ),
                color = TextWhite.copy(alpha = 0.55f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 6.dp, end = 16.dp)
                    .zIndex(5f)
            )

            // Camera error or missing camera warning in emulator
            recordingState.cameraErrorMessage?.let { errorMsg ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.95f)),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideocamOff,
                            contentDescription = null,
                            tint = AlertRed,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = str.noCameraTitle,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMsg,
                            color = TextMuted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        } else {
            // Detach preview surface while in AMOLED saver mode to stop GPU rendering & save heat
            DisposableEffect(Unit) {
                onAttachPreviewSurface(null)
                onDispose { }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PureBlack)
                    .clickable { isAmoledSaverEnabled = false },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.BrightnessLow,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (settings.language == AppLanguage.EN) "AMOLED Screen Saver (Tap to wake)" else "AMOLED 防過熱節能模式 (點擊畫面喚醒)",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$displaySpeed $speedUnitLabel",
                        color = AccentCyan,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 3. Top Status Bar: Recording Status, Compass, Thermal, Pure Mode, Saver, Library, Settings & Power Off
        if (!isPureHudMode) {
            TopTelemetryBar(
                recordingState = recordingState,
                telemetry = telemetry,
                settings = settings,
                thermalState = thermalState,
                str = str,
                isSaverActive = isAmoledSaverEnabled,
                isPureHudActive = isPureHudMode,
                onToggleSaver = { isAmoledSaverEnabled = !isAmoledSaverEnabled },
                onTogglePureHud = { isPureHudMode = !isPureHudMode },
                onLibraryClicked = onLibraryClicked,
                onSettingsClicked = onSettingsClicked,
                onPowerOffClicked = { showShutdownDialog = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .zIndex(10f)
            )
        } else {
            // Pure HUD Mode: Minimal Top Indicator
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .zIndex(10f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(AlertRed)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (settings.language == AppLanguage.EN) "Pure HUD Mode (Tap to restore)" else "純淨車載模式 (點擊螢幕恢復)",
                    color = TextWhite.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 4. Center Flashing ADAS Warning Banner
        AnimatedVisibility(
            visible = currentAlert != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
        ) {
            currentAlert?.let { alert ->
                ADASAlertBanner(alert = alert)
            }
        }

        // 5. Bottom HUD Controls: Big Speedometer, G-Force Meter & Lock Button
        if (!isPureHudMode) {
            BottomHUDControls(
                displaySpeed = displaySpeed,
                speedUnitLabel = speedUnitLabel,
                telemetry = telemetry,
                recordingState = recordingState,
                str = str,
                onLockClicked = onLockClicked,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .zIndex(10f)
            )
        } else {
            // Pure HUD Mode: Floating Speedometer Only
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .zIndex(10f),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "$displaySpeed",
                    color = TextWhite.copy(alpha = 0.9f),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = speedUnitLabel,
                    color = AccentCyan.copy(alpha = 0.9f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
        }

        // 6. Safe Power Off / Exit Confirmation Dialog
        if (showShutdownDialog) {
            AlertDialog(
                onDismissRequest = { showShutdownDialog = false },
                containerColor = DarkSurface,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            tint = AlertRed,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = str.shutdownConfirmTitle,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                },
                text = {
                    Text(
                        text = str.shutdownConfirmMsg,
                        color = TextMuted,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showShutdownDialog = false
                            onStopClicked()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                    ) {
                        Text(str.confirmShutdown, color = TextWhite, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showShutdownDialog = false }) {
                        Text(str.cancel, color = TextMuted)
                    }
                }
            )
        }
    }
}

@Composable
fun CameraPreviewSurface(
    onAttachPreviewSurface: (Preview.SurfaceProvider?) -> Unit,
    modifier: Modifier = Modifier
) {
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                previewViewRef = this
                onAttachPreviewSurface(surfaceProvider)
            }
        },
        update = { view ->
            onAttachPreviewSurface(view.surfaceProvider)
        },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            onAttachPreviewSurface(null)
        }
    }
}

@Composable
fun BoundingBoxCanvas(
    detections: List<DetectionResult>,
    modifier: Modifier = Modifier
) {
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textSize = 32f
            isFakeBoldText = true
        }
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        for (det in detections) {
            val left = det.boundingBox.left * width
            val top = det.boundingBox.top * height
            val boxWidth = det.boundingBox.width() * width
            val boxHeight = det.boundingBox.height() * height

            val boxColor = when {
                det.ttcSeconds < 2.0f -> AlertRed
                det.isInEgoLane -> WarningAmber
                else -> AccentCyan
            }

            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(boxWidth, boxHeight),
                style = Stroke(width = if (det.isInEgoLane) 4.dp.toPx() else 2.dp.toPx())
            )

            drawLine(
                color = boxColor,
                start = Offset(left, top + boxHeight),
                end = Offset(left + boxWidth, top + boxHeight),
                strokeWidth = 6.dp.toPx()
            )

            val labelText = if (det.distanceMeters in 1f..99f) {
                String.format(Locale.US, "%s %.1fm", det.obstacleClass.label, det.distanceMeters)
            } else {
                det.obstacleClass.label
            }

            drawContext.canvas.nativeCanvas.apply {
                labelPaint.color = boxColor.toArgb()
                drawText(labelText, left + 6f, (top - 10f).coerceAtLeast(36f), labelPaint)
            }
        }
    }
}

@Composable
fun TopTelemetryBar(
    recordingState: RecordingState,
    telemetry: TelemetryData,
    settings: DashcamSettings,
    thermalState: ThermalState,
    str: VisorI18nStrings,
    isSaverActive: Boolean,
    isPureHudActive: Boolean,
    onToggleSaver: () -> Unit,
    onTogglePureHud: () -> Unit,
    onLibraryClicked: () -> Unit,
    onSettingsClicked: () -> Unit,
    onPowerOffClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(DarkSurface.copy(alpha = 0.90f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Red Recording Indicator + Segment Timer + Heading + Thermal
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )

            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(AlertRed.copy(alpha = if (recordingState.isRecording) alpha else 0.3f))
            )
            Spacer(modifier = Modifier.width(6.dp))
            val minutes = recordingState.currentSegmentDurationSeconds / 60
            val seconds = recordingState.currentSegmentDurationSeconds % 60
            val totalMin = settings.segmentDurationSeconds / 60
            Text(
                text = String.format(Locale.US, "%02d:%02d/%02d:00", minutes, seconds, totalMin),
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Navigation,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = "${telemetry.headingDegrees.toInt()}°",
                color = TextWhite,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (settings.aiEnabled) "AI" else "OFF",
                color = if (settings.aiEnabled) SafeGreen else TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            // Real-time Temperature & Thermal Throttling Badge
            if (thermalState.batteryTempCelsius > 0f) {
                Spacer(modifier = Modifier.width(8.dp))
                val tempColor = when {
                    thermalState.isCritical -> AlertRed
                    thermalState.isThrottled -> WarningAmber
                    else -> TextMuted
                }
                Text(
                    text = String.format(Locale.US, "🌡️%.1f°C", thermalState.batteryTempCelsius),
                    color = tempColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (thermalState.isThrottled) FontWeight.Bold else FontWeight.Normal
                )
                if (thermalState.isThrottled) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (thermalState.isCritical) "[暫停AI]" else "[降頻${thermalState.effectiveAiFps}FPS]",
                        color = tempColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Quick Actions: Pure HUD Mode, AMOLED Saver, Clip Library, Settings, Power Off
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onTogglePureHud,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = if (isPureHudActive) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = str.pureHud,
                    tint = if (isPureHudActive) AccentCyan else TextWhite,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(
                onClick = onToggleSaver,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = if (isSaverActive) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = str.amoledSaver,
                    tint = if (isSaverActive) WarningAmber else TextWhite,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(
                onClick = onLibraryClicked,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = str.clips,
                    tint = TextWhite,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(
                onClick = onSettingsClicked,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = str.settings,
                    tint = AccentCyan,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(
                onClick = onPowerOffClicked,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = str.powerOff,
                    tint = AlertRed,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun ADASAlertBanner(alert: ADASAlert) {
    val bgColor = when (alert.type) {
        ADASAlertType.FCW_CRITICAL, ADASAlertType.IMPACT_COLLISION -> AlertRed
        ADASAlertType.FCW_WARNING, ADASAlertType.HEADWAY_TOO_CLOSE -> WarningAmber
        ADASAlertType.LVDA_LEAD_DEPARTURE -> SafeGreen
        else -> DarkSurface
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (alert.type) {
                    ADASAlertType.FCW_CRITICAL -> Icons.Default.Warning
                    ADASAlertType.LVDA_LEAD_DEPARTURE -> Icons.Default.DirectionsCar
                    else -> Icons.Default.Info
                },
                contentDescription = null,
                tint = PureBlack,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = alert.type.titleRes,
                    color = PureBlack,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp
                )
                Text(
                    text = alert.message,
                    color = PureBlack.copy(alpha = 0.85f),
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun BottomHUDControls(
    displaySpeed: Int,
    speedUnitLabel: String,
    telemetry: TelemetryData,
    recordingState: RecordingState,
    str: VisorI18nStrings,
    onLockClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Digital Speedometer
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$displaySpeed",
                color = TextWhite,
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                Text(
                    text = speedUnitLabel,
                    color = AccentCyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${String.format(Locale.US, "%.2f", telemetry.gForceTotal)} G",
                    color = if (telemetry.gForceTotal > 1.8f) AlertRed else TextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // One-Tap Emergency Lock Button
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onLockClicked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (recordingState.isCurrentClipLocked) LockedGold else AlertRed.copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = if (recordingState.isCurrentClipLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = str.manualLock,
                    tint = PureBlack
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (recordingState.isCurrentClipLocked) str.lockedProtected else str.manualLock,
                    color = PureBlack,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
