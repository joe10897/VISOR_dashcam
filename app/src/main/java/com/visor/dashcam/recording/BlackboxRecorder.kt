package com.visor.dashcam.recording

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Raw telemetry sample recorded continuously into the in-memory ring buffer.
 */
data class BlackboxSample(
    val timestampMs: Long,
    val egoSpeedKmh: Float,
    val gForceTotal: Float,
    val gForceX: Float,
    val gForceY: Float,
    val gForceZ: Float,
    val targetDistanceM: Float? = null,
    val targetSpeedKmh: Float? = null,
    val targetRelSpeedKmh: Float? = null,
    val targetDirection: String? = null,
    val isTargetHardBraking: Boolean = false
)

/**
 * Structured forensic report generated at the moment of an incident.
 */
data class BlackboxReport(
    val eventId: String,
    val triggerTimestampMs: Long,
    val videoFileName: String,
    val eventType: String,
    val triggerReason: String,
    val egoSpeedAtImpactKmh: Float,
    val targetSpeedAtImpactKmh: Float?,
    val relativeSpeedAtImpactKmh: Float?,
    val impactGForce: Float,
    val scenarioZh: String,
    val scenarioEn: String,
    val timeline: List<TimelineItem>
) {
    data class TimelineItem(
        val timeOffsetSec: Float, // e.g. -3.0, -2.5, -2.0, -1.5, -1.0, -0.5, 0.0
        val egoSpeedKmh: Float,
        val targetSpeedKmh: Float?,
        val distanceMeters: Float?,
        val gForceTotal: Float,
        val note: String? = null
    )
}

/**
 * Thread-safe 5-Second Circular Ring Buffer & Crash Forensic Blackbox Engine.
 *
 * Silently records vehicle speeds, G-forces, and tracked target velocities in memory.
 * When an accident, impact, hard brake, or manual lock is detected, it freezes the pre-crash
 * 3.5s trajectory and serializes a high-precision forensic JSON sidecar alongside the MP4 clip.
 */
class BlackboxRecorder {

    private val samples = ArrayDeque<BlackboxSample>()
    private val lock = Any()
    private val maxCapacity = 60 // ~6 seconds at 10Hz sampling

    /**
     * Records a single telemetry frame into the rolling circular buffer.
     */
    fun recordSample(sample: BlackboxSample) {
        synchronized(lock) {
            samples.addLast(sample)
            if (samples.size > maxCapacity) {
                samples.removeFirst()
            }
        }
    }

