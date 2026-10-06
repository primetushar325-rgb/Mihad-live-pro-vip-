package com.livehead.app.stream

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import com.livehead.app.core.AppLog
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Thrown to unwind pipelines when the user stops the stream. */
class CancelledException : Exception("stream stopped")

/** Sink the pipelines write finished FLV tags into (owned by the engine). */
interface MediaSink {
    /**
     * Sends one tag, blocking while the connection is down. Throws
     * [IOException] when the tag could not be written (dropped), and
     * [CancelledException] when the stream is stopping.
     */
    fun send(tag: FlvTag)

    /** Last known audio stream position (us) — used to gate video. */
    fun audioClockUs(): Long
}

interface PipelineCallbacks {
    fun onPipelineError(which: String, friendlyMessage: String)
    fun onPipelineFinished(which: String)
}

/** Shared counters surfaced through diagnostics. */
class StreamCounters {
    val videoFramesSent = AtomicLong(0)
    val audioFramesSent = AtomicLong(0)
    val videoDropped = AtomicLong(0)
    val loopCount = AtomicLong(0)
    val audioClockUs = AtomicLong(-1)
}

// =============================================================================
// VIDEO
// =============================================================================

/**
 * Video file → decoder (Surface) → [GlScaler] → H.264 encoder → FLV.
 *
 * Timestamps: decoder outputs are re-stamped to final stream time
 * (filePts - trackStart + iterationOffset) BEFORE entering the encoder input
 * surface, so encoded PTS are strictly monotonic across loops.
 */
