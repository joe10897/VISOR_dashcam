package com.visor.dashcam.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VideoQualityOption(val label: String) {
    HD_720P("720p HD (省電防過熱推薦)"),
    FHD_1080P("1080p Full HD (高畫質)")
}

enum class ImpactSensitivity(val label: String, val thresholdG: Float) {
    OFF("關閉", 999f),
    LOW("低 (3.5G - 僅劇烈撞擊)", 3.5f),
    MEDIUM("標準 (2.8G - 一般碰撞)", 2.8f),
    HIGH("高 (2.0G - 靈敏觸發)", 2.0f)
}

enum class FcwSensitivity(val label: String, val warningTtcSec: Float, val criticalTtcSec: Float) {
    CONSERVATIVE("保守 (較晚提醒)", 1.8f, 1.1f),
    STANDARD("標準 (推薦)", 2.4f, 1.4f),
    SENSITIVE("靈敏 (提早預警)", 3.0f, 1.8f)
}

enum class SpeedUnit(val label: String, val factorFromKmh: Float) {
    KMH("KM/H (公制)", 1.0f),
    MPH("MPH (英制)", 0.621371f)
}

enum class AppLanguage(val code: String, val label: String) {
    ZH_TW("zh_TW", "繁體中文"),
    EN("en", "English")
}

data class DashcamSettings(
    // 0. 語言設定 (Language)
    val language: AppLanguage = AppLanguage.ZH_TW,
    // 1. 儲存與循環錄影設定 (Storage & Recording)
    val maxStorageQuotaGb: Int = 8,                 // 最大佔用空間上限 (GB): 2, 4, 8, 16, 32, 64
    val minFreeSpaceMb: Int = 1024,                 // 系統最低保留剩餘空間 (MB)
    val segmentDurationSeconds: Int = 60,           // 單段循環錄影時長 (秒): 60, 180, 300
    val videoQuality: VideoQualityOption = VideoQualityOption.HD_720P,
    val recordAudio: Boolean = true,                // 是否錄製車內聲音
    val burnInVideoWatermark: Boolean = true,       // 直接將浮水印硬烙印在 .mp4 影片畫面中

    // 2. AI 與 ADAS 設定 (AI & ADAS)
    val aiEnabled: Boolean = true,                  // AI 辨識總開關
    val fcwEnabled: Boolean = true,                 // 前車防撞預警 (FCW)
    val fcwSensitivity: FcwSensitivity = FcwSensitivity.STANDARD,
    val lvdaEnabled: Boolean = true,                // 前車起步提醒 (LVDA)
    val hmwEnabled: Boolean = true,                 // 車距過近提示 (HMW)
    val alertSoundEnabled: Boolean = true,          // 警示蜂鳴音開關
    val aiTargetFps: Int = 10,                      // AI 推論目標幀率: 5, 10, 15 FPS
    val aiConfidenceThreshold: Float = 0.35f,       // AI 辨識信心度門檻
    val useNnapiAcceleration: Boolean = false,      // NNAPI 硬體加速 (預設關閉以確保各機型穩定)

    // 3. 感測器與顯示設定 (Sensors & Display)
    val impactSensitivity: ImpactSensitivity = ImpactSensitivity.MEDIUM,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val cameraMountHeightMeters: Float = 1.30f      // 鏡頭安裝高度 (公尺): 1.15(轎車), 1.35(SUV), 1.65(貨車)
)

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("visor_dashcam_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadFromPrefs())
    val settings: StateFlow<DashcamSettings> = _settings.asStateFlow()

    private fun loadFromPrefs(): DashcamSettings {
        val qualityName = prefs.getString("video_quality", VideoQualityOption.HD_720P.name)
        val impactName = prefs.getString("impact_sensitivity", ImpactSensitivity.MEDIUM.name)
        val fcwSensName = prefs.getString("fcw_sensitivity", FcwSensitivity.STANDARD.name)
        val speedUnitName = prefs.getString("speed_unit", SpeedUnit.KMH.name)
        val languageName = prefs.getString("app_language", AppLanguage.ZH_TW.name)

        return DashcamSettings(
            language = runCatching { AppLanguage.valueOf(languageName!!) }.getOrDefault(AppLanguage.ZH_TW),
            maxStorageQuotaGb = prefs.getInt("max_storage_quota_gb", 8),
            minFreeSpaceMb = prefs.getInt("min_free_space_mb", 1024),
            segmentDurationSeconds = prefs.getInt("segment_duration_sec", 60),
            videoQuality = runCatching { VideoQualityOption.valueOf(qualityName!!) }.getOrDefault(VideoQualityOption.HD_720P),
            recordAudio = prefs.getBoolean("record_audio", true),
            burnInVideoWatermark = prefs.getBoolean("burn_in_video_watermark", true),
            aiEnabled = prefs.getBoolean("ai_enabled", true),
            fcwEnabled = prefs.getBoolean("fcw_enabled", true),
            fcwSensitivity = runCatching { FcwSensitivity.valueOf(fcwSensName!!) }.getOrDefault(FcwSensitivity.STANDARD),
            lvdaEnabled = prefs.getBoolean("lvda_enabled", true),
            hmwEnabled = prefs.getBoolean("hmw_enabled", true),
            alertSoundEnabled = prefs.getBoolean("alert_sound_enabled", true),
            aiTargetFps = prefs.getInt("ai_target_fps", 10),
            aiConfidenceThreshold = prefs.getFloat("ai_confidence_threshold", 0.35f),
            useNnapiAcceleration = prefs.getBoolean("use_nnapi", false),
            impactSensitivity = runCatching { ImpactSensitivity.valueOf(impactName!!) }.getOrDefault(ImpactSensitivity.MEDIUM),
            speedUnit = runCatching { SpeedUnit.valueOf(speedUnitName!!) }.getOrDefault(SpeedUnit.KMH),
            cameraMountHeightMeters = prefs.getFloat("camera_mount_height_m", 1.30f)
        )
    }

    fun updateSettings(transform: (DashcamSettings) -> DashcamSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs.edit().apply {
            putString("app_language", updated.language.name)
            putInt("max_storage_quota_gb", updated.maxStorageQuotaGb)
            putInt("min_free_space_mb", updated.minFreeSpaceMb)
            putInt("segment_duration_sec", updated.segmentDurationSeconds)
            putString("video_quality", updated.videoQuality.name)
            putBoolean("record_audio", updated.recordAudio)
            putBoolean("burn_in_video_watermark", updated.burnInVideoWatermark)
            putBoolean("ai_enabled", updated.aiEnabled)
            putBoolean("fcw_enabled", updated.fcwEnabled)
            putString("fcw_sensitivity", updated.fcwSensitivity.name)
            putBoolean("lvda_enabled", updated.lvdaEnabled)
            putBoolean("hmw_enabled", updated.hmwEnabled)
            putBoolean("alert_sound_enabled", updated.alertSoundEnabled)
            putInt("ai_target_fps", updated.aiTargetFps)
            putFloat("ai_confidence_threshold", updated.aiConfidenceThreshold)
            putBoolean("use_nnapi", updated.useNnapiAcceleration)
            putString("impact_sensitivity", updated.impactSensitivity.name)
            putString("speed_unit", updated.speedUnit.name)
            putFloat("camera_mount_height_m", updated.cameraMountHeightMeters)
            apply()
        }
    }
}
