package com.livehead.app.controller

import android.content.Context
import android.content.Intent
import com.livehead.app.core.AppLog
import com.livehead.app.core.StateFlow
import com.livehead.app.data.EngineState
import com.livehead.app.data.EngineStats
import com.livehead.app.data.StreamSettings
import com.livehead.app.service.StreamingService

/**
 * In-process bridge between the foreground service (owner of the engine) and
 * the UI. Survives activity recreation (rotation, back stack) because it is a
 * plain object — never holds Context/Activity references.
 */
object StreamingController {

    data class UiState(
        val stats: EngineStats = EngineStats(),
        val serviceRunning: Boolean = false,
        val lastHeartbeatMs: Long = 0,
        val env: StreamingService.Environment? = null,
    )

    val state = StateFlow(UiState())

    private var statsFlow: StateFlow<EngineStats>? = null
    private var unsubscribeStats: (() -> Unit)? = null
    private var unsubscribeEnv: (() -> Unit)? = null

    fun attach(statsFlow: StateFlow<EngineStats>, envFlow: StateFlow<StreamingService.Environment>) {
        detach()
        this.statsFlow = statsFlow
        unsubscribeStats = statsFlow.subscribe { s ->
            state.set(state.value.copy(stats = s, serviceRunning = true, lastHeartbeatMs = System.currentTimeMillis()))
        }
        unsubscribeEnv = envFlow.subscribe { e ->
            state.set(state.value.copy(env = e))
        }
    }

    fun heartbeat() {
        val cur = state.value
        if (!cur.serviceRunning) {
            state.set(cur.copy(serviceRunning = true))
        }
    }

    fun detach() {
        unsubscribeStats?.invoke()
        unsubscribeEnv?.invoke()
        unsubscribeStats = null
        unsubscribeEnv = null
        statsFlow = null
        // keep last stats so the UI can show the terminal state briefly
    }

    fun publishError(message: String) {
        state.set(
            state.value.copy(
                stats = state.value.stats.copy(state = EngineState.ERROR, message = message),
                serviceRunning = false,
            )
        )
        AppLog.e("Controller", message)
    }

    fun startStream(
        context: Context,
        uri: String,
        url: String,
        key: String,
        settings: StreamSettings,
    ) {
        val intent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra("uri", uri)
            putExtra("url", url)
            putExtra("key", key)
            putExtra("res", settings.resolution.name)
            putExtra("fps", settings.fps.name)
            putExtra("vbr", settings.videoBitrate.name)
            putExtra("keyframe", settings.keyframeIntervalSec)
            putExtra("loop", settings.loop)
            putExtra("reconnect", settings.reconnectEnabled)
            putExtra("max_retry", settings.maxRetryIntervalSec)
        }
        context.startForegroundService(intent)
    }

    fun stopStream(context: Context) {
        val intent = Intent(context, StreamingService::class.java).setAction(StreamingService.ACTION_STOP)
        context.startService(intent)
    }

    fun isStreaming(): Boolean =
        StreamingService.running && state.value.stats.streaming
}