class VideoPipeline(
    context: Context,
    private val uri: Uri,
    private val srcWidth: Int,
    private val srcHeight: Int,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val bitrateBps: Int,
    private val fps: Int,
    private val keyframeIntervalSec: Int,
    private val loopEnabled: Boolean,
    private val loopLengthUs: Long,
    private val pacer: Pacer,
    private val sink: MediaSink,
    private val callbacks: PipelineCallbacks,
    private val counters: StreamCounters,
) {
    companion object {
        private const val TAG = "VideoPipeline"
        private const val TIMEOUT_US = 10_000L
    }

    private val appContext = context.applicationContext
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: android.view.Surface? = null
    private var scaler: GlScaler? = null

    @Volatile private var cancelled = false
    @Volatile private var feeding = true
    @Volatile private var finished = false
    private var iterOffsetUs = 0L
    private var trackStartUs = -1L
    private var spsPps: Pair<ByteArray, ByteArray>? = null
    private var thread: Thread? = null

    /** Engine-facing readouts. */
    val targetWidthOut: Int get() = targetWidth
    val targetHeightOut: Int get() = targetHeight
    val isDone: Boolean get() = finished

    /** Prepares codecs on the caller thread so config failures surface early. */
    fun prepare() {
        val ext = MediaExtractor()
        try {
            ext.setDataSource(appContext, uri, null)
        } catch (t: Throwable) {
            throw IOException("Video cannot be opened. Try re-selecting it from the picker.")
        }
        var trackIdx = -1
        var format: MediaFormat? = null
        for (i in 0 until ext.trackCount) {
            val f = ext.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) { trackIdx = i; format = f; break }
        }
        if (trackIdx < 0 || format == null) {
            ext.release()
            throw IOException("This video has no video track.")
        }
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        ext.selectTrack(trackIdx)

        // Encoder ------------------------------------------------------------
        val encoder = try {
            createEncoder()
        } catch (t: Throwable) {
            ext.release()
            throw IOException("Encoder initialization failed. Try lowering resolution or bitrate.")
        }
        var inputSurface: android.view.Surface? = null
        var scaler: GlScaler? = null
        try {
            inputSurface = encoder.createInputSurface()
            val needScale = targetWidth != srcWidth || targetHeight != srcHeight
            val decoderSurface = if (needScale) {
                scaler = GlScaler(srcWidth, srcHeight, targetWidth, targetHeight, inputSurface)
                scaler.start()
            } else inputSurface
            val decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (t: Throwable) {
                throw IOException("This video format is not supported on this device.")
            }
            try {
                decoder.configure(format, decoderSurface, null, 0)
                decoder.start()
            } catch (t: Throwable) {
                decoder.release()
                throw IOException("This video format is not supported on this device.")
            }
            this.extractor = ext
            this.encoder = encoder
            this.decoder = decoder
            this.encoderInputSurface = inputSurface
            this.scaler = scaler
        } catch (t: Throwable) {
            try { encoder.stop() } catch (ignore: Throwable) {}
            encoder.release()
            try { inputSurface?.release() } catch (ignore: Throwable) {}
            try { scaler?.stop() } catch (ignore: Throwable) {}
            if (t is IOException) throw t
            throw IOException("Encoder initialization failed. Try lowering resolution or bitrate.")
        }
    }

    private fun createEncoder(): MediaCodec {
        val encoder = MediaCodec.createEncoderByType("video/avc")

        fun fmt(withProfile: Boolean, withCbr: Boolean): MediaFormat {
            val f = MediaFormat.createVideoFormat("video/avc", targetWidth, targetHeight)
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSec)
            f.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            if (withCbr) {
                // MediaFormat.KEY_BIT_RATE_MODE ("bitrate-mode"); CBR keeps the
                // live bitrate flat, which ingest servers prefer.
                f.setInteger("bitrate-mode", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            if (withProfile) {
                f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
            return f
        }

        var ok = false
        var last: Throwable? = null
        for (f in listOf(fmt(true, true), fmt(true, false), fmt(false, true), fmt(false, false))) {
            try {
                encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                ok = true
                break
            } catch (t: Throwable) {
                last = t
            }
        }
        if (!ok) {
            encoder.release()
            throw last ?: IllegalStateException("encoder configure failed")
        }
        encoder.start()
        return encoder
    }

    fun startThread() {
        thread = Thread({ runLoop() }, "livehead-video").apply {
            isDaemon = true
            start()
        }
    }

    fun requestKeyframe() {
        try {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (ignore: Throwable) {
        }
    }

    fun sequenceHeader(timestampMs: Int): FlvTag? {
        val csd = spsPps ?: return null
        return FlvMuxer.avcSequenceHeader(csd.first, csd.second, timestampMs)
    }

    fun cancel() {
        cancelled = true
        feeding = false
    }

    /** Stops feeding new frames and flushes the encoder (~0.5s). */
    fun stopFeedingAndFlush() {
        if (finished) return
        feeding = false
        try {
            encoder?.signalEndOfInputStream()
        } catch (ignore: Throwable) {
        }
        // drain loop happens inside runLoop(); give it a moment here
        try { Thread.sleep(50) } catch (ignore: InterruptedException) {}
    }

    fun joinThread(ms: Long = 2500) {
        try { thread?.join(ms) } catch (ignore: InterruptedException) {}
    }

    fun release() {
        try { decoder?.stop() } catch (ignore: Throwable) {}
        try { decoder?.release() } catch (ignore: Throwable) {}
        try { scaler?.stop() } catch (ignore: Throwable) {}
        try { encoderInputSurface?.release() } catch (ignore: Throwable) {}
        try { encoder?.stop() } catch (ignore: Throwable) {}
        try { encoder?.release() } catch (ignore: Throwable) {}
        try { extractor?.release() } catch (ignore: Throwable) {}
        decoder = null
        encoder = null
        scaler = null
        encoderInputSurface = null
        extractor = null
    }

    // ---------------------------------------------------------------------

    private fun runLoop() {
        try {
            pump()
        } catch (c: CancelledException) {
            // normal on stop
        } catch (t: Throwable) {
            AppLog.e(TAG, "video pipeline failed: ${t.message ?: t.javaClass.simpleName}")
            callbacks.onPipelineError("video", "Video processing failed. ${t.message ?: ""}".trim())
        } finally {
            finished = true
            callbacks.onPipelineFinished("video")
        }
    }

    private fun pump() {
        val ext = extractor!!
        val dec = decoder!!
        val enc = encoder!!
        val info = MediaCodec.BufferInfo()
        var inputEosQueued = false
        var decoderEosSeen = false

        while (!cancelled) {
            var busy = false

            // 1) feed decoder ------------------------------------------------
            if (feeding && !inputEosQueued) {
                val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = dec.getInputBuffer(inIdx)!!
                    val n = ext.readSampleData(buf, 0)
                    if (n < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEosQueued = true
                    } else {
                        val pts = ext.sampleTime
                        if (trackStartUs < 0) trackStartUs = pts
                        dec.queueInputBuffer(inIdx, 0, n, pts, 0)
                        busy = true
                    }
                    ext.advance()
                }
            }

            // 2) drain decoder ------------------------------------------------
            val outIdx = dec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIdx >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (eos) {
                    decoderEosSeen = true
                    dec.releaseOutputBuffer(outIdx, false)
                } else if (info.size > 0) {
                    val streamPts = info.presentationTimeUs - trackStartUs + iterOffsetUs
                    if (streamPts >= 0) {
                        dec.releaseOutputBuffer(outIdx, streamPts * 1000L)
                        busy = true
                    } else {
                        dec.releaseOutputBuffer(outIdx, false)
                    }
                } else {
                    dec.releaseOutputBuffer(outIdx, false)
                }
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // decoder format change on surface path: nothing to do
            }

            // 3) drain encoder -------------------------------------------------
            drainEncoder(info, final = false)
            if (decoderEosSeen) {
                handleEos()
                if (!loopEnabled) {
                    drainEncoder(info, final = true)
                    return
                }
                inputEosQueued = false
                decoderEosSeen = false
            }

            if (!busy) Thread.sleep(2)
        }

        // cancelled — final flush
        drainEncoder(info, final = true)
    }

    private fun drainEncoder(info: MediaCodec.BufferInfo, final: Boolean) {
        val enc = encoder ?: return
        while (true) {
            val i = enc.dequeueOutputBuffer(info, if (final) 20_000L else TIMEOUT_US)
            if (i >= 0) {
                val buf = enc.getOutputBuffer(i)!!
                val flags = info.flags
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    val csd = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(csd)
                    FlvMuxer.parseSpsPps(csd)?.let { spsPps = it }
                    sendSequenceHeader()
                } else if (info.size > 0) {
                    val data = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(data)
                    enc.releaseOutputBuffer(i, false)
                    sendFrame(data, info.size, info.presentationTimeUs)
                    if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    continue
                }
                enc.releaseOutputBuffer(i, false)
                if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = enc.outputFormat
                f.getByteBuffer("csd-0")?.let { b ->
                    val csd = ByteArray(b.remaining())
                    b.get(csd)
                    FlvMuxer.parseSpsPps(csd)?.let { spsPps = it }
                    sendSequenceHeader()
                }
            } else {
                return // no more output right now
            }
            if (!final && cancelled) return
        }
    }

    private fun sendSequenceHeader() {
        val csd = spsPps ?: return
        try {
            val ts = kotlin.math.max(pacer.lastStampMs(), 0L).toInt()
            sink.send(FlvMuxer.avcSequenceHeader(csd.first, csd.second, ts))
        } catch (c: CancelledException) {
            throw c
        } catch (t: Throwable) {
            // dropped while reconnecting; engine resends headers on reconnect
        }
    }

    private fun sendFrame(data: ByteArray, size: Int, presentationTimeUs: Long) {
        val streamPts = presentationTimeUs
        // Do not let video run more than ~0.9s ahead of the audio clock.
        while (!cancelled) {
            val audioClock = sink.audioClockUs()
            if (audioClock < 0 || streamPts <= audioClock + 900_000L) break
            Thread.sleep(40)
        }
        if (cancelled) throw CancelledException()

        val ts = pacer.awaitSend(streamPts)
        if (ts < 0) throw CancelledException()
        val avcc = FlvMuxer.annexBToAvcc(data, size)
        if (avcc.avcc.isEmpty()) return
        try {
            sink.send(FlvMuxer.avcFrame(avcc.avcc, ts, avcc.hasIdr))
            counters.videoFramesSent.incrementAndGet()
        } catch (c: CancelledException) {
            throw c
        } catch (t: Throwable) {
            counters.videoDropped.incrementAndGet()
        }
    }

    private fun handleEos() {
        if (!loopEnabled) {
            AppLog.i(TAG, "video track finished (loop off)")
            return
        }
        // Wait for the iteration boundary so audio and video stay aligned.
        val boundary = iterOffsetUs + loopLengthUs
        val ts = pacer.awaitSend(boundary)
        if (ts < 0) throw CancelledException()
        iterOffsetUs += loopLengthUs
        counters.loopCount.incrementAndGet()
        AppLog.d(TAG, "video loop #${counters.loopCount.get()}")
        try {
            extractor!!.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            decoder!!.flush()
            requestKeyframe()
        } catch (t: Throwable) {
            AppLog.w(TAG, "loop restart hiccup: ${t.javaClass.simpleName}")
        }
    }
}