    /**
     * Captures a pre-crash snapshot (-3.5s to 0.0s) and writes a forensic JSON file companion to the MP4 clip.
     */
    fun captureEventSnapshot(
        videoFile: File?,
        eventType: String,
        reason: String,
        impactG: Float = 0f
    ): BlackboxReport? {
        val triggerTime = System.currentTimeMillis()
        val snapshot: List<BlackboxSample>

        synchronized(lock) {
            if (samples.isEmpty()) return null
            snapshot = samples.toList()
        }

        // Filter samples in the window: [triggerTime - 3500ms .. triggerTime + 500ms]
        val windowSamples = snapshot.filter {
            it.timestampMs in (triggerTime - 3500L)..(triggerTime + 500L)
        }
        val targetList = if (windowSamples.isNotEmpty()) windowSamples else snapshot.takeLast(10)
        val triggerSample = targetList.lastOrNull() ?: return null

        val egoSpeedAtImpact = triggerSample.egoSpeedKmh
        val targetSpeedAtImpact = triggerSample.targetSpeedKmh
        val relativeSpeedAtImpact = triggerSample.targetRelSpeedKmh
        val maxG = maxOf(impactG, targetList.maxOfOrNull { it.gForceTotal } ?: 1.0f)

        // Classify Forensic Scenario
        val isTargetHardBraking = targetList.any { it.isTargetHardBraking }
        val isOncoming = targetList.any { it.targetDirection == "ONCOMING_OPPOSITE" }

        val scenarioZh: String
        val scenarioEn: String

        when {
            isTargetHardBraking -> {
                scenarioZh = "同向前車突發急煞 (前車大幅減速逼近)"
                scenarioEn = "Preceding Vehicle Sudden Hard Braking (Brake Check)"
            }
            isOncoming -> {
                val oncomingSpeed = targetSpeedAtImpact?.toInt() ?: 0
                scenarioZh = "路口對向超速來車 (對向估算時速約 $oncomingSpeed km/h)"
                scenarioEn = "Oncoming Traffic Approaching (Estimated ~ $oncomingSpeed km/h)"
            }
            eventType.contains("IMPACT") -> {
                scenarioZh = "G-Sensor 衝擊碰撞鎖檔 (${String.format(Locale.US, "%.2f", maxG)}G 重力震撼)"
                scenarioEn = "Collision Impact Event (${String.format(Locale.US, "%.2f", maxG)}G Peak)"
            }
            eventType.contains("HARD_BRAKE") -> {
                scenarioZh = "自車緊急減速急煞"
                scenarioEn = "Ego Vehicle Hard Emergency Braking"
            }
            eventType.contains("FCW") -> {
                scenarioZh = "前車防撞嚴重危險預警 (TTC 臨界值)"
                scenarioEn = "Forward Collision Critical Alert (Critical TTC)"
            }
            else -> {
                scenarioZh = "駕駛手動存證鎖檔"
                scenarioEn = "Manual Driver Incident Tagging"
            }
        }

        // Downsample into ~7 clear timeline steps: -3.0s, -2.5s, -2.0s, -1.5s, -1.0s, -0.5s, 0.0s
        val timelineOffsets = listOf(-3.0f, -2.5f, -2.0f, -1.5f, -1.0f, -0.5f, 0.0f)
        val timeline = timelineOffsets.map { offsetSec ->
            val targetTime = triggerTime + (offsetSec * 1000f).toLong()
            val closestSample = targetList.minByOrNull { abs(it.timestampMs - targetTime) }

            val note = when {
                offsetSec == 0.0f -> "撞擊 / 鎖檔觸發點"
                offsetSec == -1.0f && isTargetHardBraking -> "前車急煞減速點"
                offsetSec == -2.0f && isOncoming -> "對向來車逼近"
                else -> null
            }

            BlackboxReport.TimelineItem(
                timeOffsetSec = offsetSec,
                egoSpeedKmh = closestSample?.egoSpeedKmh ?: egoSpeedAtImpact,
                targetSpeedKmh = closestSample?.targetSpeedKmh,
                distanceMeters = closestSample?.targetDistanceM,
                gForceTotal = closestSample?.gForceTotal ?: 1.0f,
                note = note
            )
        }

        val report = BlackboxReport(
            eventId = "BB_${triggerTime}",
            triggerTimestampMs = triggerTime,
            videoFileName = videoFile?.name ?: "UNKNOWN.mp4",
            eventType = eventType,
            triggerReason = reason,
            egoSpeedAtImpactKmh = egoSpeedAtImpact,
            targetSpeedAtImpactKmh = targetSpeedAtImpact,
            relativeSpeedAtImpactKmh = relativeSpeedAtImpact,
            impactGForce = maxG,
            scenarioZh = scenarioZh,
            scenarioEn = scenarioEn,
            timeline = timeline
        )

        // Write companion JSON file if video file provided
        if (videoFile != null) {
            try {
                val jsonFile = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}_blackbox.json")
                val jsonString = serializeToJson(report)
                jsonFile.writeText(jsonString)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return report
    }

    private fun serializeToJson(report: BlackboxReport): String {
        val root = JSONObject()
        root.put("eventId", report.eventId)
        root.put("triggerTimestampMs", report.triggerTimestampMs)
        root.put("videoFileName", report.videoFileName)
        root.put("eventType", report.eventType)
        root.put("triggerReason", report.triggerReason)
        root.put("egoSpeedAtImpactKmh", report.egoSpeedAtImpactKmh.toDouble())
        if (report.targetSpeedAtImpactKmh != null) {
            root.put("targetSpeedAtImpactKmh", report.targetSpeedAtImpactKmh.toDouble())
        }
        if (report.relativeSpeedAtImpactKmh != null) {
            root.put("relativeSpeedAtImpactKmh", report.relativeSpeedAtImpactKmh.toDouble())
        }
        root.put("impactGForce", report.impactGForce.toDouble())
        root.put("scenarioZh", report.scenarioZh)
        root.put("scenarioEn", report.scenarioEn)

        val timelineArray = JSONArray()
        for (item in report.timeline) {
            val itemObj = JSONObject()
            itemObj.put("timeOffsetSec", item.timeOffsetSec.toDouble())
            itemObj.put("egoSpeedKmh", item.egoSpeedKmh.toDouble())
            if (item.targetSpeedKmh != null) {
                itemObj.put("targetSpeedKmh", item.targetSpeedKmh.toDouble())
            }
            if (item.distanceMeters != null) {
                itemObj.put("distanceMeters", item.distanceMeters.toDouble())
            }
            itemObj.put("gForceTotal", item.gForceTotal.toDouble())
            if (item.note != null) {
                itemObj.put("note", item.note)
            }
            timelineArray.put(itemObj)
        }
        root.put("timeline", timelineArray)

        return root.toString(2)
    }

    companion object {
        /**
         * Loads and parses a companion blackbox report JSON for a video file, if it exists.
         */
        fun loadReportForVideo(videoFile: File): BlackboxReport? {
            return try {
                val jsonFile = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}_blackbox.json")
                if (!jsonFile.exists() || !jsonFile.canRead()) return null

                val content = jsonFile.readText()
                val root = JSONObject(content)

                val timelineArray = root.optJSONArray("timeline") ?: JSONArray()
                val timeline = mutableListOf<BlackboxReport.TimelineItem>()

                for (i in 0 until timelineArray.length()) {
                    val itemObj = timelineArray.getJSONObject(i)
                    timeline.add(
                        BlackboxReport.TimelineItem(
                            timeOffsetSec = itemObj.optDouble("timeOffsetSec", 0.0).toFloat(),
                            egoSpeedKmh = itemObj.optDouble("egoSpeedKmh", 0.0).toFloat(),
                            targetSpeedKmh = if (itemObj.has("targetSpeedKmh")) itemObj.getDouble("targetSpeedKmh").toFloat() else null,
                            distanceMeters = if (itemObj.has("distanceMeters")) itemObj.getDouble("distanceMeters").toFloat() else null,
                            gForceTotal = itemObj.optDouble("gForceTotal", 1.0).toFloat(),
                            note = if (itemObj.has("note")) itemObj.getString("note") else null
                        )
                    )
                }

                BlackboxReport(
                    eventId = root.optString("eventId", "BB_UNKNOWN"),
                    triggerTimestampMs = root.optLong("triggerTimestampMs", 0L),
                    videoFileName = root.optString("videoFileName", videoFile.name),
                    eventType = root.optString("eventType", "LOCK"),
                    triggerReason = root.optString("triggerReason", "INCIDENT"),
                    egoSpeedAtImpactKmh = root.optDouble("egoSpeedAtImpactKmh", 0.0).toFloat(),
                    targetSpeedAtImpactKmh = if (root.has("targetSpeedAtImpactKmh")) root.getDouble("targetSpeedAtImpactKmh").toFloat() else null,
                    relativeSpeedAtImpactKmh = if (root.has("relativeSpeedAtImpactKmh")) root.getDouble("relativeSpeedAtImpactKmh").toFloat() else null,
                    impactGForce = root.optDouble("impactGForce", 1.0).toFloat(),
                    scenarioZh = root.optString("scenarioZh", "事件鎖檔"),
                    scenarioEn = root.optString("scenarioEn", "Incident Lock"),
                    timeline = timeline
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
