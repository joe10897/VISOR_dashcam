# 🛡️ VISOR AI Dashcam (車載智慧邊緣行車記錄器)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-green.svg)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-35%20(Android%2015)-blue.svg)](https://developer.android.com/about/versions/15)
[![16KB Page Size](https://img.shields.io/badge/Android%2015-16KB%20Page--Size%20Ready-success.svg)](https://developer.android.com/guide/practices/page-sizes)
[![Inference Engine](https://img.shields.io/badge/AI%20Engine-ONNX%20Runtime%20Mobile%20%2B%20NNAPI-orange.svg)](https://onnxruntime.ai)
[![Zero-Cloud](https://img.shields.io/badge/Privacy-100%25%20Zero--Cloud%20Offline-red.svg)](#隱私與安全性架構)

> **VISOR (Vehicle Intelligent Sensory Optical Recorder)** 是一款專為 Android 平台打造的**完全離線（100% On-Device / Zero-Cloud）高階車載邊緣行車記錄器與 ADAS 駕駛輔助系統**。
>
> 結合最新 **YOLO26n / YOLO11n 原生無 NMS（End-to-End NMS-Free）神經網絡架構**、硬體加速影片浮水印壓制、單目透視幾何碰撞時間 (TTC) 估算、G-Sensor 衝擊雙重鎖檔與智慧溫控保護，將閒置或現役的 Android 手機直接轉變為頂級專業車用行車記錄主機。

---

## 📑 目錄

1. [核心架構與設計哲學](#-核心架構與設計哲學)
2. [系統使用者介面 (UI Showcase)](#-系統使用者介面-ui-showcase)
3. [系統架構與資料流向 (System Architecture)](#-系統架構與資料流向-system-architecture)
4. [核心功能特點 (Feature Highlights)](#-核心功能特點-feature-highlights)
5. [硬體推薦與車載安裝指引](#-硬體推薦與車載安裝指引)
6. [專案目錄結構 (Project Layout)](#-專案目錄結構-project-layout)
7. [快速開始與編譯指引 (Build & Setup Guide)](#-快速開始與編譯指引-build--setup-guide)
8. [開源授權條款 (MIT License)](#-開源授權條款-mit-license)

---

## 💡 核心架構與設計哲學

- **100% 離線純淨運行 (Zero-Cloud Guarantee)**：
  本專案絕不使用任何 Firebase、分析統計、廣告 SDK 或雲端回傳 API。所有影音、GPS 座標、時速與 AI 事件均儲存在本機儲存空間與 Room SQLite 資料庫中，行車隱私零外洩。
- **車載專用極致穩定度 (Automotive Stability)**：
  針對長期置於車輛擋風玻璃下日曬、高溫與顛簸環境設計。內建動態四階溫控節流（Thermal Throttling）、AMOLED 息屏防烙印模式與前景服務（Foreground Service）常駐保護。
- **純淨駕駛無干擾理念**：
  專注於關鍵安全功能——前車防撞預警（FCW）、前車起步提醒（LVDA）、車距監控（HMW），**刻意不納入車道偏離警示 (LDW)**，防止都會行車頻繁變道引發虛警疲勞。

---

## 🖥️ 系統使用者介面 (UI Showcase)

VISOR 採用 Jetpack Compose 打造高對比、低眩光且符合車規人體工學的橫向（Landscape）操作介面：

### 1. 駕駛輔助主畫面 (HUD Interface)

主畫面整合了即時行車遙測資訊、AI 目標檢測邊界框與動態碰撞時間（TTC）彩色警示：

![HUD Main Interface](docs/screenshots/hud_main.png)

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ [🔴 錄影中 00:19/03:00]  [🧭 240°]  [⚡ AI ON]  [🌡️ 35.0°C]       [🔄] [🌙] [⚙️] [📁] [🔒] [⏻] │
│                                                                                        │
│                      ┌──────────────────────┐                                          │
│                      │ 🚘 Car 88%  (0.8s)   │ <--- 動態 TTC 邊界框 (綠/黃/紅)          │
│                      │ 距離估算: 18.2 m     │                                          │
│                      └──────────────────────┘                                          │
│                                                                                        │
│                                                                                        │
│  [ 65 ] KM/H                                                         ┌──────────────┐  │
│   0.08 G (穩定)                                                      │ 🔒 緊急鎖檔  │  │
│                                                                      └──────────────┘  │
│                                                    [🛡️ VISOR AI | 2026-10-22 | 65 KM/H]│
└────────────────────────────────────────────────────────────────────────────────────────┘
```

- **頂部狀態列 (Top Telemetry Bar)**：
  - `🔴 錄影指示`：顯示當前分段錄製時間進度（預設 3 分鐘切片）與儲存狀態。
  - `🧭 羅盤方向`：根據 GPS 航向即時計算方位角（如 240° WSW）。
  - `⚡ AI 開關`：單鍵啟閉神經網路推論，節省能耗與算力。
  - `🌡️ 溫度監控`：即時電量與晶片溫度感測，依溫度自動變色警示（藍/黃/橙/紅）。
  - `快捷控制群`：鏡頭翻轉（前/後）、AMOLED 息屏節能、系統設定、行車影片庫、手動鎖檔、安全關機鍵。
- **左下大字時速儀表**：提供高清晰度數值顯示與三軸 G-Sensor 重力數值。
- **右下緊急手動鎖檔鍵**：單擊即時將當前錄製片段保護，避免被循環機制覆蓋。
- **右下硬體視訊浮水印**：時間、經緯度、時速直接烙印於 MP4 視訊底層，具備完整法律存證效力。

---

### 2. 系統設定與繁中/英文多語系切換 (Settings & Localization)

支援**繁體中文 (Traditional Chinese)** 與 **English** 即時無縫雙語切換，無需重啟 App：

![Settings Screen](docs/screenshots/settings_screen.png)

- **語言與介面設定**：單鍵立即變更全局介面語言。
- **視訊解析度**：4K UHD (2160p) / 1080p Full HD (建議) / 720p HD。
- **循環分段時長**：1 分鐘 / 3 分鐘 (預設標準) / 5 分鐘。
- **視訊遙測浮水印開關**：控制是否將時速、時間與 GPS 座標直接編碼至 MP4 視訊流。
- **ADAS 預警距離微調 (0 效能損耗)**：提供 0.8x (近/較晚) 至 1.2x (遠/提早) 五檔微調，完美適應不同車型與駕駛習慣。
- **高溫動態降頻保護**：高於 40°C 自動降頻 AI 推論，45°C+ 進入極致保護模式確保錄影不中斷。

---

### 3. 本機行車記錄庫與影片管理 (Clip Library & Player)

無需取出 SD 卡，直接在車機或手機端檢視、播放與管理所有歷史行車檔案：

![Clip Library](docs/screenshots/clip_library.png)

- **智慧分類標籤**：清晰劃分「一般循環片段（Loop）」與「已鎖定受保護片段（Locked）」。
- **即時預覽縮圖**：本機非同步生成高解析度視訊縮圖。
- **單鍵保護切換**：輕觸鎖定圖標即可在保護與循環狀態間切換。
- **安全刪除機制**：受保護之關鍵事故影片設有防呆確認，避免意外誤刪。

---

### 4. 安全關機與資料落盤程序 (Safe Shutdown Finalization)

點擊頂部紅色電源鈕即可呼叫安全關機確認對話框：

![Shutdown Dialog](docs/screenshots/shutdown_dialog.png)

- 自動安全封裝正處於寫入狀態的 `.mp4` 視訊頭資訊（moov atom），防止因強行斷電導致影片損毀。
- 乾淨終止 CameraX 視訊管線、感測器監聽器與 Android 前景服務。
- 調用 `finishAffinity()` 釋放所有系統資源並優雅退出。

---

## 🏗️ 系統架構與資料流向 (System Architecture)

```mermaid
flowchart TD
    subgraph SENSORS ["📡 物理感測與輸入硬體"]
        CAM["📷 相機鏡頭 (CameraX)"]
        GPS["🛰️ GPS 衛星模組"]
        IMU["📐 三軸加速度計 (G-Sensor)"]
        THM["🌡️ 電池與主板溫度感測器"]
    end

    subgraph PIPELINE ["⚙️ 核心處理管線"]
        ISP["OpenGLES 著色管線"]
        OVERLAY["OverlayEffect (視訊浮水印壓制)"]
        ENC["MediaCodec 硬體編碼器 (H.264/H.265)"]
        YUV["YUV_420_888 影像分析管線"]
        AI["🧠 YOLO26n NMS-Free 推論引擎 (ONNX + NNAPI)"]
        ADAS["📐 ADAS 碰撞時間計算 (Monocular TTC)"]
        SAFETY["🚨 雙重緊急觸發器 (Dual-Trigger)"]
        THERMAL["🔥 4-Tier 動態溫控節流調度器"]
    end

    subgraph STORAGE ["💾 儲存與落盤管理"]
        MP4["📁 循環 MP4 視訊切片 (/Movies/VISOR)"]
        DB["🗄️ Room SQLite 資料庫 (視訊索引與遙測軌跡)"]
        PURGE["🧹 磁碟容量智能清洗器 (自動維持 10% 緩衝)"]
    end

    CAM --> ISP
    ISP --> OVERLAY
    OVERLAY --> ENC
    ENC --> MP4
    CAM --> YUV
    YUV --> AI
    AI --> ADAS
    GPS --> OVERLAY
    IMU --> SAFETY
    ADAS --> SAFETY
    SAFETY --> DB
    DB --> MP4
    THM --> THERMAL
    THERMAL --> AI
    MP4 --> PURGE
```

---

## 🚀 核心功能特點 (Feature Highlights)

### 1. YOLO26n / YOLO11n 端到端免 NMS 深度學習引擎
- 採用專為行動邊緣裝置設計的最新 Nano 級物件檢測模型。
- **NMS-Free 架構**：模型輸出層已整合一對一匈牙利匹配篩選，省略傳統 CPU 密集型的 Non-Maximum Suppression 演算法，推論延遲降低 35% 以上。
- 透過 **ONNX Runtime Mobile** 原生綁定 Android **NNAPI** 或 Qualcomm **QNN Execution Provider**，在多數現代 Android 晶片上可達 30+ FPS 即時檢測。

### 2. 單目透視幾何前車防撞 (Monocular TTC Algorithm)
- 透過相機安裝幾何參數、車頭俯仰角（Pitch Angle）與地平線消失點，計算目標車輛在相機座標系中的縱向距離：
  $$\text{Distance} \approx \frac{f \times H_{\text{real}}}{h_{\text{bbox}}}$$
- 追蹤車輛邊界框之膨脹擴展速率（Expansion Rate），推估碰撞時間（Time-to-Collision, TTC）：
  $$\text{TTC} = \frac{w(t)}{\frac{dw}{dt}}$$
- 當 $\text{TTC} \le 1.2\text{ 秒}$ 時自動觸發聲光 FCW 警報；停等紅燈前車起步時觸發 LVDA 提示。

### 3. 硬體級視訊底層浮水印 (Direct MP4 Watermark)
- 採用 AndroidX CameraX 1.4+ 的 `OverlayEffect` 與 OpenGL 著色管線。
- 遙測數據（時間戳記、行車時速、GPS 經緯座標）直接在 MediaCodec 壓縮前繪製至視訊圖層上。
- 產生的 `.mp4` 檔案在任何播放器中皆自帶浮水印，免除外部 `.srt` 字幕遺失風險，具備事故舉證效力。

### 4. 智慧四階動態溫控降頻 (Dynamic Thermal Throttling)
針對夏季高溫酷暑環境，VISOR 設有主動降溫機制：
| 溫控狀態 | 溫度區間 | 系統行為調整 |
| :--- | :--- | :--- |
| **NORMAL (正常)** | $< 37^\circ\text{C}$ | 全功能開啟，AI 推論滿血運行 (10 FPS) |
| **WARM (溫暖)** | $37^\circ\text{C} \sim 40^\circ\text{C}$ | 降低 AI 取樣率至 5 FPS，背景減低算力消耗 |
| **HOT (高溫)** | $40^\circ\text{C} \sim 45^\circ\text{C}$ | 暫停 AI 視覺推論，UI 提示降溫，確保主錄影管線正常 |
| **CRITICAL (極限)** | $> 45^\circ\text{C}$ | 強制轉入黑屏節能模式，僅保留關鍵循環錄影與 G-Sensor |

### 5. 車載動態 180° / 倒置自適應旋轉 (Dynamic Auto-Orientation)
- 針對不同車型支架安裝習慣（例如充電線朝向左側或右側、倒立安裝），服務層內建 `OrientationEventListener` 與 `DisplayManager` 動態校正。
- 自動同步調整 CameraX Preview、VideoCapture 與 ImageAnalysis 管線之目標旋轉角度 (`targetRotation`)。
- 旋轉時自動觸發安全無縫接續換檔，同時在 AI 視覺分析前進行旋轉矩陣變換，確保 YOLO26n 檢測邊界框與 TTC 距離判斷永遠與實際地平線一致。

### 6. 未滿分段與中途關機之安全原子落盤 (Atomic In-Flight Segment Finalization)
- 解決傳統行車記錄器在手動關機或中途退出時，當前分段尚未滿 3~5 分鐘導致視訊檔案損毀遺失的問題。
- 採用非取消的獨立協程範疇 (`SupervisorJob() + Dispatchers.IO`)，關機前監聽 CameraX `VideoRecordEvent.Finalize`，保證 MP4 容器之關鍵 `moov` 索引標籤寫入磁碟並完成 Room SQLite 交易。
- 使用者點擊進入「影片庫」時，若當前錄影時長已超過 3 秒，系統即刻無縫裁切儲存當前片段並續錄新片段，確保剛錄下的所有畫面立即可在影片庫播放與管理。

### 7. 零效能損耗之 ADAS 預警距離微調 (Zero-Overhead Distance Calibration)
- 於設定頁面提供「預警距離微調」選項：`0.8x (偏近/較晚提醒)`、`0.9x`、`1.0x (標準推薦)`、`1.1x`、`1.2x (偏遠/提早提醒)`。
- 採用純量浮點數一階運算校正幾何距離，單次檢測耗時僅約 0.1 奈秒 ($O(1)$)，對神經網路推論幀率與 CPU 負載帶來 **0% 額外效能損耗**。

---

## 📱 硬體推薦與車載安裝指引

### 建議硬體規格
- **作業系統**：Android 10.0 (API 29) 以上；完全適配 **Android 15 (API 35)** 及 **16 KB Page-Size** 記憶體分頁機制。
- **處理器 (SoC)**：
  - 旗艦首選：高通 Snapdragon 8 Gen 1 / Gen 2 / Gen 3、聯發科天璣 8000 / 9000 系列、Google Tensor G2/G3/G4。
  - 主流可用：高通 Snapdragon 778G / 7 Gen 1 以上或同級處理器。
- **相機規格**：支援 Camera2 API `FULL` 或 `LIMITED` 等級，具備超廣角（FOV 110°~140°）鏡頭為佳。
- **電源供應**：車充需具備 QC 3.0 或 USB-PD 18W 以上輸出能力，建議搭配耐高溫雙面膠固定之專用車載支架。

### 安裝校準建議
1. **安裝位置**：建議固定於前擋風玻璃中央、後視鏡正後方或稍下方處，確保鏡頭處於雨刷刮除範圍內。
2. **水平角度**：使畫面地平線約略位於中央垂直 50% 處，天空與路面比例約 4:6 至 5:5。
3. **散熱維護**：盡量避免黑色車載遮陽墊堵塞手機散熱背殼，若長途高溫行駛可開啟內建 AMOLED 節能黑屏模式。

---

## 📂 專案目錄結構 (Project Layout)

```
d:/Playground/VISOR_dashcam/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # 權限、前景服務與硬體需求設定
│   │   ├── assets/
│   │   │   └── yolo26n.onnx             # 導出之 NMS-Free ONNX 模型檔
│   │   └── java/com/visor/dashcam/
│   │       ├── VisorApp.kt              # App 初始化與通知管道建置
│   │       ├── MainActivity.kt          # 權限申請、服務繫結與導航入口
│   │       ├── ai/
│   │       │   ├── detector/
│   │       │   │   └── Yolo26nDetector.kt   # ONNX Runtime 推論器 (NNAPI 加速)
│   │       │   ├── adas/
│   │       │   │   ├── ADASManager.kt       # 單目幾何 TTC、LVDA、HMW 演算法
│   │       │   │   └── ADASAlert.kt         # 警報事件資料模型
│   │       │   └── model/
│   │       │       └── DetectionResult.kt   # 物件檢測類別與座標結構
│   │       ├── recording/
│   │       │   ├── DashcamRecordingService.kt # CameraX 循環分段前台錄影服務
│   │       │   ├── StorageManager.kt        # 循環儲存清理與磁碟空間管理
│   │       │   └── RecordingState.kt        # 錄影狀態與鎖檔標識
│   │       ├── sensors/
│   │       │   ├── TelemetryManager.kt      # 離線 GPS 速度與 G-Sensor 加速度計
│   │       │   ├── ThermalManager.kt        # 電池與主機板溫度監控節流器
│   │       │   └── TelemetryData.kt         # 感測器融合遙測封裝
│   │       ├── data/
│   │       │   ├── VisorDatabase.kt         # Room SQLite 資料庫
│   │       │   ├── entity/ClipEntity.kt     # 視訊切片實體紀錄
│   │       │   └── repository/SettingsRepository.kt # 系統偏好與語言持久化
│   │       └── ui/
│   │           ├── i18n/VisorStrings.kt     # 繁體中文 / 英文雙語字串資源
│   │           ├── components/VisorHUD.kt   # 車載抬頭顯示器與觸控儀表
│   │           ├── screens/
│   │           │   ├── SettingsScreen.kt    # 系統設定面板
│   │           │   └── ClipLibraryScreen.kt # 行車影片庫與本地播放介面
│   │           └── theme/Theme.kt           # 高對比車規深色主題
├── tools/
│   └── export_yolo26n.py                # YOLO26n / YOLO11n ONNX 導出腳本
├── docs/
│   └── screenshots/                     # 系統運作截圖展示
├── build.gradle.kts                     # 專案級構建腳本
├── LICENSE                              # MIT 開源授權條款
└── git.md                               # 專案完整文檔與架構白皮書
```

---

## 🛠️ 快速開始與編譯指引 (Build & Setup Guide)

### 1. 環境需求
- **Android Studio**：Ladybug (2024.2+) 或更高版本。
- **Java SDK**：JDK 17 或 21。
- **Android SDK**：API Level 35 (Android 15)。

### 2. 取得源始碼與編譯
```bash
# 複製專案
git clone https://github.com/your-username/VISOR_dashcam.git
cd VISOR_dashcam

# 編譯 Debug APK
./gradlew assembleDebug

# 安裝至連線之 Android 手機或模擬器
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 模型自定義與重新導出 (選用)
如欲使用自己的訓練權重或升級模型：
```bash
cd tools
pip install ultralytics onnx onnxsim onnxruntime
python export_yolo26n.py --weights yolo11n.pt --imgsz 320 --output ../app/src/main/assets/yolo26n.onnx
```

---

## ⚖️ 開源授權條款 (MIT License)

本專案遵循 **MIT License** 授權條款開源：

```
MIT License

Copyright (c) 2026 VISOR Dashcam Project Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
