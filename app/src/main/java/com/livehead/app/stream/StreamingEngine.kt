package com.livehead.app.stream

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import com.livehead.app.core.AppLog
import com.livehead.app.core.StateFlow
import com.livehead.app.data.EngineState
import com.livehead.app.data.EngineStats
import com.livehead.app.data.StreamSettings
import com.livehead.app.data.UploadHealth
import com.livehead.app.data.VideoMeta
import com.livehead.app.stream.rtmp.RtmpConnection
import com.livehead.app.stream.rtmp.RtmpEndpoint
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** V1 engine interface — a future version can add playlist sources without
 *  touching the service/UI contract. */
interface StreamingEngine {
    val stats: StateFlow<EngineStats>
    fun start()
    fun stop(userInitiated: Boolean = true)
}

/**
 * Owns the entire streaming session:
 *
 *   RtmpConnection  ↔  supervisor (connect / watchdog / reconnect)
 *   VideoPipeline   →  MediaSink → connection
 *   AudioPipeline   →  MediaSink → connection
 *
 * The UI never talks to this class directly — the foreground service does.
 */
class RtmpStreamingEngine(
    context: Context,
    private val endpoint: RtmpEndpoint,
    private val videoUri: Uri,
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
    @Volatile private var connectionLive = false

    private val pacer = Pacer(
        clockMs = { SystemClock.elapsedRealtime() },
        sleeper = { ms -> Thread.sleep(ms) }
    )

    private val counters = StreamCounters()
    private var videoPipeline: VideoPipeline? = null
    private var audioPipeline: AudioPipeline? = null

    private val signals = LinkedBlockingQueue<Signal>()
    private var engineThread: Thread? = null

    // rolling bitrate window
    private class Sample(val t: Long, val bytes: Long)

    private val window = ArrayDeque<Sample>()
    private var lastStatsEmit = 0L
    private var startedAt = -1L
    private val totalBytes = AtomicLong(0)
    private val totalPackets = AtomicLong(0)
    private var reconnects = 0L
    private var bothPipelinesDone = false
    private var lastVideoDropBaseline = 0L

    private enum class Signal { STOP, CONNECTION_DIED, PIPELINE_DONE }

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
        videoPipeline?.cancel()
        audioPipeline?.cancel()
    }

    // =========================================================================
    // Supervisor
    // =========================================================================

    private fun supervise() {
        try {
            publish(EngineState.PREPARING, null)
            val meta = probeVideo()
            val (tw, th) = targetSize(meta)
            val bitrate = settings.resolveVideoBitrate(th)
            val loopLenUs = computeLoopLengthUs(meta)

            AppLog.i(TAG, "engine start: src=${meta.displayWidth}x${meta.displayHeight} → ${tw}x$th @${settings.fps.fps}fps, vbr=${bitrate / 1000}kbps, abr=${settings.audioBitrateBps / 1000}kbps, keyframe=${settings.keyframeIntervalSec}s, loop=${settings.loop}, audio=${if (meta.hasAudio) "track" else "silence"}")

            if (th > 720) {
                AppLog.w(TAG, "encoding at ${tw}x$th is demanding; consider 720p if the stream is unstable")
            }

            val vp = VideoPipeline(
                appContext, videoUri,
                meta.displayWidth, meta.displayHeight, tw, th,
                bitrate, settings.fps.fps, settings.keyframeIntervalSec,
                settings.loop, loopLenUs, pacer, this, pipelineCallbacks, counters
            )
            val ap = AudioPipeline(
                appContext, videoUri,
                settings.audioBitrateBps, settings.loop, loopLenUs,
                pacer, this, pipelineCallbacks, counters
            )
            vp.prepare()
            ap.prepare()
            videoPipeline = vp
            audioPipeline = ap

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
                        // a rejected stream key will never heal by retrying
                        lastFatal = friendly
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
                connectionLive = true
                // drop any stale signals from the failed attempt(s)
                signals.clear()
                if (!everConnected) {
                    everConnected = true
                    startedAt = SystemClock.elapsedRealtime()
                    pacer.start()
                    // onMetaData first; the pipelines emit their own AVC/AAC
                    // sequence headers the moment their encoders produce csd.
                    val md = FlvMuxer.onMetaData(
                        tw, th, settings.fps.fps, bitrate, settings.audioBitrateBps,
                        ap.encRateOut, ap.encChannelsOut, true
                    )
                    conn.sendTag(md)
                    vp.startThread()
                    ap.startThread()
                    publish(EngineState.STREAMING, null)
                    AppLog.i(TAG, "LIVE")
                } else {
                    reconnects++
                    pacer.rebaseToZero()
                    pacer.unfreeze()
                    val ts = pacer.lastStampMs().toInt()
                    vp.sequenceHeader(ts)?.let { conn.sendTag(it) }
                    ap.sequenceHeader(ts)?.let { conn.sendTag(it) }
                    vp.requestKeyframe()
                    publish(EngineState.STREAMING, null)
                    AppLog.i(TAG, "reconnected ( #$reconnects )")
                }

                // run until something happens --------------------------------
                monitorLoop@ while (true) {
                    val sig = signals.poll(1000, TimeUnit.MILLISECONDS)
                    when (sig) {
                        Signal.STOP -> break@monitorLoop
                        Signal.CONNECTION_DIED -> {
                            connectionLive = false
                            if (!settings.reconnectEnabled) {
                                lastFatal = "Connection lost."
                                stopRequested = true
                                break@monitorLoop
                            }
                            pacer.freeze()
                            publish(EngineState.RECONNECTING, "Connection lost. Reconnecting…")
                            break@monitorLoop
                        }
                        Signal.PIPELINE_DONE -> {
                            if (bothPipelinesDone) {
                                // video finished and loop is off
                                finishStream()
                                return
                            }
                        }
                        null -> {}
                    }
                    if (stopRequested) break@monitorLoop

                    watchdog(conn)
                    emitStats(conn)
                }

                conn.gracefulClose()
                connection = null
                connectionLive = false
                attempt++
            }

            if (lastFatal != null) {
                publish(EngineState.ERROR, lastFatal)
            } else if (!bothPipelinesDone) {
                // orderly shutdown
                publish(EngineState.STOPPING, null)
                drainAndRelease()
                publish(EngineState.STOPPED, null)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "engine crashed: ${t.message} (${t.javaClass.simpleName})")
            publish(EngineState.ERROR, "Streaming stopped unexpectedly. ${t.message ?: ""}".trim())
            drainAndRelease()
        } finally {
            drainAndRelease()
        }
    }

    private fun handleConnectionEvent(event: RtmpConnection.Event) {
        when (event) {
            is RtmpConnection.Event.PublishStarted -> {}
            is RtmpConnection.Event.CommandError -> {
                signals.add(Signal.CONNECTION_DIED)
            }
            is RtmpConnection.Event.ClosedUnexpected -> {
                signals.add(Signal.CONNECTION_DIED)
            }
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

    private fun finishStream() {
        AppLog.i(TAG, "stream finished (end of video, loop off)")
        publish(EngineState.STOPPING, null)
        drainAndRelease()
        publish(EngineState.FINISHED, null)
    }

    private fun drainAndRelease() {
        try {
            videoPipeline?.stopFeedingAndFlush()
        } catch (ignore: Throwable) {
        }
        videoPipeline?.joinThread(2500)
        audioPipeline?.joinThread(2500)
        try { connection?.gracefulClose() } catch (ignore: Throwable) {}
        videoPipeline?.release()
        audioPipeline?.release()
        connection = null
        connectionLive = false
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
                    // write failed — connection dying; the supervisor will
                    // freeze the pacer and reconnect. Drop this packet.
                    throw IOException("write failed")
                }
            }
            if (stopRequested) throw CancelledException()
            Thread.sleep(50)
        }
    }

    override fun audioClockUs(): Long = counters.audioClockUs.get()

    // =========================================================================
    // Probing / sizing
    // =========================================================================

    private fun probeVideo(): VideoMeta {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, videoUri)
        } catch (t: Throwable) {
            throw IOException("Video cannot be opened. Try re-selecting it from the picker.")
        }
        try {
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.let { rot ->
                val hh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (rot == "90" || rot == "270") hh.also {  } else 0
            } ?: 0
            // simpler + correct:
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            w = if (rot == 90 || rot == 270) rawH else rawW
            h = if (rot == 90 || rot == 270) rawW else rawH

            if (rawW <= 0 || rawH <= 0) throw IOException("This video format is not supported.")

            // codec info from the extractor
            var videoMime = "video/avc"
            var audioMime: String? = null
            var aRate = 44100
            var aCh = 2
            var aBr = 0
            var vTrackDurUs = 0L
            var aTrackDurUs = 0L
            val ext = MediaExtractor()
            try {
                ext.setDataSource(appContext, videoUri, null)
                for (i in 0 until ext.trackCount) {
                    val f = ext.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && videoMime == "video/avc") {
                        videoMime = mime
                        vTrackDurUs = f.longOr(MediaFormat.KEY_DURATION, durationMs * 1000L)
                    } else if (mime.startsWith("audio/") && audioMime == null) {
                        audioMime = mime
                        aRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
                        aCh = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
                        aBr = f.intOr(MediaFormat.KEY_BIT_RATE, 0)
                        aTrackDurUs = f.longOr(MediaFormat.KEY_DURATION, durationMs * 1000L)
                    }
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "track probe failed: ${t.message}")
            } finally {
                try { ext.release() } catch (ignore: Throwable) {}
            }

            return VideoMeta(
                uri = videoUri.toString(),
                displayName = videoUri.lastPathSegment ?: "video",
                durationUs = durationMs * 1000L,
                displayWidth = w,
                displayHeight = h,
                videoMime = videoMime,
                hasAudio = audioMime != null,
                audioMime = audioMime,
                audioSampleRate = aRate,
                audioChannels = aCh,
                audioBitrate = aBr,
            ).also { metaTrackDurUs = maxOf(vTrackDurUs, aTrackDurUs) }
        } finally {
            try { retriever.release() } catch (ignore: Throwable) {}
        }
    }

    private var metaTrackDurUs = 0L

    private fun computeLoopLengthUs(meta: VideoMeta): Long {
        val len = maxOf(meta.durationUs, metaTrackDurUs, 1_000_000L)
        return len
    }

    /** Chooses encoder output size: user choice capped, never upscaled, even dims. */
    private fun targetSize(meta: VideoMeta): Pair<Int, Int> {
        val srcW = meta.displayWidth
        val srcH = meta.displayHeight
        val scale = when (settings.resolution) {
            com.livehead.app.data.ResolutionChoice.AUTO ->
                minOf(1.0, 1920.0 / maxOf(srcW, srcH), 1088.0 / minOf(srcW, srcH))
            else -> minOf(1.0, settings.resolution.maxLongSide.toDouble() / srcH)
        }
        fun even(v: Int) = ((v / 2) * 2).coerceAtLeast(2)
        return even((srcW * scale).toInt()) to even((srcH * scale).toInt())
    }

    private fun networkAvailable(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (t: Throwable) {
            true
        }
    }

    // =========================================================================
    // Stats
    // =========================================================================

    private fun publish(state: EngineState, message: String?) {
        val s = stats.value
        stats.set(
            s.copy(
                state = state,
                message = message,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                reconnects = reconnects,
            )
        )
    }

    private fun emitStats(conn: RtmpConnection) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsEmit < 1000) return
        lastStatsEmit = now

        val bytes = totalBytes.get()
        window.addLast(Sample(now, bytes))
        while (window.size > 11) window.removeFirst()
        val first = window.first()
        val spanMs = (now - first.t).coerceAtLeast(1)
        val avgBps = ((bytes - first.bytes) * 8 * 1000) / spanMs
        val recentBps = if (window.size >= 2) {
            val prev = window[window.size - 2]
            ((bytes - prev.bytes) * 8 * 1000) / ((now - prev.t).coerceAtLeast(1))
        } else avgBps

        val vp = videoPipeline
        val elapsed = if (startedAt > 0) now - startedAt else 0
        val health = uploadHealth(avgBps, elapsed, vp)

        stats.set(
            stats.value.copy(
                state = stats.value.state,
                nowElapsedMs = now,
                videoWidth = vp?.targetWidthOut ?: 0,
                videoHeight = vp?.targetHeightOut ?: 0,
                fpsSetting = settings.fps.fps,
                videoBitrateSettingBps = settings.resolveVideoBitrate(vp?.targetHeightOut ?: 720),
                audioBitrateSettingBps = settings.audioBitrateBps,
                actualBitrateBps = recentBps,
                avgBitrateBps = avgBps,
                packetsSent = totalPackets.get(),
                bytesSent = bytes,
                videoFramesSent = counters.videoFramesSent.get(),
                audioFramesSent = counters.audioFramesSent.get(),
                droppedFrames = counters.videoDropped.get(),
                lateFrames = pacer.latePackets + pacer.timestampClamps,
                reconnects = reconnects,
                loopCount = counters.loopCount.get(),
                networkAvailable = networkAvailable(),
                uploadHealth = health,
            )
        )
    }

    private fun uploadHealth(avgBps: Long, elapsedMs: Long, vp: VideoPipeline?): UploadHealth {
        if (elapsedMs < 15_000 || vp == null) return UploadHealth.UNKNOWN
        val target = settings.resolveVideoBitrate(vp.targetHeightOut)
        return when {
            avgBps < target * 80 / 100 -> UploadHealth.POOR
            avgBps < target * 115 / 100 -> UploadHealth.MARGINAL
            else -> UploadHealth.OK
        }
    }

    private val pipelineCallbacks = object : PipelineCallbacks {
        override fun onPipelineError(which: String, friendlyMessage: String) {
            AppLog.e(TAG, "pipeline[$which] error: $friendlyMessage")
            publish(EngineState.ERROR, friendlyMessage)
            stopRequested = true
            signals.add(Signal.STOP)
        }

        override fun onPipelineFinished(which: String) {
            AppLog.i(TAG, "pipeline[$which] finished")
            if (videoPipeline?.isDone == true && audioPipeline?.isDone == true) {
                bothPipelinesDone = true
                signals.add(Signal.PIPELINE_DONE)
            }
        }
    }

    private fun sleepInterruptible(ms: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + ms
        while (!stopRequested) {
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline) return true
            signals.poll(minOf(deadline - now, 500L), TimeUnit.MILLISECONDS)?.let { return false }
        }
        return false
    }
}
