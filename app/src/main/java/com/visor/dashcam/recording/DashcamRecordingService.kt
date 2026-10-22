package com.visor.dashcam.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.visor.dashcam.MainActivity
import com.visor.dashcam.VisorApp
import com.visor.dashcam.ai.adas.ADASAlertType
import com.visor.dashcam.ai.adas.ADASManager
import com.visor.dashcam.ai.detector.Yolo26nDetector
import com.visor.dashcam.ai.model.DetectionResult
import com.visor.dashcam.data.repository.SettingsRepository
import com.visor.dashcam.data.repository.VideoQualityOption
import com.visor.dashcam.sensors.TelemetryManager
import com.visor.dashcam.sensors.ThermalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DashcamRecordingService : LifecycleService() {

    private val binder = LocalBinder()
    private val recordingExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var storageManager: StorageManager
        private set
    lateinit var telemetryManager: TelemetryManager
        private set
    lateinit var adasManager: ADASManager
        private set
    lateinit var yoloDetector: Yolo26nDetector
        private set
    lateinit var thermalManager: ThermalManager
        private set

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var overlayEffect: OverlayEffect? = null
    private var overlayThread: HandlerThread? = null
    private var overlayHandler: Handler? = null
    private var pendingSurfaceProvider: Preview.SurfaceProvider? = null

    private var activeRecording: Recording? = null
    private var currentRecordingFile: File? = null
    private var currentSegmentStartTime: Long = 0L
    private var segmentMaxSpeedKmh: Float = 0f

    private val _recordingState = MutableStateFlow(RecordingState())
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _latestDetections = MutableStateFlow<List<DetectionResult>>(emptyList())
    val latestDetections: StateFlow<List<DetectionResult>> = _latestDetections.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    private var timerJob: Job? = null
    private val shouldKeepRecording = AtomicBoolean(false)
    private val isStartingSegment = AtomicBoolean(false)
    private var isCameraBound = false

    private var lastInferenceTimestamp = 0L

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
        settingsRepository = app.settingsRepository
        storageManager = StorageManager(this, app.database.clipDao(), settingsRepository)
        adasManager = ADASManager(this)
        yoloDetector = Yolo26nDetector(this)
        thermalManager = ThermalManager(this)
        thermalManager.start(settingsRepository.settings.value.aiTargetFps)

        telemetryManager = TelemetryManager(this, settingsRepository) { gForce ->
            lockCurrentRecording("G_SENSOR_IMPACT (${String.format(Locale.US, "%.2f", gForce)}G)")
        }

        acquireWakeLock()
        telemetryManager.start()

        val oThread = HandlerThread("Visor-OverlayEffect-GL").apply { start() }
        overlayThread = oThread
        overlayHandler = Handler(oThread.looper)

        // Warm up YOLO detector on background thread so Main Thread is never blocked
        analysisExecutor.execute {
            val useNnapi = settingsRepository.settings.value.useNnapiAcceleration
            yoloDetector.ensureInitialized(useNnapi)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VISOR:DashcamRecordingWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START, null -> startDashcam()
            ACTION_STOP -> stopDashcam()
            ACTION_LOCK -> lockCurrentRecording("MANUAL_USER_LOCK")
        }
        return START_STICKY
    }

    private fun startDashcam() {
        startForegroundServiceNotification()
        shouldKeepRecording.set(true)
        if (!isCameraBound) {
            setupCamera()
        } else if (activeRecording == null && !isStartingSegment.get()) {
            startNextVideoSegment()
        }
        startSegmentTimer()
    }

    private fun startForegroundServiceNotification() {
        val notification = buildRecordingNotification("錄影中 - AI 離線監控運行中")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var serviceTypes = 0
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                ) {
                    serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }

                if (serviceTypes != 0) {
                    startForeground(NOTIFICATION_ID, notification, serviceTypes)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {
            }
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

    /**
     * Attaches or detaches the UI PreviewView's SurfaceProvider without unbinding VideoCapture!
     */
    fun attachPreviewSurface(surfaceProvider: Preview.SurfaceProvider?) {
        pendingSurfaceProvider = surfaceProvider
        ContextCompat.getMainExecutor(this).execute {
            try {
                previewUseCase?.setSurfaceProvider(surfaceProvider)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Rebinds camera if user changes video resolution in Settings.
     */
    fun restartCameraWithNewSettings() {
        ContextCompat.getMainExecutor(this).execute {
            activeRecording?.stop()
            activeRecording = null
            bindCameraUseCases()
        }
    }

    private fun setupCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // Check if device/emulator actually has physical or virtual cameras
        val cameraManager = getSystemService(CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
        val cameraIds = cameraManager?.cameraIdList ?: emptyArray()
        if (cameraIds.isEmpty()) {
            _recordingState.value = _recordingState.value.copy(
                cameraErrorMessage = "未偵測到可用相機 (Available cameras: 0)。\n若使用模擬器，請在 Android Studio Device Manager 編輯裝置 -> Show Advanced Settings -> 將 Camera (Back) 設為 VirtualScene 或 Webcam0 後重新啟動模擬器。"
            )
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                e.printStackTrace()
                _recordingState.value = _recordingState.value.copy(
                    cameraErrorMessage = "相機服務初始化失敗: ${e.localizedMessage ?: "請檢查裝置相機設定"}"
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Dynamically select available camera (Back preferred, fallback to Front if Back is missing)
        val cameraSelector = when {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> {
                _recordingState.value = _recordingState.value.copy(
                    cameraErrorMessage = "未找到可用的相機鏡頭。若使用模擬器，請確認 Camera 已開啟。"
                )
                return
            }
        }

        val currentSettings = settingsRepository.settings.value

        // 1. Preview UseCase (Owned by Service so UI navigation never interrupts recording)
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(pendingSurfaceProvider)
        }
        previewUseCase = preview

        // 2. VideoCapture UseCase
        val preferredQuality = when (currentSettings.videoQuality) {
            VideoQualityOption.FHD_1080P -> Quality.FHD
            VideoQualityOption.HD_720P -> Quality.HD
        }
        val qualitySelector = QualitySelector.from(
            preferredQuality,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .setExecutor(recordingExecutor)
            .build()
        val videoCap = VideoCapture.withOutput(recorder)
        videoCapture = videoCap

        // 3. ImageAnalysis UseCase for Offline YOLO AI Inference
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetResolution(android.util.Size(640, 360))
            .build()

        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            processAnalysisFrame(imageProxy)
        }
        imageAnalysis = analysis

        try {
            overlayEffect?.close()
        } catch (_: Exception) {}
        overlayEffect = null

        try {
            if (currentSettings.burnInVideoWatermark) {
                val effect = createWatermarkOverlayEffect()
                overlayEffect = effect
                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(videoCap)
                    .addUseCase(analysis)
                    .addEffect(effect)
                    .build()
                provider.bindToLifecycle(this, cameraSelector, useCaseGroup)
            } else {
                provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    videoCap,
                    analysis
                )
            }
            isCameraBound = true
            _recordingState.value = _recordingState.value.copy(cameraErrorMessage = null)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback without effect if hardware/emulator doesn't support GL surface processor
            try {
                overlayEffect?.close()
            } catch (_: Exception) {}
            overlayEffect = null

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    videoCap,
                    analysis
                )
                isCameraBound = true
                _recordingState.value = _recordingState.value.copy(cameraErrorMessage = null)
            } catch (e2: Exception) {
                // Fallback for legacy Camera2 devices that only support 2 simultaneous streams
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        videoCap
                    )
                    isCameraBound = true
                    _recordingState.value = _recordingState.value.copy(cameraErrorMessage = null)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    isCameraBound = false
                    _recordingState.value = _recordingState.value.copy(
                        cameraErrorMessage = "綁定相機串流失敗: ${ex.localizedMessage}"
                    )
                }
            }
        }

        if (isCameraBound && shouldKeepRecording.get()) {
            startNextVideoSegment()
        }
    }

    private fun getOverlayHandler(): Handler {
        var thread = overlayThread
        if (thread == null || !thread.isAlive) {
            thread = HandlerThread("Visor-OverlayEffect-GL").apply { start() }
            overlayThread = thread
            overlayHandler = Handler(thread.looper)
        }
        return overlayHandler ?: Handler(Looper.getMainLooper())
    }

    private fun createWatermarkOverlayEffect(): OverlayEffect {
        val bgHandler = getOverlayHandler()
        val effect = OverlayEffect(
            CameraEffect.VIDEO_CAPTURE,
            2,
            bgHandler
        ) { throwable ->
            throwable.printStackTrace()
        }

        val watermarkTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val textPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        val bgPaint = Paint().apply {
            color = Color.argb(125, 0, 0, 0)
            style = Paint.Style.FILL
        }

        effect.setOnDrawListener { frame ->
            try {
                val canvas = frame.overlayCanvas
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                val width = frame.size.width.toFloat()
                val height = frame.size.height.toFloat()

                // Dynamically scale text size according to video resolution (e.g. 720p vs 1080p)
                val textSize = (height * 0.030f).coerceIn(20f, 36f)
                textPaint.textSize = textSize

                val telemetry = telemetryManager.telemetry.value
                val now = System.currentTimeMillis()
                val timeStr = watermarkTimeFormat.format(Date(now))
                val speedStr = String.format(Locale.US, "%3.0f KM/H", telemetry.speedKmh)
                val compassStr = "${telemetry.headingDegrees.toInt()}°"
                val gStr = String.format(Locale.US, "%.2fG", telemetry.gForceTotal)
                val gpsStr = if (telemetry.latitude != null && telemetry.longitude != null &&
                    (telemetry.latitude != 0.0 || telemetry.longitude != 0.0)
                ) {
                    String.format(Locale.US, "GPS: %.5f, %.5f", telemetry.latitude, telemetry.longitude)
                } else {
                    "GPS: 定位中"
                }

                val line1 = "VISOR AI DASHCAM   $timeStr"
                val line2 = "時速: $speedStr | 方位: $compassStr | 重力: $gStr | $gpsStr"

                val bannerHeight = textSize * 2.8f
                val margin = 20f
                val bannerTop = height - bannerHeight - margin

                // Draw background banner
                canvas.drawRect(margin, bannerTop, width - margin, height - margin, bgPaint)

                // Draw Watermark text
                canvas.drawText(line1, margin + 16f, bannerTop + textSize * 1.15f, textPaint)
                canvas.drawText(line2, margin + 16f, bannerTop + textSize * 2.35f, textPaint)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            true
        }

        return effect
    }

    private fun processAnalysisFrame(imageProxy: ImageProxy) {
        val currentSettings = settingsRepository.settings.value
        val thermal = thermalManager.thermalState.value

        // If AI is disabled or phone is in critical overheat state (>= 47°C), skip inference
        if (!currentSettings.aiEnabled || thermal.isCritical) {
            if (_latestDetections.value.isNotEmpty()) {
                _latestDetections.value = emptyList()
                adasManager.clearAlerts()
            }
            imageProxy.close()
            return
        }

        // Dynamic Thermal Throttling: Automatically scales FPS from 10/15 down to 4 FPS when warm
        val effectiveFps = thermal.effectiveAiFps.coerceIn(1, 30)
        val minIntervalMs = (1000L / effectiveFps)
        val now = System.currentTimeMillis()
        if (now - lastInferenceTimestamp < minIntervalMs) {
            imageProxy.close()
            return
        }
        lastInferenceTimestamp = now

        var bitmap: android.graphics.Bitmap? = null
        try {
            bitmap = imageProxy.toBitmap()
            val detections = yoloDetector.detect(
                bitmap = bitmap,
                confidenceThreshold = currentSettings.aiConfidenceThreshold,
                useNnapi = currentSettings.useNnapiAcceleration
            )

            val speed = telemetryManager.telemetry.value.speedKmh
            val analyzedDetections = adasManager.processFrame(detections, speed, currentSettings)

            _latestDetections.value = analyzedDetections

            if (adasManager.currentAlert.value?.type == ADASAlertType.FCW_CRITICAL) {
                lockCurrentRecording("AI_FCW_COLLISION_ALERT")
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            try {
                bitmap?.recycle()
            } catch (_: Exception) {
            }
            imageProxy.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNextVideoSegment() {
        val cap = videoCapture ?: return
        if (!shouldKeepRecording.get()) return
        if (!isStartingSegment.compareAndSet(false, true)) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Enforce max storage quota & min free space before creating new segment
                storageManager.checkAndPurgeOldestClips()
                val availGb = storageManager.getAvailableDeviceStorageGb()

                val targetFile = storageManager.createNewVideoFile()
                currentRecordingFile = targetFile
                currentSegmentStartTime = System.currentTimeMillis()
                segmentMaxSpeedKmh = telemetryManager.telemetry.value.speedKmh

                val outputBuilder = FileOutputOptions.Builder(targetFile)
                // Embed GPS location directly into MP4 container metadata if available
                telemetryManager.lastKnownLocation?.let { loc ->
                    outputBuilder.setLocation(loc)
                }
                val outputOptions = outputBuilder.build()

                val currentSettings = settingsRepository.settings.value
                val canRecordAudio = currentSettings.recordAudio &&
                    ContextCompat.checkSelfPermission(
                        this@DashcamRecordingService,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                withContext(Dispatchers.Main) {
                    val pendingRecording = cap.output.prepareRecording(this@DashcamRecordingService, outputOptions)
                    if (canRecordAudio) {
                        pendingRecording.withAudioEnabled()
                    }

                    activeRecording = pendingRecording.start(
                        ContextCompat.getMainExecutor(this@DashcamRecordingService)
                    ) { recordEvent ->
                        when (recordEvent) {
                            is VideoRecordEvent.Start -> {
                                currentSegmentStartTime = System.currentTimeMillis()
                                isStartingSegment.set(false)
                                _recordingState.value = _recordingState.value.copy(
                                    isRecording = true,
                                    isCurrentClipLocked = false,
                                    lockReason = null,
                                    currentSegmentDurationSeconds = 0,
                                    totalRecordedSegments = _recordingState.value.totalRecordedSegments + 1,
                                    availableStorageGb = availGb
                                )
                            }
                            is VideoRecordEvent.Finalize -> {
                                isStartingSegment.set(false)
                                activeRecording = null
                                handleSegmentFinalized(recordEvent, targetFile)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isStartingSegment.set(false)
            }
        }
    }

    private fun handleSegmentFinalized(event: VideoRecordEvent.Finalize, file: File) {
        val endTime = System.currentTimeMillis()
        val startTime = if (currentSegmentStartTime > 0L) currentSegmentStartTime else (endTime - 1000L)
        val isLocked = _recordingState.value.isCurrentClipLocked
        val lockReason = _recordingState.value.lockReason
        val telemetry = telemetryManager.telemetry.value
        val maxSpeed = maxOf(segmentMaxSpeedKmh, telemetry.speedKmh)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (file.exists() && file.length() > 0L) {
                    storageManager.saveClipRecord(
                        file = file,
                        startTime = startTime,
                        endTime = endTime,
                        maxSpeed = maxSpeed,
                        avgSpeed = telemetry.speedKmh,
                        startLat = telemetry.latitude,
                        startLng = telemetry.longitude,
                        endLat = telemetry.latitude,
                        endLng = telemetry.longitude,
                        isLocked = isLocked,
                        lockReason = lockReason
                    )
                } else if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Seamlessly kick off the next segment if continuous loop recording is still active
            if (shouldKeepRecording.get()) {
                startNextVideoSegment()
            }
        }
    }

    private fun startSegmentTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (true) {
                delay(1000L)
                if (!_recordingState.value.isRecording || currentSegmentStartTime == 0L) {
                    continue
                }

                val currentTelemetry = telemetryManager.telemetry.value
                val currentSpeed = currentTelemetry.speedKmh
                if (currentSpeed > segmentMaxSpeedKmh) {
                    segmentMaxSpeedKmh = currentSpeed
                }

                val elapsedSeconds = ((System.currentTimeMillis() - currentSegmentStartTime) / 1000L)
                    .toInt()
                    .coerceAtLeast(0)

                _recordingState.value = _recordingState.value.copy(
                    currentSegmentDurationSeconds = elapsedSeconds,
                    availableStorageGb = storageManager.getAvailableDeviceStorageGb()
                )

                val targetSegmentDurationSec = settingsRepository.settings.value.segmentDurationSeconds
                // When segment reaches user-configured duration (e.g. 60s / 180s / 300s),
                // stop current recording; VideoRecordEvent.Finalize will automatically start the next segment!
                if (elapsedSeconds >= targetSegmentDurationSec && activeRecording != null) {
                    try {
                        activeRecording?.stop()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun lockCurrentRecording(reason: String) {
        _recordingState.value = _recordingState.value.copy(
            isCurrentClipLocked = true,
            lockReason = reason
        )
    }

    fun stopDashcam() {
        shouldKeepRecording.set(false)
        timerJob?.cancel()
        try {
            activeRecording?.stop()
        } catch (_: Exception) {
        }
        activeRecording = null
        try {
            overlayEffect?.close()
        } catch (_: Exception) {}
        overlayEffect = null
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        isCameraBound = false
        _recordingState.value = _recordingState.value.copy(isRecording = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shouldKeepRecording.set(false)
        timerJob?.cancel()
        try {
            overlayEffect?.close()
        } catch (_: Exception) {}
        overlayEffect = null
        thermalManager.stop()
        telemetryManager.stop()
        adasManager.release()
        analysisExecutor.execute {
            yoloDetector.close()
        }
        recordingExecutor.shutdown()
        analysisExecutor.shutdown()
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.visor.dashcam.action.START"
        const val ACTION_STOP = "com.visor.dashcam.action.STOP"
        const val ACTION_LOCK = "com.visor.dashcam.action.LOCK"
        private const val NOTIFICATION_ID = 1001
    }
}
