package com.visor.dashcam.ui.i18n

import com.visor.dashcam.data.repository.AppLanguage
import com.visor.dashcam.data.repository.FcwSensitivity
import com.visor.dashcam.data.repository.ImpactSensitivity
import com.visor.dashcam.data.repository.SpeedUnit
import com.visor.dashcam.data.repository.VideoQualityOption

data class VisorI18nStrings(
    // Common & Actions
    val back: String,
    val cancel: String,
    val confirm: String,
    val delete: String,
    val selectAll: String,
    val lock: String,
    val unlock: String,

    // HUD Top Bar & Actions
    val pureHud: String,
    val amoledSaver: String,
    val clips: String,
    val settings: String,
    val powerOff: String,
    val recordingActive: String,

    // HUD Alerts & Dialog
    val shutdownConfirmTitle: String,
    val shutdownConfirmMsg: String,
    val shutdownSavingMsg: String,
    val confirmShutdown: String,
    val manualLock: String,
    val lockedProtected: String,
    val noCameraTitle: String,
    val noCameraDesc: String,
    val gpsAcquiring: String,

    // Settings Screen
    val settingsTitle: String,
    val languageSection: String,
    val languageTitle: String,
    val languageDesc: String,
    
    // Settings - Storage
    val storageSection: String,
    val storageUsed: String,
    val deviceFree: String,
    val clipSummary: String,
    val maxQuotaTitle: String,
    val minFreeSpaceTitle: String,
    val clearUnlockedButton: String,
    val clearConfirmTitle: String,
    val clearConfirmMsg: String,
    val confirmClear: String,

    // Settings - Recording
    val recordingSection: String,
    val segmentDurationTitle: String,
    val videoQualityTitle: String,
    val cabinAudioTitle: String,
    val cabinAudioSub: String,
    val watermarkTitle: String,
    val watermarkSub: String,

    // Settings - AI & ADAS
    val aiSection: String,
    val enableYoloTitle: String,
    val enableYoloSub: String,
    val fcwTitle: String,
    val fcwSub: String,
    val fcwSensitivityTitle: String,
    val distanceCalibrationTitle: String,
    val distanceCalibrationSub: String,
    val lvdaTitle: String,
    val lvdaSub: String,
    val hmwTitle: String,
    val hmwSub: String,
    val beepSoundTitle: String,
    val beepSoundSub: String,
    val targetFpsTitle: String,
    val nnapiTitle: String,
    val nnapiSub: String,

    // Settings - Sensors & Vehicle
    val sensorSection: String,
    val impactSensitivityTitle: String,
    val vehicleMountHeightTitle: String,
    val speedUnitTitle: String,

    // Clip Library
    val libraryTitle: String,
    val noClipsFound: String,
    val filterAll: String,
    val filterLocked: String,
    val filterNormal: String,

    // Blackbox Forensics
    val blackboxAnalysisButton: String,
    val blackboxReportTitle: String,
    val blackboxEgoSpeed: String,
    val blackboxTargetSpeed: String,
    val blackboxRelativeSpeed: String,
    val blackboxImpactG: String,
    val blackboxTimelineTitle: String,
    val blackboxScenarioTitle: String
)

