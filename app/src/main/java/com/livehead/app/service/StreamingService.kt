package com.livehead.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.app.KeyguardManager
import com.livehead.app.App
import com.livehead.app.R
import com.livehead.app.controller.StreamingController
import com.livehead.app.core.AppLog
import com.livehead.app.core.Fmt
import com.livehead.app.core.StateFlow
import com.livehead.app.data.EngineState
import com.livehead.app.data.EngineStats
import com.livehead.app.data.StreamSettings
import com.livehead.app.stream.CameraStreamingEngine
import com.livehead.app.stream.StreamingEngine
import com.livehead.app.stream.rtmp.RtmpEndpoint
import com.livehead.app.ui.HomeActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single owner of the streaming lifecycle. The UI never keeps the engine
 * alive: killing the activity (rotation, back, screen off) does not touch the
 * stream. The engine itself runs inside this foreground service with a
 * partial wake lock so encoding continues with the display off.
 */
class StreamingService : Service() {

    companion object {
        private const val TAG = "StreamingService"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.livehead.app.action.START"
        const val ACTION_STOP = "com.livehead.app.action.STOP"

        @Volatile var running = false
            private set
    }

    private var engine: StreamingEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val started = AtomicBoolean(false)

    // environment monitoring
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkAvailable = true
    private var batteryPct = -1
    private var batteryLow = false
    private var thermalHot = false
    private var envStats = StateFlow(Environment())

    data class Environment(
        val networkAvailable: Boolean = true,
        val batteryPct: Int = -1,
        val batteryLow: Boolean = false,
        val thermalHot: Boolean = false,
        val thermalStatus: String = "none",
    )

    // ---------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStream()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val url = intent.getStringExtra("url")
                val key = intent.getStringExtra("key")
                val settings = StreamSettings(
                    resolution = enumValueOf(intent.getStringExtra("res") ?: "AUTO"),
                    fps = enumValueOf(intent.getStringExtra("fps") ?: "F30"),
                    videoBitrate = enumValueOf(intent.getStringExtra("vbr") ?: "AUTO"),
                    keyframeIntervalSec = intent.getIntExtra("keyframe", 2),
                    loop = intent.getBooleanExtra("loop", true),
                    reconnectEnabled = intent.getBooleanExtra("reconnect", true),
                    maxRetryIntervalSec = intent.getIntExtra("max_retry", 30),
                )
                if (url.isNullOrEmpty() || key.isNullOrEmpty()) {
                    AppLog.e(TAG, "start requested without complete parameters")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Android 14: a camera/microphone foreground service must not
                // start without the runtime permissions already granted.
                if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    StreamingController.publishError("Camera and microphone permissions are required to go live.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val endpoint = RtmpEndpoint.parse(url, key)
                if (endpoint == null) {
                    StreamingController.publishError("Stream URL is invalid. It should look like rtmps://a.rtmp.youtube.com/live2")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (started.compareAndSet(false, true)) {
                    startForegroundTyped(buildNotification(null))
                    startEnvironmentMonitoring()
                    acquireWakeLock()
                    running = true
                    val e = CameraStreamingEngine(this, endpoint, settings)
                    engine = e
                    e.stats.subscribe { onStats(it) }
                    StreamingController.attach(e.stats, envStats)
                    e.start()
                }
            }
            else -> {
                // restarted with no intent: nothing to do
                if (!started.get()) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundTyped(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 14 enforces the declared types; camera+microphone is
            // what this service actually uses while live.
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------------------------------------------------------------------
    // Environment: network / battery / thermal
    // ---------------------------------------------------------------------

    private fun startEnvironmentMonitoring() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkAvailable = true
                pushEnv()
            }

            override fun onLost(network: Network) {
                networkAvailable = false
                pushEnv()
                AppLog.w(TAG, "network lost")
            }
        }
        networkCallback = cb
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb
            )
        } catch (t: Throwable) {
            AppLog.w(TAG, "network callback registration failed")
        }

        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { sticky ->
            updateBattery(sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
        }
        pushEnv()
    }

    private fun updateBattery(level: Int, scale: Int) {
        batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        batteryLow = batteryPct in 0..15
        pushEnv()
    }

