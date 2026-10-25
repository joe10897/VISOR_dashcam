package com.visor.dashcam.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.visor.dashcam.VisorApp
import com.visor.dashcam.data.entity.ClipEntity
import com.visor.dashcam.data.repository.AppLanguage
import com.visor.dashcam.recording.BlackboxRecorder
import com.visor.dashcam.recording.BlackboxReport
import com.visor.dashcam.ui.i18n.VisorI18nStrings
import com.visor.dashcam.ui.i18n.VisorStrings
import com.visor.dashcam.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipLibraryScreen(
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val app = context.applicationContext as VisorApp
    val clipDao = app.database.clipDao()
    val settings by app.settingsRepository.settings.collectAsState()
    val str = remember(settings.language) { VisorStrings.get(settings.language) }

    var showLockedOnly by remember { mutableStateOf(false) }
    var selectedBlackboxReport by remember { mutableStateOf<BlackboxReport?>(null) }
    val clipsFlow = remember(showLockedOnly) {
        if (showLockedOnly) clipDao.getLockedClipsFlow() else clipDao.getAllClipsFlow()
    }
    val clips by clipsFlow.collectAsState(initial = emptyList())

    selectedBlackboxReport?.let { report ->
        BlackboxReportDialog(
            report = report,
            language = settings.language,
            str = str,
            onDismiss = { selectedBlackboxReport = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str.libraryTitle, color = TextWhite, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBackClicked) {
                        Icon(Icons.Default.ArrowBack, contentDescription = str.back, tint = TextWhite)
                    }
                },
                actions = {
                    FilterChip(
                        selected = showLockedOnly,
                        onClick = { showLockedOnly = !showLockedOnly },
                        label = { Text(if (showLockedOnly) str.filterLocked else str.filterAll) },
                        leadingIcon = {
                            if (showLockedOnly) Icon(Icons.Default.Lock, contentDescription = null, tint = LockedGold)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CardBackground,
                            selectedLabelColor = TextWhite
                        )
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = PureBlack,
        modifier = modifier
    ) { padding ->
        if (clips.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (showLockedOnly) {
                        if (settings.language == AppLanguage.EN) "No protected clips" else "尚無鎖定保護之片段"
                    } else {
                        str.noClipsFound
                    },
                    color = TextMuted,
                    fontSize = 15.sp
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(clips, key = { it.id }) { clip ->
                    ClipItemCard(
                        clip = clip,
                        language = settings.language,
                        str = str,
                        onPlayClicked = { playVideo(context, clip.filePath) },
                        onToggleLock = {
                            coroutineScope.launch(Dispatchers.IO) {
                                clipDao.setLockStatus(
                                    clipId = clip.id,
                                    isLocked = !clip.isLocked,
                                    reason = if (!clip.isLocked) "USER_TOGGLE_LOCK" else null
                                )
                            }
                        },
                        onDeleteClicked = {
                            coroutineScope.launch(Dispatchers.IO) {
                                val file = File(clip.filePath)
                                if (file.exists()) file.delete()
                                val jsonFile = File(file.parentFile, "${file.nameWithoutExtension}_blackbox.json")
                                if (jsonFile.exists()) jsonFile.delete()
                                clipDao.delete(clip)
                            }
                        },
                        onShowBlackbox = { report ->
                            selectedBlackboxReport = report
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ClipItemCard(
    clip: ClipEntity,
    language: AppLanguage,
    str: VisorI18nStrings,
    onPlayClicked: () -> Unit,
    onToggleLock: () -> Unit,
    onDeleteClicked: () -> Unit,
    onShowBlackbox: (BlackboxReport) -> Unit
) {
    val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
        .format(Date(clip.startTimeMillis))

    val blackboxReport = remember(clip.filePath) {
        BlackboxRecorder.loadReportForVideo(File(clip.filePath))
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlayClicked() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (clip.isLocked) Icons.Default.Lock else Icons.Default.VideoFile,
                        contentDescription = null,
                        tint = if (clip.isLocked) LockedGold else AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = clip.fileName,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                // File size in MB
                val sizeMb = clip.fileSizeBytes / (1024f * 1024f)
                Text(
                    text = "${String.format("%.1f", sizeMb)} MB",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = dateStr, color = TextMuted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (language == AppLanguage.EN) {
                                "Max: ${clip.maxSpeedKmh.toInt()} km/h | Length: ${clip.durationSeconds}s"
                            } else {
                                "最高車速: ${clip.maxSpeedKmh.toInt()} km/h | 時長: ${clip.durationSeconds}s"
                            },
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (language == AppLanguage.EN) "Watermark✓" else "MP4硬烙印✓",
                            color = SafeGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (clip.lockReason != null) {
                        Text(
                            text = if (language == AppLanguage.EN) "Reason: ${clip.lockReason}" else "鎖定原因: ${clip.lockReason}",
                            color = WarningAmber,
                            fontSize = 11.sp
                        )
                    }
                    if (blackboxReport != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { onShowBlackbox(blackboxReport) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.6f)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.QueryStats, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(str.blackboxAnalysisButton, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Row {
                    IconButton(onClick = onToggleLock) {
                        Icon(
                            imageVector = if (clip.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (clip.isLocked) str.unlock else str.lock,
                            tint = if (clip.isLocked) LockedGold else TextMuted
                        )
                    }
                    if (!clip.isLocked) {
                        IconButton(onClick = onDeleteClicked) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = str.delete,
                                tint = AlertRed.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun playVideo(context: Context, filePath: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@Composable
fun BlackboxReportDialog(
    report: BlackboxReport,
    language: AppLanguage,
    str: VisorI18nStrings,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(str.blackboxReportTitle, color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "${report.videoFileName} | ${report.eventType}",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Scenario Banner Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = str.blackboxScenarioTitle,
                            color = WarningAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (language == AppLanguage.EN) report.scenarioEn else report.scenarioZh,
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Key Speed & Impact Grid
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricBox(
                        title = str.blackboxEgoSpeed,
                        value = "${report.egoSpeedAtImpactKmh.toInt()} km/h",
                        color = AccentCyan,
                        modifier = Modifier.weight(1f)
                    )
                    MetricBox(
                        title = str.blackboxTargetSpeed,
                        value = report.targetSpeedAtImpactKmh?.let { "${it.toInt()} km/h" } ?: "N/A",
                        color = if (report.targetSpeedAtImpactKmh != null) WarningAmber else TextMuted,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricBox(
                        title = str.blackboxRelativeSpeed,
                        value = report.relativeSpeedAtImpactKmh?.let { "${if (it > 0) "+" else ""}${it.toInt()} km/h" } ?: "N/A",
                        color = AlertRed,
                        modifier = Modifier.weight(1f)
                    )
                    MetricBox(
                        title = str.blackboxImpactG,
                        value = "${String.format(Locale.US, "%.2f", report.impactGForce)}G",
                        color = if (report.impactGForce > 2.0f) AlertRed else SafeGreen,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Timeline Section
                Text(
                    text = str.blackboxTimelineTitle,
                    color = AccentCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Timeline Table Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("時間", color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(44.dp))
                    Text("自車時速", color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(56.dp))
                    Text("對方時速", color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(56.dp))
                    Text("距離", color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                    Text("重力", color = TextMuted, fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(4.dp))

                for (item in report.timeline) {
                    val isImpactPoint = item.timeOffsetSec == 0.0f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isImpactPoint) AlertRed.copy(alpha = 0.2f) else CardBackground,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format(Locale.US, "T%+.1fs", item.timeOffsetSec),
                            color = if (isImpactPoint) AlertRed else TextWhite,
                            fontSize = 11.sp,
                            fontWeight = if (isImpactPoint) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(44.dp)
                        )
                        Text(
                            text = "${item.egoSpeedKmh.toInt()} km/h",
                            color = AccentCyan,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(56.dp)
                        )
                        Text(
                            text = item.targetSpeedKmh?.let { "${it.toInt()} km/h" } ?: "-",
                            color = WarningAmber,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(56.dp)
                        )
                        Text(
                            text = item.distanceMeters?.let { String.format(Locale.US, "%.1fm", it) } ?: "-",
                            color = TextWhite,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(42.dp)
                        )
                        Text(
                            text = String.format(Locale.US, "%.2fG", item.gForceTotal),
                            color = if (item.gForceTotal > 1.8f) AlertRed else TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text(str.confirm, color = PureBlack, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardBackground,
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun MetricBox(
    title: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(text = title, color = TextMuted, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
