package com.visor.dashcam.recording

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.visor.dashcam.data.dao.ClipDao
import com.visor.dashcam.data.entity.ClipEntity
import com.visor.dashcam.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StorageManager(
    private val context: Context,
    private val clipDao: ClipDao,
    private val settingsRepository: SettingsRepository
) {
    val recordingsDir: File
        get() {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val dir = File(baseDir, "VISOR_Clips")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

    fun createNewVideoFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(recordingsDir, "VISOR_${timestamp}.mp4")
    }

    fun getAvailableDeviceStorageGb(): Float {
        return try {
            val stat = StatFs(recordingsDir.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            availableBytes / (1024f * 1024f * 1024f)
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * Checks both:
     * 1. User-configured Max Storage Quota (e.g., 8 GB max for VISOR clips)
     * 2. Device minimum free space threshold (e.g., keep at least 1 GB free on phone)
     * Purges oldest unlocked clips until both constraints are satisfied.
     */
    suspend fun checkAndPurgeOldestClips() = withContext(Dispatchers.IO) {
        try {
            val currentSettings = settingsRepository.settings.value
            val maxQuotaBytes = currentSettings.maxStorageQuotaGb.toLong() * 1024L * 1024L * 1024L
            val minFreeSpaceBytes = currentSettings.minFreeSpaceMb.toLong() * 1024L * 1024L

            var safetyCounter = 0
            while (safetyCounter < 50) {
                safetyCounter++

                val stat = StatFs(recordingsDir.path)
                val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
                val usedByVisorBytes = clipDao.getTotalStorageUsedBytes() ?: 0L

                // Reserve ~150MB headroom for the upcoming recording segment
                val segmentHeadroomBytes = 150L * 1024L * 1024L
                val isOverQuota = (usedByVisorBytes + segmentHeadroomBytes) > maxQuotaBytes
                val isDiskLow = availableBytes < minFreeSpaceBytes

                if (!isOverQuota && !isDiskLow) {
                    break
                }

                val oldestClips = clipDao.getOldestUnlockedClips(limit = 3)
                if (oldestClips.isEmpty()) {
                    // All remaining clips are locked or none left
                    break
                }

                for (clip in oldestClips) {
                    runCatching {
                        val file = File(clip.filePath)
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                    clipDao.delete(clip)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun clearAllUnlockedClips(): Int = withContext(Dispatchers.IO) {
        val unlockedClips = clipDao.getAllUnlockedClips()
        var deletedCount = 0
        for (clip in unlockedClips) {
            runCatching {
                val file = File(clip.filePath)
                if (file.exists()) {
                    file.delete()
                }
            }
            clipDao.delete(clip)
            deletedCount++
        }
        deletedCount
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
        val duration = ((endTime - startTime) / 1000).toInt().coerceAtLeast(1)
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
