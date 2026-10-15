package com.visor.dashcam.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val clipId: Long? = null,
    val timestamp: Long,
    val eventType: String, // "FCW", "LVDA", "IMPACT", "HARD_BRAKE", "MANUAL_LOCK"
    val severity: Float = 0f,
    val speedKmh: Float = 0f,
    val gForce: Float = 0f,
    val description: String
)