object VisorStrings {
    private val zhTw = VisorI18nStrings(
        back = "返回",
        cancel = "取消",
        confirm = "確認",
        delete = "刪除",
        selectAll = "全選",
        lock = "鎖定保護",
        unlock = "解鎖",

        pureHud = "純淨車載模式",
        amoledSaver = "節能息屏",
        clips = "影片庫",
        settings = "系統設定",
        powerOff = "安全關機退出",
        recordingActive = "錄影中",

        shutdownConfirmTitle = "確認關閉行車記錄器？",
        shutdownConfirmMsg = "系統將安全存檔當前影片段落，釋放相機與感測器資源並退出應用程式。",
        shutdownSavingMsg = "正在安全存檔當前錄製片段並退出...",
        confirmShutdown = "安全關機",
        manualLock = "手動鎖檔",
        lockedProtected = "已鎖定保護",
        noCameraTitle = "未啟用可用鏡頭",
        noCameraDesc = "請確認相機權限或裝置鏡頭狀態",
        gpsAcquiring = "GPS: 定位中",

        settingsTitle = "系統與儲存設定",
        languageSection = "語言與介面設定",
        languageTitle = "系統語言 (Language)",
        languageDesc = "切換繁體中文或英文介面",

        storageSection = "儲存空間與循環覆蓋配額",
        storageUsed = "已使用 %s / %s 上限",
        deviceFree = "手機剩餘: %s",
        clipSummary = "共 %d 段影片 (其中已鎖定保護 %d 段, 佔 %s)",
        maxQuotaTitle = "行車記錄器最大佔用空間上限 (超過時自動循環覆蓋最舊未鎖定影片)",
        minFreeSpaceTitle = "手機系統最低保留空間 (低於此值時強制清理舊檔)",
        clearUnlockedButton = "一鍵清理所有未鎖定影片 (保留鎖檔)",
        clearConfirmTitle = "確認清理未鎖定影片？",
        clearConfirmMsg = "此動作將刪除所有未鎖定的歷史循環錄影，釋放手機儲存空間。已鎖定的碰撞與事件保護影片將完整保留。",
        confirmClear = "確認清理",

        recordingSection = "循環錄影與畫質設定",
        segmentDurationTitle = "單段循環錄影時長",
        videoQualityTitle = "錄影解析度 (夏季高溫建議使用 720p HD 減少發熱)",
        cabinAudioTitle = "車內環境錄音",
        cabinAudioSub = "關閉後僅錄製無聲影像，保障車內談話隱私",
        watermarkTitle = "MP4 影片畫面硬烙印浮水印",
        watermarkSub = "直接將即時時間、車速、方位與 GPS 座標燒錄於 .mp4 影片中（推薦開啟）",

        aiSection = "離線 AI 與 ADAS 駕駛輔助",
        enableYoloTitle = "啟用離線 AI 視覺辨識 (YOLO)",
        enableYoloSub = "完全於本機運行車輛與行人偵測，零雲端傳輸",
        fcwTitle = "前車防撞預警 (FCW)",
        fcwSub = "計算碰撞時間 (TTC)，前車急煞或過快接近時發出警報且自動鎖檔",
        fcwSensitivityTitle = "FCW 預警靈敏度",
        distanceCalibrationTitle = "前車預警距離微調 (測距係數)",
        distanceCalibrationSub = "微調測距與防撞預警時機（0.8x 較近 ~ 1.2x 較遠提早），採即時純量乘法，零效能損耗",
        lvdaTitle = "前車起步提醒 (LVDA)",
        lvdaSub = "停等紅燈超過 3 秒後，若前車駛離則發出提示音",
        hmwTitle = "高速車距過近警示 (HMW)",
        hmwSub = "時速高於 50 km/h 且跟車時距小於 0.8 秒時提醒",
        beepSoundTitle = "警示蜂鳴提示音",
        beepSoundSub = "觸發 FCW/LVDA/HMW 時播放蜂鳴聲提醒駕駛",
        targetFpsTitle = "AI 目標推論幀率 (高溫自動動態降頻)",
        nnapiTitle = "NNAPI / NPU 硬體加速 (實驗性)",
        nnapiSub = "調用專屬晶片神經網路處理器（非所有機型相容）",

        sensorSection = "車身感測器與衝擊鎖檔設定",
        impactSensitivityTitle = "G-Sensor 碰撞自動緊急鎖檔靈敏度",
        vehicleMountHeightTitle = "車型與鏡頭安裝高度 (用於單目距離換算校正)",
        speedUnitTitle = "車速顯示單位",

        libraryTitle = "行車影像影片庫",
        noClipsFound = "尚無錄製影片",
        filterAll = "全部",
        filterLocked = "已鎖定",
        filterNormal = "一般",

        blackboxAnalysisButton = "事故黑盒子分析",
        blackboxReportTitle = "🛡️ 事故鑑識黑盒子報告",
        blackboxEgoSpeed = "自車初速",
        blackboxTargetSpeed = "目標車速",
        blackboxRelativeSpeed = "相對速差 (Delta-V)",
        blackboxImpactG = "衝擊 G 值",
        blackboxTimelineTitle = "碰撞前 3 秒時序軌跡",
        blackboxScenarioTitle = "事故研判情境"
    )

