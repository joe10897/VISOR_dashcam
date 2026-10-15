package com.visor.dashcam.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val clipDao = (context.applicationContext as VisorApp).database.clipDao()

    var showLockedOnly by remember { mutableStateOf(false) }
    val clipsFlow = remember(showLockedOnly) {
        if (showLockedOnly) clipDao.getLockedClipsFlow() else clipDao.getAllClipsFlow()
    }
    val clips by clipsFlow.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本機行車錄影庫 (100% 離線)", color = TextWhite, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBackClicked) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = TextWhite)
                    }
                },
                actions = {
                    FilterChip(
                        selected = showLockedOnly,
                        onClick = { showLockedOnly = !showLockedOnly },
                        label = { Text(if (showLockedOnly) "僅已鎖檔" else "全部影片") },
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
                    text = if (showLockedOnly) "尚無鎖定保護之片段" else "尚無錄製影片",
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
                                clipDao.delete(clip)
                            }
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
    onPlayClicked: () -> Unit,
    onToggleLock: () -> Unit,
    onDeleteClicked: () -> Unit
) {
    val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
        .format(Date(clip.startTimeMillis))

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
                    Text(
                        text = "最高車速: ${clip.maxSpeedKmh.toInt()} km/h | 時長: ${clip.durationSeconds}s",
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (clip.lockReason != null) {
                        Text(
                            text = "鎖定原因: ${clip.lockReason}",
                            color = WarningAmber,
                            fontSize = 11.sp
                        )
                    }
                }

                Row {
                    IconButton(onClick = onToggleLock) {
                        Icon(
                            imageVector = if (clip.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "鎖定/解鎖",
                            tint = if (clip.isLocked) LockedGold else TextMuted
                        )
                    }
                    if (!clip.isLocked) {
                        IconButton(onClick = onDeleteClicked) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "刪除",
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
