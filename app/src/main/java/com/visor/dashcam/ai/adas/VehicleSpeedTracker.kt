package com.visor.dashcam.ai.adas

import android.graphics.RectF
import com.visor.dashcam.ai.model.DetectionResult
import com.visor.dashcam.ai.model.ObstacleClass
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Direction classification of a tracked obstacle relative to the ego vehicle.
 */
enum class TargetMotionDirection(val displayNameZh: String, val displayNameEn: String) {
    SAME_DIRECTION("同向前車", "Preceding (Same Dir)"),
    ONCOMING_OPPOSITE("對向來車", "Oncoming (Opposite Dir)"),
    STATIONARY("靜止障礙", "Stationary Obstacle")
}

/**
 * Result of speed and trajectory estimation for a tracked target.
 */
data class SpeedEstimate(
    val targetSpeedKmh: Float,
    val relativeSpeedKmh: Float,
    val direction: TargetMotionDirection,
    val isBrakeCheckAlert: Boolean, // Preceding vehicle performed sudden emergency braking
    val accelerationMs2: Float,
    val confidence: Float
)

/**
 * Represents an active vehicle track across consecutive camera frames.
 */
data class TrackedVehicle(
    val trackId: Int,
    val obstacleClass: ObstacleClass,
    var lastBoundingBox: RectF,
    var lastUpdateTimestamp: Long,
    var lastEstimatedDistance: Float,
    val history: MutableList<TrackPoint> = mutableListOf(),
    var currentSpeedEstimate: SpeedEstimate? = null
) {
    data class TrackPoint(
        val timestampMs: Long,
        val distanceMeters: Float,
        val centerX: Float,
        val centerY: Float,
        val bboxWidth: Float,
        val bboxHeight: Float,
        val egoSpeedKmh: Float
    )
}

data class TrackerFrameResult(
    val activeTracks: List<TrackedVehicle>,
    val primaryTarget: TrackedVehicle?
)

/**
 * Robust Multi-Object Vehicle Speed Estimator & Motion Tracker for Forensics and ADAS.
 *
 * Uses Ordinary Least Squares (OLS) linear regression over a rolling time window (~0.8s)
 * to estimate relative velocity dD/dt, removing bounding-box pixel jitter.
 *
 * Combines relative velocity with Ego Vehicle GPS/IMU speed to derive absolute target speed:
 * - Preceding vehicle (Same direction): V_target = V_ego + V_rel
 * - Oncoming vehicle (Opposite direction): V_target = |V_rel| - V_ego
 */
class VehicleSpeedTracker {

    private var nextTrackId = 1
    private val tracks = mutableListOf<TrackedVehicle>()
    private val maxHistoryPoints = 12 // At ~10 FPS, spans ~1.2 seconds of telemetry
    private val maxTrackAgeMs = 1200L // Prune tracks not updated for > 1.2s

    /**
     * Updates active tracks with new detections, calculates speeds, and detects sudden braking.
     */
    fun update(
        detections: List<DetectionResult>,
        currentEgoSpeedKmh: Float,
        timestampMs: Long
    ): TrackerFrameResult {
        // 1. Filter obstacle vehicles
        val vehicleDetections = detections.filter {
            it.obstacleClass == ObstacleClass.CAR ||
                    it.obstacleClass == ObstacleClass.TRUCK ||
                    it.obstacleClass == ObstacleClass.BUS ||
                    it.obstacleClass == ObstacleClass.MOTORBIKE
        }

        // 2. Associate detections with existing tracks (greedy IoU / distance matching)
        val unmatchedDetections = vehicleDetections.toMutableList()
        val matchedTrackIds = mutableSetOf<Int>()

        for (track in tracks) {
            var bestDet: DetectionResult? = null
            var bestScore = 0f

            for (det in unmatchedDetections) {
                val iou = calculateIoU(track.lastBoundingBox, det.boundingBox)
                val distMatch = calculateCentroidMatch(track.lastBoundingBox, det.boundingBox)
                val score = 0.7f * iou + 0.3f * distMatch

                if (score > 0.25f && score > bestScore) {
                    bestScore = score
                    bestDet = det
                }
            }

            if (bestDet != null) {
                // Update track with new observation
                track.lastBoundingBox = bestDet.boundingBox
                track.lastUpdateTimestamp = timestampMs
                track.lastEstimatedDistance = bestDet.distanceMeters

                track.history.add(
                    TrackedVehicle.TrackPoint(
                        timestampMs = timestampMs,
                        distanceMeters = bestDet.distanceMeters,
                        centerX = bestDet.boundingBox.centerX(),
                        centerY = bestDet.boundingBox.centerY(),
                        bboxWidth = bestDet.boundingBox.width(),
                        bboxHeight = bestDet.boundingBox.height(),
                        egoSpeedKmh = currentEgoSpeedKmh
                    )
                )
                if (track.history.size > maxHistoryPoints) {
                    track.history.removeAt(0)
                }

                // Compute speed estimate
                track.currentSpeedEstimate = estimateSpeedForTrack(track, currentEgoSpeedKmh)
                matchedTrackIds.add(track.trackId)
                unmatchedDetections.remove(bestDet)
            }
        }

        // 3. Create new tracks for unmatched detections
        for (det in unmatchedDetections) {
            val newTrack = TrackedVehicle(
                trackId = nextTrackId++,
                obstacleClass = det.obstacleClass,
                lastBoundingBox = det.boundingBox,
                lastUpdateTimestamp = timestampMs,
                lastEstimatedDistance = det.distanceMeters
            )
            newTrack.history.add(
                TrackedVehicle.TrackPoint(
                    timestampMs = timestampMs,
                    distanceMeters = det.distanceMeters,
                    centerX = det.boundingBox.centerX(),
                    centerY = det.boundingBox.centerY(),
                    bboxWidth = det.boundingBox.width(),
                    bboxHeight = det.boundingBox.height(),
                    egoSpeedKmh = currentEgoSpeedKmh
                )
            )
            tracks.add(newTrack)
        }

        // 4. Prune old stale tracks
        tracks.removeAll { timestampMs - it.lastUpdateTimestamp > maxTrackAgeMs }

        // 5. Select primary/most critical target (closest vehicle in ego lane or center)
        val primaryTarget = tracks
            .filter { it.history.size >= 3 }
            .minByOrNull {
                // Distance weighted by offset from center
                val centerOffset = abs(it.lastBoundingBox.centerX() - 0.5f)
                it.lastEstimatedDistance * (1.0f + centerOffset * 1.5f)
            }

        return TrackerFrameResult(
            activeTracks = tracks.toList(),
            primaryTarget = primaryTarget
        )
    }

