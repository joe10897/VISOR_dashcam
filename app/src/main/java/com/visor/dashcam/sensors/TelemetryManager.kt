package com.visor.dashcam.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.visor.dashcam.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class TelemetryManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val onEmergencyImpact: ((gForce: Float) -> Unit)? = null,
    private val onHardBrake: ((gForceY: Float) -> Unit)? = null
) : SensorEventListener, LocationListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()

    var lastKnownLocation: Location? = null
        private set

    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    private val hardBrakeThresholdG = 0.55f
    private var lastImpactTimestamp = 0L
    private var lastHardBrakeTimestamp = 0L

    fun start() {
        try {
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

            accelerometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            magnetometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locMgr = locationManager ?: return
        try {
            val providers = locMgr.allProviders
            if (providers.contains(LocationManager.GPS_PROVIDER) &&
                locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER)
            ) {
                locMgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0.5f,
                    this
                )
            }
        } catch (e: Exception) {
            // Safely catch SecurityException or IllegalArgumentException on devices without GPS
            e.printStackTrace()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val alpha = 0.8f
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
            hasGravity = true

            val linearX = (event.values[0] - gravity[0]) / SensorManager.GRAVITY_EARTH
            val linearY = (event.values[1] - gravity[1]) / SensorManager.GRAVITY_EARTH
            val linearZ = (event.values[2] - gravity[2]) / SensorManager.GRAVITY_EARTH

            val totalG = sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ)

            var isImpact = false
            var isHardBrake = false

            val impactThresholdG = settingsRepository.settings.value.impactSensitivity.thresholdG

            if (totalG > impactThresholdG && (now - lastImpactTimestamp > 5000L)) {
                lastImpactTimestamp = now
                isImpact = true
                onEmergencyImpact?.invoke(totalG)
            }

            if (linearY < -hardBrakeThresholdG || linearZ < -hardBrakeThresholdG) {
                isHardBrake = true
                if (now - lastHardBrakeTimestamp > 5000L) {
                    lastHardBrakeTimestamp = now
                    onHardBrake?.invoke(linearY)
                }
            }

            _telemetry.value = _telemetry.value.copy(
                gForceTotal = totalG,
                gForceX = linearX,
                gForceY = linearY,
                gForceZ = linearZ,
                isImpactDetected = isImpact,
                isHardBrakeDetected = isHardBrake
            )
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            geomagnetic[0] = event.values[0]
            geomagnetic[1] = event.values[1]
            geomagnetic[2] = event.values[2]
            hasGeomagnetic = true
        }

        if (hasGravity && hasGeomagnetic) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (azimuth < 0) azimuth += 360f

                _telemetry.value = _telemetry.value.copy(headingDegrees = azimuth)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(location: Location) {
        lastKnownLocation = location
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        val heading = if (location.hasBearing()) location.bearing else _telemetry.value.headingDegrees

        _telemetry.value = _telemetry.value.copy(
            speedKmh = speedKmh,
            headingDegrees = heading,
            altitudeMeters = location.altitude,
            latitude = location.latitude,
            longitude = location.longitude
        )
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    fun stop() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {
        }
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
    }
}
