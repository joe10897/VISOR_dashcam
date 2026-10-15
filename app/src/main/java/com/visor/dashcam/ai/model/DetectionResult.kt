package com.visor.dashcam.ai.model

import android.graphics.RectF

enum class ObstacleClass(val id: Int, val label: String) {
    PERSON(0, "Person"),
    BICYCLE(1, "Bicycle"),
    CAR(2, "Car"),
    MOTORBIKE(3, "Motorbike"),
    BUS(5, "Bus"),
    TRUCK(7, "Truck"),
    UNKNOWN(-1, "Unknown");

    companion object {
        fun fromClassId(id: Int): ObstacleClass {
            return when (id) {
                0 -> PERSON
                1 -> BICYCLE
                2 -> CAR
                3 -> MOTORBIKE
                5 -> BUS
                7 -> TRUCK
                else -> UNKNOWN
            }
        }
    }
}

data class DetectionResult(
    val boundingBox: RectF, // Normalized [0.0, 1.0] coordinates: left, top, right, bottom
    val obstacleClass: ObstacleClass,
    val confidence: Float,
    var distanceMeters: Float = 0f,
    var ttcSeconds: Float = Float.POSITIVE_INFINITY, // Time to collision in seconds
    var isInEgoLane: Boolean = false,
    var trackingId: Int = -1
)
