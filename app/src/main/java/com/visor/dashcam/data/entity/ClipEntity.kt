package com.visor.dashcam.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clips")
data class ClipEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val filePath: String,
    val fileName: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long = 0,
    val durationSeconds: Int = 0,
    val fileSizeBytes: Long = 0,
    val isLocked: Boolean = false,
    val lockReason: String? = null,
    val maxSpeedKmh: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val endLat: Double? = null,
    val endLng: Double? = null,
    val hasImpactEvent: Boolean = false
)
