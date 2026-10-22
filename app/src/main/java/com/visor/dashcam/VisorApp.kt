package com.visor.dashcam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.visor.dashcam.data.VisorDatabase
import com.visor.dashcam.data.repository.SettingsRepository
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VisorApp : Application() {

    lateinit var database: VisorDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        setupOfflineCrashLogger()
        database = VisorDatabase.getDatabase(this)
        settingsRepository = SettingsRepository(this)
        createNotificationChannels()
    }

    private fun setupOfflineCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val logFile = File(filesDir, "last_crash_log.txt")
                logFile.writeText("[$timestamp] Thread: ${thread.name}\n$sw")
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
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
