package com.visor.dashcam.ai.adas

enum class ADASAlertType(
    val priority: Int,
    val titleRes: String,
    val requiresBuzzer: Boolean
) {
    NONE(0, "正常", false),
    LVDA_LEAD_DEPARTURE(1, "前車已起步", true),
    HEADWAY_TOO_CLOSE(2, "車距過近", false),
    FCW_WARNING(3, "注意前方車輛", true),
    FCW_CRITICAL(4, "碰撞警報！請立即煞車", true),
    IMPACT_COLLISION(5, "偵測到碰撞，已緊急鎖檔", true)
}

data class ADASAlert(
    val type: ADASAlertType,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val ttcSeconds: Float? = null,
    val distanceMeters: Float? = null
)