    /**
     * Estimates target speed and motion direction using OLS slope estimation over history window.
     */
    private fun estimateSpeedForTrack(
        track: TrackedVehicle,
        currentEgoSpeedKmh: Float
    ): SpeedEstimate {
        val history = track.history
        if (history.size < 3) {
            // Not enough points yet for robust derivative
            return SpeedEstimate(
                targetSpeedKmh = currentEgoSpeedKmh,
                relativeSpeedKmh = 0f,
                direction = TargetMotionDirection.SAME_DIRECTION,
                isBrakeCheckAlert = false,
                accelerationMs2 = 0f,
                confidence = 0.3f
            )
        }

        // 1. Calculate OLS slope dD/dt
        val t0 = history.first().timestampMs
        val n = history.size
        var sumT = 0.0
        var sumD = 0.0
        var sumTD = 0.0
        var sumT2 = 0.0

        for (pt in history) {
            val t = (pt.timestampMs - t0) / 1000.0 // in seconds
            val d = pt.distanceMeters.toDouble()
            sumT += t
            sumD += d
            sumTD += (t * d)
            sumT2 += (t * t)
        }

        val denominator = (n * sumT2) - (sumT * sumT)
        val slopeMs = if (abs(denominator) > 1e-6) {
            ((n * sumTD) - (sumT * sumD)) / denominator
        } else {
            0.0
        }

        // Relative speed in km/h: negative means distance is shrinking (closing)
        val relativeSpeedKmh = (slopeMs * 3.6).toFloat().coerceIn(-180f, 180f)

        // 2. Classify direction & calculate absolute target speed
        val direction: TargetMotionDirection
        val targetSpeedKmh: Float

        if (relativeSpeedKmh < -1.0f) {
            // Distance is decreasing (closing in)
            val closingSpeedKmh = abs(relativeSpeedKmh)

            if (closingSpeedKmh > (currentEgoSpeedKmh + 6.0f)) {
                // Closing faster than ego speed -> Oncoming vehicle moving towards us!
                direction = TargetMotionDirection.ONCOMING_OPPOSITE
                targetSpeedKmh = max(0f, closingSpeedKmh - currentEgoSpeedKmh)
            } else {
                // Closing slower than ego speed -> Preceding car driving slower in same direction
                direction = TargetMotionDirection.SAME_DIRECTION
                val rawTargetSpeed = currentEgoSpeedKmh + relativeSpeedKmh // relativeSpeedKmh is negative
                targetSpeedKmh = max(0f, rawTargetSpeed)
            }
        } else if (relativeSpeedKmh > 1.0f) {
            // Distance is increasing -> Preceding car driving faster and pulling away
            direction = TargetMotionDirection.SAME_DIRECTION
            targetSpeedKmh = currentEgoSpeedKmh + relativeSpeedKmh
        } else {
            // Relative speed ~ 0 -> Cruising at matched speed
            direction = TargetMotionDirection.SAME_DIRECTION
            targetSpeedKmh = currentEgoSpeedKmh
        }

        // 3. Compute target acceleration / deceleration over the window
        val halfSize = history.size / 2
        var isBrakeCheckAlert = false
        var accelerationMs2 = 0f

        if (halfSize >= 2) {
            val earlierPt = history[0]
            val recentPt = history.last()
            val dtSec = max((recentPt.timestampMs - earlierPt.timestampMs) / 1000.0f, 0.1f)

            // Rate of speed change of target
            val deltaTargetSpeedMs = (targetSpeedKmh - (earlierPt.egoSpeedKmh + relativeSpeedKmh)) / 3.6f
            accelerationMs2 = deltaTargetSpeedMs / dtSec

            // Sudden brake check alert if target in same direction decelerates sharply (< -3.8 m/s²)
            if (direction == TargetMotionDirection.SAME_DIRECTION &&
                accelerationMs2 < -3.8f &&
                currentEgoSpeedKmh > 20f &&
                track.lastEstimatedDistance < 40f
            ) {
                isBrakeCheckAlert = true
            }
        }

        return SpeedEstimate(
            targetSpeedKmh = targetSpeedKmh,
            relativeSpeedKmh = relativeSpeedKmh,
            direction = direction,
            isBrakeCheckAlert = isBrakeCheckAlert,
            accelerationMs2 = accelerationMs2,
            confidence = (n / 10f).coerceIn(0.4f, 0.95f)
        )
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val union = areaA + areaB - intersection
        return if (union > 0f) (intersection / union).coerceIn(0f, 1f) else 0f
    }

    private fun calculateCentroidMatch(a: RectF, b: RectF): Float {
        val dx = abs(a.centerX() - b.centerX())
        val dy = abs(a.centerY() - b.centerY())
        val dist = dx + dy
        return max(0f, 1.0f - dist * 3.0f)
    }

    fun clear() {
        tracks.clear()
        nextTrackId = 1
    }
}
