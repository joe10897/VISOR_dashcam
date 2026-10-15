package com.visor.dashcam.sensors

data class TelemetryData(
    val speedKmh: Float = 0f,
    val headingDegrees: Float = 0f,
    val altitudeMeters: Double = 0.0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val gForceTotal: Float = 1.0f,
    val gForceX: Float = 0f,
    val gForceY: Float = 0f,
    val gForceZ: Float = 1.0f,
    val isImpactDetected: Boolean = false,
    val isHardBrakeDetected: Boolean = false
)
