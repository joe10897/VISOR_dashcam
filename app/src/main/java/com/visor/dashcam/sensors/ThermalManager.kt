package com.visor.dashcam.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class ThermalState(
    val batteryTempCelsius: Float = 0f,
    val isThrottled: Boolean = false,
    val isCritical: Boolean = false,
    val thermalStatus: Int = 0,
    val effectiveAiFps: Int = 10,
    val warningMessage: String? = null
)

/**
 * Monitors device and battery temperature continuously to prevent windshield overheating.
 * Dynamically throttles AI inference FPS from 10/15 FPS down to 4 FPS (or pauses inference)
 * while ensuring video loop recording stays 100% active.
 */
class ThermalManager(
    private val context: Context,
    private val onThermalThrottleChanged: ((isThrottled: Boolean, effectiveFps: Int) -> Unit)? = null
) {
    private val _thermalState = MutableStateFlow(ThermalState())
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var isRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (tempTenths > 0) {
                    val celsius = tempTenths / 10.0f
                    updateThermalMetrics(batteryTemp = celsius)
                }
            }
        }
    }

    fun start(baseTargetFps: Int) {
        if (isRegistered) return

        // 1. Initial Battery Temperature check
        try {
            val batteryStatusIntent = context.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            isRegistered = true
            val initialTempTenths = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val initialTemp = if (initialTempTenths > 0) initialTempTenths / 10.0f else 32.0f
            updateThermalMetrics(batteryTemp = initialTemp, baseFps = baseTargetFps)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Android 10+ (API 29+) PowerManager Thermal Status Listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                    updateThermalMetrics(status = status, baseFps = baseTargetFps)
                }
                thermalListener?.let { powerManager?.addThermalStatusListener(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateThermalMetrics(
        batteryTemp: Float = _thermalState.value.batteryTempCelsius,
        status: Int = _thermalState.value.thermalStatus,
        baseFps: Int = _thermalState.value.effectiveAiFps.coerceAtLeast(10)
    ) {
        val isSevereStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            status >= PowerManager.THERMAL_STATUS_SEVERE
        } else false

        val isCriticalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            status >= PowerManager.THERMAL_STATUS_CRITICAL
        } else false

        // Temperature thresholds:
        // Normal: < 41°C
        // Throttled: 41°C ~ 46°C or PowerManager THERMAL_STATUS_SEVERE
        // Critical: >= 47°C or PowerManager THERMAL_STATUS_CRITICAL
        val isCritical = batteryTemp >= 47.0f || isCriticalStatus
        val isThrottled = isCritical || batteryTemp >= 41.0f || isSevereStatus

        val effectiveFps = when {
            isCritical -> 1
            isThrottled -> minOf(baseFps, 4)
            else -> baseFps
        }

        val warningMsg = when {
            isCritical -> String.format(Locale.US, "高溫警報 (%.1f°C)！已暫停 AI 推論，背景持續安全錄影中", batteryTemp)
            isThrottled -> String.format(Locale.US, "機身偏熱 (%.1f°C)，AI 已降頻至 %d FPS 防過熱", batteryTemp, effectiveFps)
            else -> null
        }

        val previousThrottled = _thermalState.value.isThrottled
        _thermalState.value = ThermalState(
            batteryTempCelsius = batteryTemp,
            isThrottled = isThrottled,
            isCritical = isCritical,
            thermalStatus = status,
            effectiveAiFps = effectiveFps,
            warningMessage = warningMsg
        )

        if (previousThrottled != isThrottled) {
            onThermalThrottleChanged?.invoke(isThrottled, effectiveFps)
        }
    }

    fun stop() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver)
                isRegistered = false
            } catch (_: Exception) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                thermalListener?.let { powerManager?.removeThermalStatusListener(it) }
            } catch (_: Exception) {}
        }
    }
}
