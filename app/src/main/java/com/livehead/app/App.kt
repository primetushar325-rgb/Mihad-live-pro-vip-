package com.livehead.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STREAMING,
                "Live streaming",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing status of your live stream"
                setShowBadge(false)
            }
        )
    }

    companion object {
        lateinit var instance: App
            private set

        const val CHANNEL_STREAMING = "livehead_streaming"
        const val VERSION_NAME = "1.0.0"
        const val VERSION_CODE = 1
    }
}
