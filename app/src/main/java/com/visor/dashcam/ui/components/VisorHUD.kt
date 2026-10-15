package com.visor.dashcam.ui.components

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.visor.dashcam.ai.adas.ADASAlert
import com.visor.dashcam.ai.adas.ADASAlertType
import com.visor.dashcam.ai.model.DetectionResult
import com.visor.dashcam.recording.RecordingState
import com.visor.dashcam.sensors.TelemetryData
import com.visor.dashcam.ui.theme.*

@Composable
fun VisorHUD(
    telemetry: TelemetryData,
    recordingState: RecordingState,
    detections: List<DetectionResult>,
    currentAlert: ADASAlert?,
    onLockClicked: () -> Unit,
    onStopClicked: () -> Unit,
    onLibraryClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isAmoledSaverEnabled by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
    ) {
        if (!isAmoledSaverEnabled) {
            // 1. Live Camera Preview (when saver is off)
            CameraPreviewBackground(modifier = Modifier.fillMaxSize())

            // 2. Bounding Box & ADAS Overlay Canvas
            BoundingBoxCanvas(
                detections = detections,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // AMOLED Heat & Battery Saver Screen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PureBlack)
                    .clickable { isAmoledSaverEnabled = false },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.BrightnessMedium,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "AMOLED 防過熱節能模式 (點擊喚醒畫面)",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${telemetry.speedKmh.toInt()} KM/H",
                        color = AccentCyan,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 3. Top Status Bar: Recording Status, Clock, GPS Compass
        TopTelemetryBar(
            recordingState = recordingState,
            telemetry = telemetry,
            isSaverActive = isAmoledSaverEnabled,
            onToggleSaver = { isAmoledSaverEnabled = !isAmoledSaverEnabled },
            onLibraryClicked = onLibraryClicked,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp)
        )

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
        BottomHUDControls(
            telemetry = telemetry,
            recordingState = recordingState,
            onLockClicked = onLockClicked,
            onStopClicked = onStopClicked,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        )
    }
}

@Composable
fun CameraPreviewBackground(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE

                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(surfaceProvider)
                    }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        },
        modifier = modifier
    )
}

@Composable
fun BoundingBoxCanvas(
    detections: List<DetectionResult>,
    modifier: Modifier = Modifier
) {
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

            // Draw Bounding Box
            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(boxWidth, boxHeight),
                style = Stroke(width = if (det.isInEgoLane) 4.dp.toPx() else 2.dp.toPx())
            )

            // Draw Wheel contact indicator
            drawLine(
                color = boxColor,
                start = Offset(left, top + boxHeight),
                end = Offset(left + boxWidth, top + boxHeight),
                strokeWidth = 6.dp.toPx()
            )
        }
    }
}

@Composable
fun TopTelemetryBar(
    recordingState: RecordingState,
    telemetry: TelemetryData,
    isSaverActive: Boolean,
    onToggleSaver: () -> Unit,
    onLibraryClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(DarkSurface.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Red Recording Indicator
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(AlertRed.copy(alpha = if (recordingState.isRecording) alpha else 0.3f))
            )
            Spacer(modifier = Modifier.width(8.dp))
            val minutes = recordingState.currentSegmentDurationSeconds / 60
            val seconds = recordingState.currentSegmentDurationSeconds % 60
            Text(
                text = String.format("%02d:%02d", minutes, seconds),
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
        }

        // Compass Heading & Altitude
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Navigation,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${telemetry.headingDegrees.toInt()}°",
                color = TextWhite,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Quick Actions
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleSaver, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isSaverActive) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = "節能息屏",
                    tint = if (isSaverActive) WarningAmber else TextWhite
                )
            }
            IconButton(onClick = onLibraryClicked, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = "影片庫",
                    tint = TextWhite
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
    telemetry: TelemetryData,
    recordingState: RecordingState,
    onLockClicked: () -> Unit,
    onStopClicked: () -> Unit,
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
                text = "${telemetry.speedKmh.toInt()}",
                color = TextWhite,
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                Text(
                    text = "KM/H",
                    color = AccentCyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${String.format("%.2f", telemetry.gForceTotal)} G",
                    color = if (telemetry.gForceTotal > 1.8f) AlertRed else TextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Action Buttons
        Row(verticalAlignment = Alignment.CenterVertically) {
            // One-Tap Emergency Lock Button
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
                    contentDescription = "鎖定片段",
                    tint = PureBlack
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (recordingState.isCurrentClipLocked) "已鎖定" else "手動鎖檔",
                    color = PureBlack,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
