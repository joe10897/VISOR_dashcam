package com.visor.dashcam.recording

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.visor.dashcam.MainActivity
import com.visor.dashcam.R
import com.visor.dashcam.VisorApp
import com.visor.dashcam.ai.adas.ADASAlert
import com.visor.dashcam.ai.adas.ADASManager
import com.visor.dashcam.ai.detector.Yolo26nDetector
import com.visor.dashcam.ai.model.DetectionResult
import com.visor.dashcam.sensors.TelemetryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DashcamRecordingService : LifecycleService() {

    private val binder = LocalBinder()
    private val recordingExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    lateinit var storageManager: StorageManager
        private set
    lateinit var telemetryManager: TelemetryManager
        private set
    lateinit var adasManager: ADASManager
        private set
    lateinit var yoloDetector: Yolo26nDetector
        private set

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var currentRecordingFile: File? = null
    private var currentSegmentStartTime: Long = 0L

    private val _recordingState = MutableStateFlow(RecordingState())
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _latestDetections = MutableStateFlow<List<DetectionResult>>(emptyList())
    val latestDetections: StateFlow<List<DetectionResult>> = _latestDetections.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    private var timerJob: Job? = null
    private val isRollingOver = AtomicBoolean(false)

    // Segment length: 60 seconds loop recording chunks
    private val segmentDurationMs: Long = 60_000L

    // Frame decimation: limit AI inference to ~8-10 FPS to prevent phone overheating
    private var lastInferenceTimestamp = 0L
    private val minInferenceIntervalMs = 100L // 10 FPS max

    inner class LocalBinder : Binder() {
        fun getService(): DashcamRecordingService = this@DashcamRecordingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as VisorApp
        storageManager = StorageManager(this, app.database.clipDao())
        adasManager = ADASManager(this)
        yoloDetector = Yolo26nDetector(this)

        telemetryManager = TelemetryManager(this) { gForce ->
            // On Emergency Impact detected by G-Sensor
            lockCurrentRecording("G_SENSOR_IMPACT (${String.format("%.2f", gForce)}G)")
        }

        acquireWakeLock()
        telemetryManager.start()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VISOR:DashcamRecordingWakeLock"
        ).apply {
            acquire(12 * 60 * 60 * 1000L) // 12 hours max
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startDashcam()
            ACTION_STOP -> stopDashcam()
            ACTION_LOCK -> lockCurrentRecording("MANUAL_USER_LOCK")
        }
        return START_STICKY
    }

    private fun startDashcam() {
        startForegroundServiceNotification()
        setupCamera()
        startSegmentTimer()
    }

    private fun startForegroundServiceNotification() {
        val notification = buildRecordingNotification("錄影中 - AI 離線監控運行中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildRecordingNotification(content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingLaunch = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val lockIntent = Intent(this, DashcamRecordingService::class.java).apply {
            action = ACTION_LOCK
        }
        val pendingLock = PendingIntent.getService(
            this,
            1,
            lockIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, VisorApp.CHANNEL_RECORDING_ID)
            .setContentTitle("VISOR 完全離線 AI 行車記錄器")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pendingLaunch)
            .addAction(android.R.drawable.ic_lock_lock, "緊急鎖檔", pendingLock)
            .build()
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        // 1. VideoCapture UseCase for H.264 loop recording
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD)) // 720p or 1080p for efficiency & heat balance
            .setExecutor(recordingExecutor)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        // 2. ImageAnalysis UseCase for Offline YOLO26n AI Inference
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            processAnalysisFrame(imageProxy)
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.bindToLifecycle(
                this,
                cameraSelector,
                videoCapture,
                imageAnalysis
            )
            startNextVideoSegment()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processAnalysisFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastInferenceTimestamp < minInferenceIntervalMs) {
            imageProxy.close()
            return
        }
        lastInferenceTimestamp = now

        try {
            // Convert to Bitmap for YOLO26n
            val bitmap = imageProxy.toBitmap()
            val detections = yoloDetector.detect(bitmap)

            // Feed detections & GPS telemetry to ADAS Engine
            val speed = telemetryManager.telemetry.value.speedKmh
            val analyzedDetections = adasManager.processFrame(detections, speed)

            _latestDetections.value = analyzedDetections

            // Auto-lock on critical FCW collision warning
            if (adasManager.currentAlert.value?.type == com.visor.dashcam.ai.adas.ADASAlertType.FCW_CRITICAL) {
                lockCurrentRecording("AI_FCW_COLLISION_ALERT")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNextVideoSegment() {
        val cap = videoCapture ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            // Check storage and purge oldest clips if needed
            storageManager.checkAndPurgeOldestClips()

            val targetFile = storageManager.createNewVideoFile()
            currentRecordingFile = targetFile
            currentSegmentStartTime = System.currentTimeMillis()

            val outputOptions = FileOutputOptions.Builder(targetFile).build()

            activeRecording = cap.output
                .prepareRecording(this@DashcamRecordingService, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this@DashcamRecordingService)) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            _recordingState.value = _recordingState.value.copy(
                                isRecording = true,
                                isCurrentClipLocked = false,
                                lockReason = null,
                                totalRecordedSegments = _recordingState.value.totalRecordedSegments + 1
                            )
                        }
                        is VideoRecordEvent.Finalize -> {
                            handleSegmentFinalized(recordEvent, targetFile)
                        }
                    }
                }
        }
    }

    private fun handleSegmentFinalized(event: VideoRecordEvent.Finalize, file: File) {
        val endTime = System.currentTimeMillis()
        val isLocked = _recordingState.value.isCurrentClipLocked
        val lockReason = _recordingState.value.lockReason
        val telemetry = telemetryManager.telemetry.value

        lifecycleScope.launch(Dispatchers.IO) {
            if (!event.hasError() && file.exists() && file.length() > 0) {
                storageManager.saveClipRecord(
                    file = file,
                    startTime = currentSegmentStartTime,
                    endTime = endTime,
                    maxSpeed = telemetry.speedKmh,
                    avgSpeed = telemetry.speedKmh,
                    startLat = telemetry.latitude,
                    startLng = telemetry.longitude,
                    endLat = telemetry.latitude,
                    endLng = telemetry.longitude,
                    isLocked = isLocked,
                    lockReason = lockReason
                )
            }

            // If still recording, kick off next segment in loop
            if (_recordingState.value.isRecording && !isRollingOver.get()) {
                startNextVideoSegment()
            }
        }
    }

    private fun startSegmentTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (true) {
                delay(1000L)
                val elapsedSeconds = ((System.currentTimeMillis() - currentSegmentStartTime) / 1000).toInt()
                _recordingState.value = _recordingState.value.copy(
                    currentSegmentDurationSeconds = elapsedSeconds
                )

                // Rollover when segment reaches duration limit (e.g. 60 seconds)
                if (elapsedSeconds >= (segmentDurationMs / 1000L)) {
                    rolloverSegment()
                }
            }
        }
    }

    private fun rolloverSegment() {
        isRollingOver.set(true)
        activeRecording?.stop()
        isRollingOver.set(false)
        startNextVideoSegment()
    }

    fun lockCurrentRecording(reason: String) {
        _recordingState.value = _recordingState.value.copy(
            isCurrentClipLocked = true,
            lockReason = reason
        )
    }

    private fun stopDashcam() {
        timerJob?.cancel()
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        _recordingState.value = _recordingState.value.copy(isRecording = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        telemetryManager.stop()
        adasManager.release()
        yoloDetector.close()
        recordingExecutor.shutdown()
        analysisExecutor.shutdown()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    companion object {
        const val ACTION_START = "com.visor.dashcam.action.START"
        const val ACTION_STOP = "com.visor.dashcam.action.STOP"
        const val ACTION_LOCK = "com.visor.dashcam.action.LOCK"
        private const val NOTIFICATION_ID = 1001
    }
}
