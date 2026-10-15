package com.visor.dashcam.recording

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.visor.dashcam.data.dao.ClipDao
import com.visor.dashcam.data.entity.ClipEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StorageManager(
    private val context: Context,
    private val clipDao: ClipDao
) {
    private val minFreeSpaceBytes: Long = 1024L * 1024L * 1500L // Keep at least 1.5GB free

    val recordingsDir: File
        get() {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VISOR_Clips")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

    fun createNewVideoFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir, "VISOR_${timestamp}.mp4")
    }

    suspend fun checkAndPurgeOldestClips() = withContext(Dispatchers.IO) {
        val stat = StatFs(recordingsDir.path)
        var availableBytes = stat.availableBlocksLong * stat.blockSizeLong

        while (availableBytes < minFreeSpaceBytes) {
            val oldestClips = clipDao.getOldestUnlockedClips(limit = 3)
            if (oldestClips.isEmpty()) {
                // All clips are locked or disk is completely full of other apps
                break
            }

            for (clip in oldestClips) {
                val file = File(clip.filePath)
                if (file.exists()) {
                    file.delete()
                }
                clipDao.delete(clip)
            }

            // Refresh available space
            val updatedStat = StatFs(recordingsDir.path)
            availableBytes = updatedStat.availableBlocksLong * updatedStat.blockSizeLong
        }
    }

    suspend fun saveClipRecord(
        file: File,
        startTime: Long,
        endTime: Long,
        maxSpeed: Float,
        avgSpeed: Float,
        startLat: Double?,
        startLng: Double?,
        endLat: Double?,
        endLng: Double?,
        isLocked: Boolean = false,
        lockReason: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val duration = ((endTime - startTime) / 1000).toInt()
        val entity = ClipEntity(
            filePath = file.absolutePath,
            fileName = file.name,
            startTimeMillis = startTime,
            endTimeMillis = endTime,
            durationSeconds = duration,
            fileSizeBytes = file.length(),
            isLocked = isLocked,
            lockReason = lockReason,
            maxSpeedKmh = maxSpeed,
            avgSpeedKmh = avgSpeed,
            startLat = startLat,
            startLng = startLng,
            endLat = endLat,
            endLng = endLng
        )
        clipDao.insert(entity)
    }

    suspend fun lockClip(clipId: Long, reason: String) = withContext(Dispatchers.IO) {
        clipDao.setLockStatus(clipId, true, reason)
    }
}
