package com.visor.dashcam.ai.adas

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import com.visor.dashcam.ai.model.DetectionResult
import com.visor.dashcam.ai.model.ObstacleClass
import com.visor.dashcam.data.repository.DashcamSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.max

class ADASManager(
    private val context: Context,
    private val horizonNormalizedY: Float = 0.48f
) {
    private val _currentAlert = MutableStateFlow<ADASAlert?>(null)
    val currentAlert: StateFlow<ADASAlert?> = _currentAlert.asStateFlow()

    private var toneGenerator: ToneGenerator? = null

    // Tracking state for Lead Vehicle (in Ego Lane)
    private var lastLeadDistance: Float? = null
    private var lastLeadTimestamp: Long = 0L
    private var vehicleStoppedStartTime: Long? = null
    private var hasAlertedLeadDeparture = false
    private var lastAlertTime = 0L

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Analyzes detected obstacles alongside current telemetry & user settings.
     */
    fun processFrame(
        detections: List<DetectionResult>,
        currentSpeedKmh: Float,
        settings: DashcamSettings
    ): List<DetectionResult> {
        val now = System.currentTimeMillis()
        val isVehicleStopped = currentSpeedKmh < 2.5f
        val mountHeight = settings.cameraMountHeightMeters

        // Track stop time for LVDA
        if (isVehicleStopped) {
            if (vehicleStoppedStartTime == null) {
                vehicleStoppedStartTime = now
            }
        } else {
            vehicleStoppedStartTime = null
            hasAlertedLeadDeparture = false
        }

        // 1. Calculate Monocular Distance & identify Ego-Lane obstacles
        var closestLeadCar: DetectionResult? = null
        var minLeadDistance = Float.MAX_VALUE

        for (det in detections) {
            val bottomY = det.boundingBox.bottom
            val bboxWidth = det.boundingBox.width()

            val calibFactor = settings.distanceCalibrationFactor.coerceIn(0.5f, 2.0f)
            if (bottomY > horizonNormalizedY + 0.05f) {
                val distFromWidth = (1.1f * 1.8f) / max(bboxWidth, 0.05f)
                val distFromPerspective = mountHeight / max((bottomY - horizonNormalizedY), 0.02f)
                val rawDist = (0.6f * distFromWidth + 0.4f * distFromPerspective).coerceIn(1.5f, 120f)
                det.distanceMeters = (rawDist * calibFactor).coerceIn(1.0f, 120f)
            } else {
                det.distanceMeters = (80f * calibFactor).coerceIn(1.0f, 120f)
            }

            // Ego Lane Detection Corridor: center of screen [0.32, 0.68]
            val centerX = det.boundingBox.centerX()
            det.isInEgoLane = (centerX in 0.32f..0.68f)

            if (det.isInEgoLane && (det.obstacleClass == ObstacleClass.CAR ||
                        det.obstacleClass == ObstacleClass.TRUCK ||
                        det.obstacleClass == ObstacleClass.BUS ||
                        det.obstacleClass == ObstacleClass.MOTORBIKE)
            ) {
                if (det.distanceMeters < minLeadDistance) {
                    minLeadDistance = det.distanceMeters
                    closestLeadCar = det
                }
            }
        }

        // 2. Evaluate ADAS Rules according to Settings
        var activeAlert: ADASAlert? = null

        if (closestLeadCar != null) {
            val leadDist = closestLeadCar.distanceMeters

            // A. Evaluate LVDA (Lead Vehicle Departure Alert)
            val stoppedDuration = vehicleStoppedStartTime?.let { now - it } ?: 0L
            if (settings.lvdaEnabled && isVehicleStopped && stoppedDuration > 3000L) {
                val previousDist = lastLeadDistance
                if (previousDist != null && !hasAlertedLeadDeparture) {
                    val deltaDist = leadDist - previousDist
                    if (deltaDist >= 2.0f && leadDist in 4.0f..25.0f) {
                        activeAlert = ADASAlert(
                            type = ADASAlertType.LVDA_LEAD_DEPARTURE,
                            message = "前車已起步，請注意路況",
                            distanceMeters = leadDist
                        )
                        hasAlertedLeadDeparture = true
                    }
                }
            }

            // B. Evaluate FCW (Forward Collision Warning) when moving
            if (settings.fcwEnabled && currentSpeedKmh >= 20.0f) {
                val previousDist = lastLeadDistance
                val dt = (now - lastLeadTimestamp) / 1000.0f

                if (previousDist != null && dt in 0.05f..0.8f) {
                    val closingSpeedMs = (previousDist - leadDist) / dt

                    if (closingSpeedMs > 1.5f) {
                        val ttc = leadDist / closingSpeedMs
                        closestLeadCar.ttcSeconds = ttc

                        val critTtc = settings.fcwSensitivity.criticalTtcSec
                        val warnTtc = settings.fcwSensitivity.warningTtcSec

                        if (ttc < critTtc) {
                            activeAlert = ADASAlert(
                                type = ADASAlertType.FCW_CRITICAL,
                                message = "⚠️ 碰撞危險！請立即煞車！",
                                ttcSeconds = ttc,
                                distanceMeters = leadDist
                            )
                        } else if (ttc < warnTtc) {
                            activeAlert = ADASAlert(
                                type = ADASAlertType.FCW_WARNING,
                                message = "注意前車距離過近 (TTC ${String.format(Locale.US, "%.1f", ttc)}s)",
                                ttcSeconds = ttc,
                                distanceMeters = leadDist
                            )
                        }
                    }
                }
            }

            // C. Headway Monitoring Warning (HMW)
            if (settings.hmwEnabled && activeAlert == null && currentSpeedKmh > 50f) {
                val currentSpeedMs = currentSpeedKmh / 3.6f
                val timeHeadway = leadDist / max(currentSpeedMs, 1.0f)
                if (timeHeadway < 0.8f) {
                    activeAlert = ADASAlert(
                        type = ADASAlertType.HEADWAY_TOO_CLOSE,
                        message = "車距過近 (${String.format(Locale.US, "%.1f", leadDist)}m)",
                        distanceMeters = leadDist
                    )
                }
            }

            lastLeadDistance = leadDist
            lastLeadTimestamp = now
        } else {
            lastLeadDistance = null
        }

        if (activeAlert != null) {
            _currentAlert.value = activeAlert
            if (settings.alertSoundEnabled && now - lastAlertTime > 2000L) {
                lastAlertTime = now
                playAlertSound(activeAlert.type)
            }
        } else {
            if (now - lastAlertTime > 1500L) {
                _currentAlert.value = null
            }
        }

        return detections
    }

    fun clearAlerts() {
        _currentAlert.value = null
        lastLeadDistance = null
    }

    private fun playAlertSound(type: ADASAlertType) {
        try {
            when (type) {
                ADASAlertType.FCW_CRITICAL -> {
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 400)
                }
                ADASAlertType.FCW_WARNING -> {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                }
                ADASAlertType.LVDA_LEAD_DEPARTURE -> {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 200)
                }
                ADASAlertType.IMPACT_COLLISION -> {
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
                }
                else -> {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() {
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {
        }
    }
}
