package com.visor.dashcam.data.dao

import androidx.room.*
import com.visor.dashcam.data.entity.EventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Query("SELECT * FROM events ORDER BY timestamp DESC")
    fun getAllEventsFlow(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE clipId = :clipId ORDER BY timestamp ASC")
    suspend fun getEventsForClip(clipId: Long): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: EventEntity): Long

    @Query("DELETE FROM events WHERE timestamp < :olderThanMillis")
    suspend fun deleteEventsOlderThan(olderThanMillis: Long)
}
