package com.visor.dashcam.recording

data class RecordingState(
    val isRecording: Boolean = false,
    val isCurrentClipLocked: Boolean = false,
    val lockReason: String? = null,
    val currentSegmentDurationSeconds: Int = 0,
    val totalRecordedSegments: Int = 0,
    val availableStorageGb: Float = 0f
)
