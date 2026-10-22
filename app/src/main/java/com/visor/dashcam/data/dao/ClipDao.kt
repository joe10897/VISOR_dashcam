package com.visor.dashcam.data.dao

import androidx.room.*
import com.visor.dashcam.data.entity.ClipEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {

    @Query("SELECT * FROM clips ORDER BY startTimeMillis DESC")
    fun getAllClipsFlow(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE isLocked = 1 ORDER BY startTimeMillis DESC")
    fun getLockedClipsFlow(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE id = :id LIMIT 1")
    suspend fun getClipById(id: Long): ClipEntity?

    @Query("SELECT * FROM clips WHERE isLocked = 0 ORDER BY startTimeMillis ASC LIMIT :limit")
    suspend fun getOldestUnlockedClips(limit: Int): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE isLocked = 0")
    suspend fun getAllUnlockedClips(): List<ClipEntity>

    @Query("SELECT SUM(fileSizeBytes) FROM clips")
    suspend fun getTotalStorageUsedBytes(): Long?

    @Query("SELECT SUM(fileSizeBytes) FROM clips")
    fun getTotalStorageUsedBytesFlow(): Flow<Long?>

    @Query("SELECT SUM(fileSizeBytes) FROM clips WHERE isLocked = 1")
    fun getLockedStorageUsedBytesFlow(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM clips")
    fun getTotalClipCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM clips WHERE isLocked = 1")
    fun getLockedClipCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(clip: ClipEntity): Long

    @Update
    suspend fun update(clip: ClipEntity)

    @Delete
    suspend fun delete(clip: ClipEntity)

    @Query("UPDATE clips SET isLocked = :isLocked, lockReason = :reason WHERE id = :clipId")
    suspend fun setLockStatus(clipId: Long, isLocked: Boolean, reason: String?)
}
