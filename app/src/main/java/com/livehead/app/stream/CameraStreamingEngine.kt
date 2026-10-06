package com.livehead.app.stream

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.livehead.app.core.AppLog
import com.livehead.app.core.StateFlow
import com.livehead.app.data.EngineState
import com.livehead.app.data.EngineStats
import com.livehead.app.data.StreamSettings
import com.livehead.app.data.UploadHealth
import com.livehead.app.stream.rtmp.RtmpConnection
import com.livehead.app.stream.rtmp.RtmpEndpoint
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the camera live-streaming session:
 *
 *   RtmpConnection  ↔  supervisor (connect / watchdog / reconnect)
 *   CameraPipeline  →  MediaSink → connection
 *   MicPipeline     →  MediaSink → connection
 *
 * The UI never talks to this class directly — the foreground service does.
 * On reconnect the pacer is rebased, sequence headers are resent at the
 * current stream stamp and a keyframe is forced — the exact rules that keep
 * YouTube from freezing after an ingest drop.
 */
class CameraStreamingEngine(
    context: Context,
    private val endpoint: RtmpEndpoint,
    private val settings: StreamSettings,
) : StreamingEngine, MediaSink {

    companion object {
        private const val TAG = "Engine"
        private const val STALL_TIMEOUT_MS = 5000L
        private const val WRITE_HANG_TIMEOUT_MS = 8000L
    }

    private val appContext = context.applicationContext

    override val stats = StateFlow(EngineStats())

    @Volatile private var stopRequested = false
    @Volatile private var connection: RtmpConnection? = null

    private val pacer = Pacer(
        clockMs = { SystemClock.elapsedRealtime() },
        sleeper = { ms -> Thread.sleep(ms) },
    )

    private val counters = StreamCounters()
    private var camera: CameraPipeline? = null
    private var mic: MicPipeline? = null

    private val signals = LinkedBlockingQueue<Signal>()
    private var engineThread: Thread? = null

    private class Sample(val t: Long, val bytes: Long)
    private val window = ArrayDeque<Sample>()
    private var lastStatsEmit = 0L
    private var startedAt = -1L
    private val totalBytes = AtomicLong(0)
    private val totalPackets = AtomicLong(0)
    private var reconnects = 0L

    private enum class Signal { STOP, CONNECTION_DIED }

    // =========================================================================

    override fun start() {
        check(engineThread == null) { "engine already started" }
        engineThread = Thread({ supervise() }, "livehead-engine").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop(userInitiated: Boolean) {
        if (stopRequested) return
        AppLog.i(TAG, "stop requested")
        stopRequested = true
        signals.add(Signal.STOP)
        pacer.cancel()
        camera?.cancel()
        mic?.cancel()
    }

    // =========================================================================
    // Supervisor
    // =========================================================================

    private fun supervise() {
        try {
            publish(EngineState.PREPARING, null)

            // Prepare codecs BEFORE connecting so camera/mic failures surface
            // immediately as friendly errors (never mid-stream).
            val longSide = when (settings.resolution.label) {
                "1080p" -> 1920
                "720p" -> 1280
                "480p" -> 854
                "360p" -> 640
                else -> 1280 // Auto: 720p-class is the stability sweet spot
            }
            val cp = CameraPipeline(
                appContext, longSide,
                settings.resolveVideoBitrate(longSide / 2), // height class ≈ longSide/2 (16:9)
                settings.fps.fps, settings.keyframeIntervalSec,
                pacer, this, pipelineCallbacks, counters,
            )
            val mp = MicPipeline(appContext, settings.audioBitrateBps, pacer, this, pipelineCallbacks, counters)
            cp.prepare()
            mp.prepare()
            camera = cp
            mic = mp

            val w = cp.widthOut
            val h = cp.heightOut
            val vbr = settings.resolveVideoBitrate(h)
            AppLog.i(TAG, "engine start: camera ${w}x${h} @${settings.fps.fps}fps, vbr=${vbr / 1000}kbps, abr=${settings.audioBitrateBps / 1000}kbps, keyframe=${settings.keyframeIntervalSec}s")

            var attempt = 0
            var everConnected = false
            var lastFatal: String? = null

            while (!stopRequested) {
                if (!networkAvailable()) {
                    publish(EngineState.RECONNECTING, "No Internet connection.")
                    if (!sleepInterruptible(3000)) break
                    continue
                }

                publish(EngineState.CONNECTING, null)
                val conn = RtmpConnection(endpoint) { event -> handleConnectionEvent(event) }
                try {
                    conn.connect()
                } catch (t: Throwable) {
                    val friendly = (t as? RtmpConnection.RtmpException)?.friendlyMessage
                        ?: "Could not connect to the streaming server."
                    val authError = friendly.contains("verify your Stream URL and Stream Key")
                    if (authError) {
                        lastFatal = friendly // a rejected key never heals
                        break
                    }
                    if (!settings.reconnectEnabled) {
                        lastFatal = friendly
                        break
                    }
                    val backoffSec = StreamSettings.backoffSeconds(attempt, settings.maxRetryIntervalSec)
                    attempt++
                    AppLog.w(TAG, "connect failed ($friendly); retry in ${backoffSec}s")
                    publish(EngineState.RECONNECTING, friendly)
                    if (!sleepInterruptible(backoffSec * 1000L)) break
                    continue
                }

                // connected ---------------------------------------------------
                connection = conn
                signals.clear() // drop stale signals from failed attempts
                if (!everConnected) {
                    everConnected = true
                    startedAt = SystemClock.elapsedRealtime()
                    pacer.start()
                    val md = FlvMuxer.onMetaData(
                        w, h, settings.fps.fps, vbr, settings.audioBitrateBps,
                        mp.sampleRateOut, mp.channelsOut, true,
                    )
                    conn.sendTag(md)
                    cp.startThread()
                    mp.startThread()
                    publish(EngineState.STREAMING, null)
                    AppLog.i(TAG, "LIVE")
                } else {
                    reconnects++
                    pacer.rebaseToZero()
                    pacer.unfreeze()
                    val ts = pacer.lastStampMs().toInt()
                    val md = FlvMuxer.onMetaData(
                        w, h, settings.fps.fps, vbr, settings.audioBitrateBps,
                        mp.sampleRateOut, mp.channelsOut, true,
                    )
                    conn.sendTag(md)
                    cp.sequenceHeader(ts)?.let { conn.sendTag(it) }
                    mp.sequenceHeader(ts)?.let { conn.sendTag(it) }
                    cp.requestKeyframe()
                    publish(EngineState.STREAMING, null)
                    AppLog.i(TAG, "reconnected ( #$reconnects )")
                }

                // run until something happens --------------------------------
                monitorLoop@ while (true) {
                    val sig = signals.poll(1000, TimeUnit.MILLISECONDS)
                    when (sig) {
                        Signal.STOP -> break@monitorLoop
                        Signal.CONNECTION_DIED -> {
                            if (!settings.reconnectEnabled) {
                                lastFatal = "Connection lost."
                                stopRequested = true
                                break@monitorLoop
                            }
                            pacer.freeze()
                            publish(EngineState.RECONNECTING, "Connection lost. Reconnecting…")
                            break@monitorLoop
                        }
                        null -> {}
                    }
                    if (stopRequested) break@monitorLoop
                    watchdog(conn)
                    emitStats(conn)
                }

                conn.gracefulClose()
                connection = null
                attempt++
            }

            if (lastFatal != null) {
                publish(EngineState.ERROR, lastFatal)
            } else {
                publish(EngineState.STOPPING, null)
                publish(EngineState.STOPPED, null)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "engine crashed: ${t.javaClass.simpleName}: ${t.message}")
            publish(EngineState.ERROR, "Streaming stopped unexpectedly. ${t.message ?: ""}".trim())
        } finally {
            release()
        }
    }

    private val pipelineCallbacks = object : PipelineCallbacks {
        override fun onPipelineError(which: String, friendlyMessage: String) {
            AppLog.e(TAG, "pipeline error ($which): $friendlyMessage")
            signals.add(Signal.CONNECTION_DIED)
            publish(EngineState.ERROR, friendlyMessage)
            stopRequested = true
        }

        override fun onPipelineFinished(which: String) {}
    }

    private fun handleConnectionEvent(event: RtmpConnection.Event) {
        when (event) {
            is RtmpConnection.Event.PublishStarted -> {}
            is RtmpConnection.Event.CommandError -> signals.add(Signal.CONNECTION_DIED)
            is RtmpConnection.Event.ClosedUnexpected -> signals.add(Signal.CONNECTION_DIED)
            is RtmpConnection.Event.Info -> AppLog.i(TAG, "rtmp: ${event.message}")
        }
    }

    /** Stall + hung-write detection. Never lets the app claim LIVE while dead. */
    private fun watchdog(conn: RtmpConnection) {
        val now = System.currentTimeMillis()
        if (stats.value.state == EngineState.STREAMING) {
            if (now - conn.lastWriteProgressMs > STALL_TIMEOUT_MS) {
                AppLog.w(TAG, "stall: no bytes for ${now - conn.lastWriteProgressMs}ms — forcing reconnect")
                publish(EngineState.STALLING, null)
                conn.forceClose("stall watchdog")
                return
            }
            if (now - conn.lastWriteProgressMs > WRITE_HANG_TIMEOUT_MS) {
                conn.forceClose("write watchdog")
            }
        }
    }

    private fun release() {
        camera?.stopAndRelease()
        mic?.stopAndRelease()
        camera = null
        mic = null
        try { connection?.gracefulClose() } catch (ignore: Throwable) {}
        connection = null
    }

    // =========================================================================
    // MediaSink — called from pipeline threads
    // =========================================================================

    override fun send(tag: FlvTag) {
        if (stopRequested) throw CancelledException()
        while (true) {
            val conn = connection
            if (conn != null && conn.isLive) {
                try {
                    conn.sendTag(tag)
                    totalBytes.addAndGet(tag.payload.size.toLong())
                    totalPackets.incrementAndGet()
                    return
                } catch (c: CancelledException) {
                    throw c
                } catch (t: Throwable) {
                    // write failed — supervisor freezes the pacer and
                    // reconnects; drop this packet.
                    throw IOException("write failed")
                }
            }
            if (stopRequested) throw CancelledException()
            Thread.sleep(50)
        }
    }

    override fun audioClockUs(): Long = counters.audioClockUs.get()

    // =========================================================================
    // Stats
    // =========================================================================

    private fun publish(state: EngineState, message: String?) {
        stats.set(stats.value.copy(state = state, message = message))
    }

    private fun emitStats(conn: RtmpConnection) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsEmit < 1000) return
        lastStatsEmit = now

        val bytes = totalBytes.get()
        synchronized(window) {
            window.addLast(Sample(now, bytes))
            while (window.size > 8) window.removeFirst()
        }
        val first = window.firstOrNull()
        val spanMs = if (first != null && window.size > 1) (now - first.t).coerceAtLeast(1) else 1
        val spanBytes = if (first != null) bytes - first.bytes else 0
        val bps = spanBytes * 8L * 1000L / spanMs

        val st = stats.value
        val nw = networkAvailable()
        val health = when {
            !nw -> UploadHealth.POOR
            st.state != EngineState.STREAMING -> UploadHealth.UNKNOWN
            bps in 1 until 300_000 -> UploadHealth.POOR
            bps in 300_000 until 700_000 -> UploadHealth.MARGINAL
            bps >= 700_000 -> UploadHealth.OK
            else -> UploadHealth.UNKNOWN
        }
        stats.set(
            st.copy(
                state = if (st.state == EngineState.STREAMING && !nw) EngineState.STALLING else st.state,
                startedAtElapsedMs = startedAt,
                nowElapsedMs = now,
                videoWidth = camera?.widthOut ?: 0,
                videoHeight = camera?.heightOut ?: 0,
                fpsSetting = settings.fps.fps,
                videoBitrateSettingBps = settings.resolveVideoBitrate(camera?.heightOut ?: 0),
                audioBitrateSettingBps = settings.audioBitrateBps,
                actualBitrateBps = bps,
                avgBitrateBps = if (startedAt > 0 && now > startedAt) bytes * 8L * 1000L / (now - startedAt) else 0,
                packetsSent = totalPackets.get(),
                bytesSent = bytes,
                videoFramesSent = counters.videoFramesSent.get(),
                audioFramesSent = counters.audioFramesSent.get(),
                droppedFrames = counters.videoDropped.get(),
                reconnects = reconnects,
                networkAvailable = nw,
                uploadHealth = health,
            ),
        )
    }

    private fun networkAvailable(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (t: Throwable) {
            true // assume yes; the connection attempt itself is the real test
        }
    }

    private fun sleepInterruptible(ms: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (!stopRequested) {
            val left = end - SystemClock.elapsedRealtime()
            if (left <= 0) return true
            Thread.sleep(minOf(left, 200L))
        }
        return false
    }
}