    private fun pushEnv() {
        envStats.set(
            Environment(
                networkAvailable = networkAvailable,
                batteryPct = batteryPct,
                batteryLow = batteryLow,
                thermalHot = thermalHot,
                thermalStatus = thermalStatusName,
            )
        )
    }

    private val thermalStatusName: String
        get() = try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> { thermalHot = true; "severe" }
                PowerManager.THERMAL_STATUS_CRITICAL -> { thermalHot = true; "critical" }
                PowerManager.THERMAL_STATUS_EMERGENCY -> { thermalHot = true; "emergency" }
                else -> "unknown"
            }
        } catch (t: Throwable) {
            "unknown"
        }

    // ---------------------------------------------------------------------
    // Engine events → notification + controller
    // ---------------------------------------------------------------------

    private var lastNotifUpdate = 0L

    private var lastBatteryPollMs = 0L

    private fun onStats(stats: EngineStats) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastBatteryPollMs > 30_000L) {
            lastBatteryPollMs = nowMs
            try {
                registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { sticky ->
                    updateBattery(
                        sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                        sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                    )
                }
            } catch (ignore: Throwable) {}
        }
        StreamingController.heartbeat()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val now = SystemClock.elapsedRealtime()
        val terminal = stats.state == EngineState.STOPPED ||
            stats.state == EngineState.FINISHED ||
            stats.state == EngineState.ERROR
        if (now - lastNotifUpdate > 1900 || terminal) {
            lastNotifUpdate = now
            try {
                nm.notify(NOTIF_ID, buildNotification(stats))
            } catch (ignore: Throwable) {
            }
        }
        if (terminal) {
            mainHandler.postDelayed({
                stopStream()
            }, 1500)
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun buildNotification(stats: EngineStats?): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, StreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title: String
        val text: String
        if (stats == null) {
            title = "LIVE HEAD"
            text = "Starting…"
        } else {
            val stateLabel = when (stats.state) {
                EngineState.STREAMING -> "● LIVE"
                EngineState.CONNECTING -> "Connecting…"
                EngineState.RECONNECTING -> "Reconnecting…"
                EngineState.STALLING -> "Stalled — reconnecting…"
                EngineState.STOPPING -> "Stopping…"
                EngineState.STOPPED -> "Stopped"
                EngineState.FINISHED -> "Stream finished"
                EngineState.ERROR -> "Error"
                else -> "Preparing…"
            }
            title = "LIVE HEAD"
            text = when {
                stats.state == EngineState.ERROR -> "⚠ ${stats.message ?: "Error"}"
                stats.state == EngineState.STREAMING ->
                    "$stateLabel  •  ${Fmt.bitrate(stats.actualBitrateBps)}  •  ${Fmt.res(stats.videoWidth, stats.videoHeight)}  •  ${Fmt.duration(stats.elapsedMs)}"
                else -> stateLabel
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, App.CHANNEL_STREAMING)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.apply {
            setSmallIcon(R.drawable.ic_stat_live)
            setContentTitle(title)
            setContentText(text)
            setContentIntent(openIntent)
            setOnlyAlertOnce(true)
            setOngoing(stats == null || stats.streaming)
            addAction(Notification.Action.Builder(null, "STOP LIVE", stopIntent).build())
            if (stats != null && stats.startedAtElapsedMs >= 0) {
                setWhen(System.currentTimeMillis() - stats.elapsedMs)
                setUsesChronometer(true)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
        }
        return builder.build()
    }

    // ---------------------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "livehead:stream").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // safety cap; released on destroy
        }
    }

    private fun stopStream() {
        try {
            engine?.stop()
        } catch (ignore: Throwable) {
        }
        mainHandler.removeCallbacksAndMessages(null)
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try {
            networkCallback?.let {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
        } catch (ignore: Throwable) {}
        try {
            engine?.stop()
        } catch (ignore: Throwable) {}
        engine = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        StreamingController.detach()
        started.set(false)
        AppLog.i(TAG, "service destroyed — all resources released")
        super.onDestroy()
    }
}
