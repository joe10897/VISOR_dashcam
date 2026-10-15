# 🛡️ VISOR Dashcam
**完全離線（100% On-Device / Zero-Cloud）的 Android AI 行車記錄器與 ADAS 輔助系統**

---

## 🌟 核心特色

1. **100% 完全離線與隱私保證（Zero-Cloud）**：
   - 無需登入、無聯網、無 Firebase / 廣告 / 雲端追蹤 SDK。
   - 所有影片、GPS 軌跡與感測器紀錄皆儲存於本機 SQLite (Room) 資料庫。
2. **極致輕量 YOLO26n AI 推論**：
   - 採用最新 **YOLO26n (Nano)**，具備原生 **End-to-End (NMS-Free)** 雙頭架構，無需手機 CPU 執行耗時的後處理 NMS。
   - 經由 ONNX Runtime Mobile 搭配 Android NNAPI / Qualcomm QNN NPU 硬體加速，延遲極低。
3. **實用級 ADAS 駕駛輔助演算法**：
   - **前車防撞預警 (FCW)**：基於單目透視幾何與邊界框膨脹速率計算碰撞時間（Time-to-Collision, TTC），於緊急狀況發出聲光警示。
   - **前車起步提醒 (LVDA)**：停等紅燈停頓超過 3 秒時，偵測前車駛離並提示起步。
   - **車距過近警示 (HMW)**：計算時距（Time Headway），高速跟車過近時視覺提示。
4. **智慧雙重觸發鎖檔（Dual-Trigger Auto-Lock）**：
   - 三軸 G-Sensor 重力突變（撞擊 / 坑洞）結合 AI 視覺碰撞警報，自動將該循環短片標記保護，不被循環覆蓋。
5. **車用級防過熱與節能設計**：
   - **AMOLED 息屏/超暗 HUD 模式**：夏季擋風玻璃下關閉耗電的預覽渲染，僅保留極簡車速儀表，大幅降低機身溫度。
   - **推論幀率智慧節流**：錄影維持流暢，AI 分析以 8~10 FPS 運行，兼顧即時性與電池散熱。
   - **前台常駐服務（Foreground Service）**：支援在背景持續錄影，切換到 Google Maps 或導航軟體時不受干擾。

---

## 🏗️ 專案模組架構

```
com.visor.dashcam/
├── VisorApp.kt                   # 應用程式入口與通知管道初始化
├── MainActivity.kt               # 權限申請、服務綁定與 UI 路由
├── ai/
│   ├── detector/
│   │   └── Yolo26nDetector.kt   # ONNX Runtime NMS-Free 模型推論器 (支援 NNAPI)
│   ├── adas/
│   │   ├── ADASManager.kt       # 單目測距、TTC 碰撞時間計算、LVDA 前車起步
│   │   └── ADASAlert.kt         # 警報等級與蜂鳴器音效控制
│   └── model/
│       └── DetectionResult.kt   # 檢測框、類別與目標追蹤資料結構
├── recording/
│   ├── DashcamRecordingService.kt # CameraX 循環分段錄影與背景前景服務
│   ├── StorageManager.kt        # 儲存容量監控與舊檔循環清理機制
│   └── RecordingState.kt        # 錄影狀態與鎖檔標記
├── sensors/
│   ├── TelemetryManager.kt      # 純離線 GPS (速度/方位) + 三軸加速度計 (G值)
│   └── TelemetryData.kt         # 即時感測器融合數據
├── data/
│   ├── VisorDatabase.kt         # Room 本地資料庫單例
│   ├── entity/                  # 影片切片 (ClipEntity) 與事件紀錄 (EventEntity)
│   └── dao/                     # 查詢與鎖定資料存取介面
└── ui/
    ├── components/
    │   └── VisorHUD.kt          # 駕駛儀表 HUD、AI 辨識框繪製、AMOLED 節能模式
    ├── screens/
    │   └── ClipLibraryScreen.kt # 本機離線影片播放與鎖檔管理
    └── theme/                   # 車用高對比深色主題
```

---

## 🚀 YOLO26n 模型導出指引

透過專案附帶之 `tools/export_yolo26n.py` 即可將 Ultralytics YOLO26n 導出為 Android 專用之 ONNX 檔案：

```bash
cd tools
pip install ultralytics onnx onnxsim
python export_yolo26n.py --imgsz 320 --output ../app/src/main/assets/yolo26n.onnx
```
