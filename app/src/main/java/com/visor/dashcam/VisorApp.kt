package com.visor.dashcam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.visor.dashcam.data.VisorDatabase

class VisorApp : Application() {

    lateinit var database: VisorDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = VisorDatabase.getDatabase(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Foreground Service Recording Channel
            val recordingChannel = NotificationChannel(
                CHANNEL_RECORDING_ID,
                getString(R.string.channel_recording_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_recording_desc)
                setShowBadge(false)
            }

            // High Priority ADAS Emergency Alerts Channel
            val alertChannel = NotificationChannel(
                CHANNEL_ALERT_ID,
                getString(R.string.channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_alert_desc)
                enableVibration(true)
            }

            notificationManager.createNotificationChannel(recordingChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        const val CHANNEL_RECORDING_ID = "visor_recording_channel"
        const val CHANNEL_ALERT_ID = "visor_adas_alert_channel"

        lateinit var instance: VisorApp
            private set
    }
}