    private val en = VisorI18nStrings(
        back = "Back",
        cancel = "Cancel",
        confirm = "Confirm",
        delete = "Delete",
        selectAll = "Select All",
        lock = "Protect",
        unlock = "Unlock",

        pureHud = "Pure HUD",
        amoledSaver = "Screen Saver",
        clips = "Clips",
        settings = "Settings",
        powerOff = "Power Off / Exit",
        recordingActive = "REC",

        shutdownConfirmTitle = "Power Off Dashcam?",
        shutdownConfirmMsg = "The current video segment will be safely finalized. Camera and background services will be terminated.",
        shutdownSavingMsg = "Safely saving active video segment and exiting...",
        confirmShutdown = "Power Off",
        manualLock = "Emergency Lock",
        lockedProtected = "Protected",
        noCameraTitle = "No Camera Available",
        noCameraDesc = "Please check camera permissions or device camera state",
        gpsAcquiring = "GPS: Acquiring...",

        settingsTitle = "System & Storage Settings",
        languageSection = "Language & Interface",
        languageTitle = "System Language",
        languageDesc = "Toggle Traditional Chinese or English",

        storageSection = "Storage & Loop Overwrite Quota",
        storageUsed = "Used %s / %s Limit",
        deviceFree = "Device Free: %s",
        clipSummary = "Total %d clips (%d protected, %s)",
        maxQuotaTitle = "Max Storage Quota for VISOR (auto-overwrites oldest unlocked clips)",
        minFreeSpaceTitle = "Minimum Phone Storage to Preserve (triggers purge when low)",
        clearUnlockedButton = "Clear All Unlocked Clips (Keep Protected)",
        clearConfirmTitle = "Clear Unlocked Clips?",
        clearConfirmMsg = "This will permanently delete all unprotected historical loop clips to free up storage. Impact-locked clips will be safely kept.",
        confirmClear = "Confirm Clear",

        recordingSection = "Loop Recording & Video Quality",
        segmentDurationTitle = "Segment Duration",
        videoQualityTitle = "Video Resolution (720p HD recommended to prevent overheat)",
        cabinAudioTitle = "In-Cabin Audio Recording",
        cabinAudioSub = "Disable to record video silently, protecting cabin privacy",
        watermarkTitle = "Burn-In Video Telemetry Watermark",
        watermarkSub = "Embed live timestamp, speed, heading, and GPS directly into .mp4 (Recommended)",

        aiSection = "Offline AI & ADAS Assistance",
        enableYoloTitle = "Enable Offline AI Vision (YOLO)",
        enableYoloSub = "100% on-device vehicle & pedestrian detection, 0 cloud transmission",
        fcwTitle = "Forward Collision Warning (FCW)",
        fcwSub = "Calculates Time-to-Collision (TTC), alerts and auto-locks on rapid approach",
        fcwSensitivityTitle = "FCW Alert Sensitivity",
        distanceCalibrationTitle = "Warning Distance Fine-Tuning",
        distanceCalibrationSub = "Calibrates distance & collision timing (0.8x closer ~ 1.2x farther/earlier). Scalar multiplication with 0% extra overhead",
        lvdaTitle = "Lead Vehicle Departure Alert (LVDA)",
        lvdaSub = "Beeps when the stopped lead vehicle drives away after >3s",
        hmwTitle = "Headway Monitoring & Warning (HMW)",
        hmwSub = "Warns when speed > 50 km/h and headway is under 0.8s",
        beepSoundTitle = "Audio Beep Alerts",
        beepSoundSub = "Plays audio alerts when FCW/LVDA/HMW events trigger",
        targetFpsTitle = "AI Inference Target FPS (Dynamically scales with temperature)",
        nnapiTitle = "NNAPI / NPU Hardware Acceleration",
        nnapiSub = "Utilizes dedicated Neural Processing Unit (experimental)",

        sensorSection = "Vehicle Sensors & G-Sensor Auto-Lock",
        impactSensitivityTitle = "G-Sensor Crash Auto-Lock Sensitivity",
        vehicleMountHeightTitle = "Vehicle Type & Camera Height (for monocular distance)",
        speedUnitTitle = "Speed Display Unit",

        libraryTitle = "Recorded Clips Library",
        noClipsFound = "No clips recorded yet",
        filterAll = "All",
        filterLocked = "Protected",
        filterNormal = "Normal",

        blackboxAnalysisButton = "Blackbox Forensics",
        blackboxReportTitle = "🛡️ Forensic Blackbox Report",
        blackboxEgoSpeed = "Ego Speed",
        blackboxTargetSpeed = "Target Speed",
        blackboxRelativeSpeed = "Delta-V (Rel Speed)",
        blackboxImpactG = "Impact G-Force",
        blackboxTimelineTitle = "Pre-Crash 3s Trajectory",
        blackboxScenarioTitle = "Scenario Assessment"
    )

    fun get(language: AppLanguage): VisorI18nStrings = when (language) {
        AppLanguage.ZH_TW -> zhTw
        AppLanguage.EN -> en
    }
}

fun VideoQualityOption.getLabel(language: AppLanguage): String = when (language) {
    AppLanguage.ZH_TW -> label
    AppLanguage.EN -> when (this) {
        VideoQualityOption.HD_720P -> "720p HD (Low Heat)"
        VideoQualityOption.FHD_1080P -> "1080p Full HD"
    }
}

fun ImpactSensitivity.getLabel(language: AppLanguage): String = when (language) {
    AppLanguage.ZH_TW -> label
    AppLanguage.EN -> when (this) {
        ImpactSensitivity.OFF -> "Off"
        ImpactSensitivity.LOW -> "Low (3.5G - Severe Only)"
        ImpactSensitivity.MEDIUM -> "Standard (2.8G)"
        ImpactSensitivity.HIGH -> "High (2.0G - Sensitive)"
    }
}

fun FcwSensitivity.getLabel(language: AppLanguage): String = when (language) {
    AppLanguage.ZH_TW -> label
    AppLanguage.EN -> when (this) {
        FcwSensitivity.CONSERVATIVE -> "Conservative"
        FcwSensitivity.STANDARD -> "Standard"
        FcwSensitivity.SENSITIVE -> "Sensitive"
    }
}

fun SpeedUnit.getLabel(language: AppLanguage): String = when (language) {
    AppLanguage.ZH_TW -> label
    AppLanguage.EN -> when (this) {
        SpeedUnit.KMH -> "KM/H (Metric)"
        SpeedUnit.MPH -> "MPH (Imperial)"
    }
}
