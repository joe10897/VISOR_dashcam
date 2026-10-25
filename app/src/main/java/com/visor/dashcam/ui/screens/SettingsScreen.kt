package com.visor.dashcam.ui.screens

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visor.dashcam.VisorApp
import com.visor.dashcam.data.repository.*
import com.visor.dashcam.ui.i18n.*
import com.visor.dashcam.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onClearUnlockedClips: suspend () -> Int,
    onEnforceQuotaNow: suspend () -> Unit,
    onVideoQualityChanged: () -> Unit,
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val app = context.applicationContext as VisorApp
    val clipDao = app.database.clipDao()

    val settings by settingsRepository.settings.collectAsState()
    val totalUsedBytes by clipDao.getTotalStorageUsedBytesFlow().collectAsState(initial = 0L)
    val lockedUsedBytes by clipDao.getLockedStorageUsedBytesFlow().collectAsState(initial = 0L)
    val totalClipsCount by clipDao.getTotalClipCountFlow().collectAsState(initial = 0)
    val lockedClipsCount by clipDao.getLockedClipCountFlow().collectAsState(initial = 0)

    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var statusToastMessage by remember { mutableStateOf<String?>(null) }

    val crashLogFile = remember { File(context.filesDir, "last_crash_log.txt") }
    var crashLogText by remember {
        mutableStateOf(if (crashLogFile.exists()) crashLogFile.readText() else null)
    }

    val freeDiskGb = remember(totalUsedBytes) {
        try {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val stat = StatFs(baseDir.path)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024f * 1024f * 1024f)
        } catch (_: Exception) {
            0f
        }
    }

    val str = remember(settings.language) { VisorStrings.get(settings.language) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str.settingsTitle, color = TextWhite, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBackClicked) {
                        Icon(Icons.Default.ArrowBack, contentDescription = str.back, tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = PureBlack,
        modifier = modifier
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Optional status feedback banner
            statusToastMessage?.let { msg ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SafeGreen.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SafeGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = msg, color = TextWhite, fontSize = 13.sp)
                        }
                    }
                }
            }

            // ================= 0. 語言與介面設定 (Language) =================
            item {
                SettingsSectionCard(
                    title = str.languageSection,
                    icon = Icons.Default.Language
                ) {
                    Text(
                        text = str.languageTitle,
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = str.languageDesc,
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = settings.language == lang,
                                onClick = {
                                    settingsRepository.updateSettings { it.copy(language = lang) }
                                },
                                label = { Text(lang.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }
                }
            }

            // ================= 1. 儲存與最大空間上限 =================
            item {
                SettingsSectionCard(
                    title = str.storageSection,
                    icon = Icons.Default.Storage
                ) {
                    val usedBytes = totalUsedBytes ?: 0L
                    val lockedBytes = lockedUsedBytes ?: 0L
                    val usedGb = usedBytes / (1024f * 1024f * 1024f)
                    val usedMb = usedBytes / (1024f * 1024f)
                    val lockedMb = lockedBytes / (1024f * 1024f)
                    val maxQuotaGb = settings.maxStorageQuotaGb.toFloat()
                    val progress = (usedGb / maxQuotaGb).coerceIn(0f, 1f)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (usedGb >= 1.0f) {
                                String.format(str.storageUsed, String.format(Locale.US, "%.2f GB", usedGb), "${settings.maxStorageQuotaGb} GB")
                            } else {
                                String.format(str.storageUsed, String.format(Locale.US, "%.1f MB", usedMb), "${settings.maxStorageQuotaGb} GB")
                            },
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = String.format(str.deviceFree, String.format(Locale.US, "%.1f GB", freeDiskGb)),
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { progress },
                        color = if (progress > 0.9f) AlertRed else if (progress > 0.75f) WarningAmber else AccentCyan,
                        trackColor = DarkSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = String.format(str.clipSummary, totalClipsCount, lockedClipsCount, "${String.format(Locale.US, "%.1f", lockedMb)} MB"),
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = DarkSurface)

                    // 最大佔用空間上限選擇
                    Text(
                        text = str.maxQuotaTitle,
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val quotaOptions = listOf(2, 4, 8, 16, 32, 64)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quotaOptions.forEach { gb ->
                            FilterChip(
                                selected = settings.maxStorageQuotaGb == gb,
                                onClick = {
                                    settingsRepository.updateSettings { it.copy(maxStorageQuotaGb = gb) }
                                    coroutineScope.launch {
                                        onEnforceQuotaNow()
                                        statusToastMessage = if (settings.language == AppLanguage.EN) "Max quota set to $gb GB" else "已將最大佔用空間設為 $gb GB"
                                    }
                                },
                                label = { Text("$gb GB") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 系統最低保留空間
                    Text(
                        text = str.minFreeSpaceTitle,
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val minFreeOptions = listOf(
                        512 to "512 MB",
                        1024 to "1 GB",
                        2048 to "2 GB",
                        4096 to "4 GB"
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        minFreeOptions.forEach { (mb, label) ->
                            FilterChip(
                                selected = settings.minFreeSpaceMb == mb,
                                onClick = {
                                    settingsRepository.updateSettings { it.copy(minFreeSpaceMb = mb) }
                                    coroutineScope.launch { onEnforceQuotaNow() }
                                },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedButton(
                        onClick = { showClearConfirmDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(str.clearUnlockedButton)
                    }
                }
            }

            // ================= 2. 錄影畫質與分段設定 =================
            item {
                SettingsSectionCard(
                    title = str.recordingSection,
                    icon = Icons.Default.Videocam
                ) {
                    Text(
                        text = str.segmentDurationTitle,
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val durationOptions = listOf(
                        60 to if (settings.language == AppLanguage.EN) "1 Min" else "1 分鐘",
                        180 to if (settings.language == AppLanguage.EN) "3 Min" else "3 分鐘",
                        300 to if (settings.language == AppLanguage.EN) "5 Min" else "5 分鐘"
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        durationOptions.forEach { (sec, label) ->
                            FilterChip(
                                selected = settings.segmentDurationSeconds == sec,
                                onClick = {
                                    settingsRepository.updateSettings { it.copy(segmentDurationSeconds = sec) }
                                },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = str.videoQualityTitle,
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoQualityOption.entries.forEach { option ->
                            FilterChip(
                                selected = settings.videoQuality == option,
                                onClick = {
                                    if (settings.videoQuality != option) {
                                        settingsRepository.updateSettings { it.copy(videoQuality = option) }
                                        onVideoQualityChanged()
                                        statusToastMessage = if (settings.language == AppLanguage.EN) "Quality switched to ${option.getLabel(settings.language)}" else "已切換畫質為 ${option.label}"
                                    }
                                },
                                label = { Text(option.getLabel(settings.language)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = DarkSurface)

                    SettingsSwitchRow(
                        title = str.cabinAudioTitle,
                        subtitle = str.cabinAudioSub,
                        checked = settings.recordAudio,
                        onCheckedChange = { checked ->
                            settingsRepository.updateSettings { it.copy(recordAudio = checked) }
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = DarkSurface)

                    SettingsSwitchRow(
                        title = str.watermarkTitle,
                        subtitle = str.watermarkSub,
                        checked = settings.burnInVideoWatermark,
                        onCheckedChange = { checked ->
                            settingsRepository.updateSettings { it.copy(burnInVideoWatermark = checked) }
                            onVideoQualityChanged()
                        }
                    )
                }
            }

            // ================= 3. 離線 AI 與 ADAS 駕駛輔助 =================
            item {
                SettingsSectionCard(
                    title = str.aiSection,
                    icon = Icons.Default.Psychology
                ) {
                    SettingsSwitchRow(
                        title = str.enableYoloTitle,
                        subtitle = str.enableYoloSub,
                        checked = settings.aiEnabled,
                        onCheckedChange = { checked ->
                            settingsRepository.updateSettings { it.copy(aiEnabled = checked) }
                        }
                    )

                    if (settings.aiEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = DarkSurface)

                        SettingsSwitchRow(
                            title = str.fcwTitle,
                            subtitle = str.fcwSub,
                            checked = settings.fcwEnabled,
                            onCheckedChange = { checked ->
                                settingsRepository.updateSettings { it.copy(fcwEnabled = checked) }
                            }
                        )

                        if (settings.fcwEnabled) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(str.fcwSensitivityTitle, color = TextMuted, fontSize = 12.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FcwSensitivity.entries.forEach { sens ->
                                    FilterChip(
                                        selected = settings.fcwSensitivity == sens,
                                        onClick = {
                                            settingsRepository.updateSettings { it.copy(fcwSensitivity = sens) }
                                        },
                                        label = { Text(sens.getLabel(settings.language)) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = WarningAmber,
                                            selectedLabelColor = PureBlack
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(str.distanceCalibrationTitle, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(str.distanceCalibrationSub, color = TextMuted, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            val distanceFactors = listOf(
                                0.80f to if (settings.language == AppLanguage.EN) "0.8x (Close)" else "0.8x (近/較晚)",
                                0.90f to "0.9x",
                                1.00f to if (settings.language == AppLanguage.EN) "1.0x (Standard)" else "1.0x (標準推薦)",
                                1.10f to "1.1x",
                                1.20f to if (settings.language == AppLanguage.EN) "1.2x (Far)" else "1.2x (遠/提早)"
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                distanceFactors.forEach { (factor, label) ->
                                    FilterChip(
                                        selected = kotlin.math.abs(settings.distanceCalibrationFactor - factor) < 0.03f,
                                        onClick = {
                                            settingsRepository.updateSettings { it.copy(distanceCalibrationFactor = factor) }
                                        },
                                        label = { Text(label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AccentCyan,
                                            selectedLabelColor = PureBlack
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        SettingsSwitchRow(
                            title = str.lvdaTitle,
                            subtitle = str.lvdaSub,
                            checked = settings.lvdaEnabled,
                            onCheckedChange = { checked ->
                                settingsRepository.updateSettings { it.copy(lvdaEnabled = checked) }
                            }
                        )

                        SettingsSwitchRow(
                            title = str.hmwTitle,
                            subtitle = str.hmwSub,
                            checked = settings.hmwEnabled,
                            onCheckedChange = { checked ->
                                settingsRepository.updateSettings { it.copy(hmwEnabled = checked) }
                            }
                        )

                        SettingsSwitchRow(
                            title = str.beepSoundTitle,
                            subtitle = str.beepSoundSub,
                            checked = settings.alertSoundEnabled,
                            onCheckedChange = { checked ->
                                settingsRepository.updateSettings { it.copy(alertSoundEnabled = checked) }
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = DarkSurface)

                        // AI 推論幀率控制
                        Text(
                            text = str.targetFpsTitle,
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val fpsOptions = listOf(
                            5 to if (settings.language == AppLanguage.EN) "5 FPS (Cool)" else "5 FPS (省電低溫)",
                            10 to if (settings.language == AppLanguage.EN) "10 FPS (Balanced)" else "10 FPS (平衡推薦)",
                            15 to if (settings.language == AppLanguage.EN) "15 FPS (High)" else "15 FPS (高靈敏)"
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            fpsOptions.forEach { (fps, label) ->
                                FilterChip(
                                    selected = settings.aiTargetFps == fps,
                                    onClick = {
                                        settingsRepository.updateSettings { it.copy(aiTargetFps = fps) }
                                    },
                                    label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentCyan,
                                        selectedLabelColor = PureBlack
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 信心度門檻 Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (settings.language == AppLanguage.EN) "AI Detection Confidence" else "AI 辨識信心度門檻",
                                color = TextWhite,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "${(settings.aiConfidenceThreshold * 100).roundToInt()}%",
                                color = AccentCyan,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Slider(
                            value = settings.aiConfidenceThreshold,
                            onValueChange = { v ->
                                val rounded = (v * 20).roundToInt() / 20f
                                settingsRepository.updateSettings { it.copy(aiConfidenceThreshold = rounded) }
                            },
                            valueRange = 0.25f..0.65f,
                            steps = 7,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentCyan,
                                activeTrackColor = AccentCyan
                            )
                        )

                        SettingsSwitchRow(
                            title = str.nnapiTitle,
                            subtitle = str.nnapiSub,
                            checked = settings.useNnapiAcceleration,
                            onCheckedChange = { checked ->
                                settingsRepository.updateSettings { it.copy(useNnapiAcceleration = checked) }
                            }
                        )
                    }
                }
            }

            // ================= 4. G-Sensor 與車輛校正設定 =================
            item {
                SettingsSectionCard(
                    title = str.sensorSection,
                    icon = Icons.Default.Sensors
                ) {
                    Text(
                        text = str.impactSensitivityTitle,
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ImpactSensitivity.entries.forEach { sens ->
                            FilterChip(
                                selected = settings.impactSensitivity == sens,
                                onClick = {
                                    settingsRepository.updateSettings { it.copy(impactSensitivity = sens) }
                                },
                                label = { Text(sens.getLabel(settings.language)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = str.vehicleMountHeightTitle,
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val mountOptions = listOf(
                        1.15f to if (settings.language == AppLanguage.EN) "Sedan (1.15m)" else "轎車 (1.15m)",
                        1.30f to if (settings.language == AppLanguage.EN) "SUV (1.30m)" else "休旅車 SUV (1.30m)",
                        1.60f to if (settings.language == AppLanguage.EN) "Truck/Bus (1.60m)" else "貨車/巴士 (1.60m)"
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        mountOptions.forEach { (heightM, label) ->
                            FilterChip(
                                selected = kotlin.math.abs(settings.cameraMountHeightMeters - heightM) < 0.05f,
                                onClick = {
                                    settingsRepository.updateSettings { it.copy(cameraMountHeightMeters = heightM) }
                                },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = str.speedUnitTitle,
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpeedUnit.entries.forEach { unit ->
                            FilterChip(
                                selected = settings.speedUnit == unit,
                                onClick = {
                                    settingsRepository.updateSettings { it.copy(speedUnit = unit) }
                                },
                                label = { Text(unit.getLabel(settings.language)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }
                }
            }

            // ================= 5. 離線異常診斷日誌 (若有發生過異常) =================
            if (!crashLogText.isNullOrBlank()) {
                item {
                    SettingsSectionCard(
                        title = if (settings.language == AppLanguage.EN) "Crash Diagnostics" else "本機異常診斷紀錄",
                        icon = Icons.Default.BugReport
                    ) {
                        Text(
                            text = crashLogText!!.take(600),
                            color = WarningAmber,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                runCatching { crashLogFile.delete() }
                                crashLogText = null
                            }
                        ) {
                            Text(if (settings.language == AppLanguage.EN) "Clear Log" else "清除診斷紀錄", color = TextWhite)
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            containerColor = CardBackground,
            title = { Text(str.clearConfirmTitle, color = TextWhite) },
            text = {
                Text(
                    text = str.clearConfirmMsg,
                    color = TextMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        coroutineScope.launch(Dispatchers.Main) {
                            val count = onClearUnlockedClips()
                            statusToastMessage = if (settings.language == AppLanguage.EN) "Cleared $count unlocked clips" else "已成功清理 $count 段未鎖定影片"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text(str.confirmClear, color = TextWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(str.cancel, color = TextMuted)
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = AccentCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = AccentCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = TextMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PureBlack,
                checkedTrackColor = AccentCyan
            )
        )
    }
}
