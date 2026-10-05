package com.livehead.app.stream

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.livehead.app.core.AppLog
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Audio: file track → PCM decoder → AAC encoder → FLV.
 *
 *  - If the source audio is not AAC-compatible it is re-encoded (everything is
 *    re-encoded anyway; the source codec does not matter as long as it decodes).
 *  - If the video has NO audio track, a silent AAC track is generated so the
 *    stream always carries continuous audio (a missing/stalled audio track is
 *    one of the classic causes of the 2-3 second YouTube freeze).
 *  - If the audio track is shorter than the video, silence fills the gap up to
 *    the loop boundary so audio never starves mid-iteration.
 */
class AudioPipeline(
    context: Context,
    private val uri: Uri,
    private val audioBitrateBps: Int,
    private val loopEnabled: Boolean,
    private val loopLengthUs: Long,
    private val pacer: Pacer,
    private val sink: MediaSink,
    private val callbacks: PipelineCallbacks,
    private val counters: StreamCounters,
) {
    companion object {
        private const val TAG = "AudioPipeline"
        private const val TIMEOUT_US = 10_000L
        private val AAC_RATES = setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000)
    }

    private val appContext = context.applicationContext

    // source
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var srcRate = 0
    private var srcChannels = 0
    private var hasSourceTrack = false

    // encoder
    private var encoder: MediaCodec? = null
    private var encRate = 44100
    private var encChannels = 2
    private var encoderReady = false
    private var audioSpecificConfig: ByteArray? = null
    private var frameDurUs = 0L

    @Volatile private var cancelled = false
    @Volatile private var feeding = true
    @Volatile private var finished = false
    private var iterOffsetUs = 0L
    private var trackStartUs = -1L
    private var silencePtsUs = 0L
    private var thread: Thread? = null

    /** Engine-facing readouts. */
    val encRateOut: Int get() = if (encoderReady) encRate else 44100
    val encChannelsOut: Int get() = if (encoderReady) encChannels else 2
    val isDone: Boolean get() = finished

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
            if (mime.startsWith("audio/")) { trackIdx = i; format = f; break }
        }
        if (trackIdx >= 0 && format != null) {
            hasSourceTrack = true
            ext.selectTrack(trackIdx)
            srcRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            srcChannels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            if (srcRate !in AAC_RATES) {
                // decode to PCM anyway; we resample to a legal AAC rate
                srcRate = if (srcRate in 4000..95999) srcRate else 48000
            }
            val dec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (t: Throwable) {
                AppLog.w(TAG, "audio decoder unavailable — falling back to silence")
                hasSourceTrack = false
                null
            }
            if (dec != null) {
                try {
                    val fmt = MediaFormat.createAudioFormat(mime, srcRate, srcChannels)
                    // carry decoder-specific info (csd) from the track format
                    for (key in listOf("csd-0", "csd-1")) {
                        format.getByteBuffer(key)?.let { csd -> fmt.setByteBuffer(key, csd) }
                    }
                    dec.configure(fmt, null, null, 0)
                    dec.start()
                    decoder = dec
                    extractor = ext
                } catch (t: Throwable) {
                    AppLog.w(TAG, "audio decoder configure failed — falling back to silence")
                    try { dec.release() } catch (ignore: Throwable) {}
                    hasSourceTrack = false
                }
            }
        }
        if (!hasSourceTrack) {
            AppLog.i(TAG, "no audio track — generating silence")
            ext.release()
            extractor = null
        }

        // Create the AAC encoder lazily once the real PCM format is known
        // (decoder may report a different channel count than the container).
        if (!hasSourceTrack) {
            createEncoder(44100, 2)
        }
    }

    private fun createEncoder(rate: Int, channelsIn: Int): Boolean {
        val channels = if (channelsIn >= 2) 2 else 1
        val encRate = if (rate in AAC_RATES) rate else 48000
        this.encRate = encRate
        this.encChannels = channels
        frameDurUs = 1_000_000L * 1024 / encRate
        return try {
            val enc = MediaCodec.createEncoderByType("audio/mp4a-latm")
            val fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", encRate, channels)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrateBps)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            fmt.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            encoder = enc
            encoderReady = true
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "AAC encoder init failed: ${t.message}")
            callbacks.onPipelineError("audio", "Audio encoder initialization failed.")
            false
        }
    }

    fun startThread() {
        thread = Thread({ runLoop() }, "livehead-audio").apply {
            isDaemon = true
            start()
        }
    }

    fun sequenceHeader(timestampMs: Int): FlvTag? {
        val asc = audioSpecificConfig ?: return null
        return FlvMuxer.aacSequenceHeader(asc, timestampMs)
    }

    fun cancel() {
        cancelled = true
        feeding = false
    }

    fun joinThread(ms: Long = 2500) {
        try { thread?.join(ms) } catch (ignore: InterruptedException) {}
    }

    fun release() {
        try { decoder?.stop() } catch (ignore: Throwable) {}
        try { decoder?.release() } catch (ignore: Throwable) {}
        try { encoder?.stop() } catch (ignore: Throwable) {}
        try { encoder?.release() } catch (ignore: Throwable) {}
        try { extractor?.release() } catch (ignore: Throwable) {}
        decoder = null
        encoder = null
        extractor = null
    }

    // ---------------------------------------------------------------------

    private fun runLoop() {
        try {
            if (hasSourceTrack && decoder != null) trackLoop() else silenceLoop()
        } catch (c: CancelledException) {
            // normal on stop
        } catch (t: Throwable) {
            AppLog.e(TAG, "audio pipeline failed: ${t.message ?: t.javaClass.simpleName}")
            callbacks.onPipelineError("audio", "Audio processing failed.")
        } finally {
            finished = true
            callbacks.onPipelineFinished("audio")
        }
    }

    // ------------------------ real track ------------------------

    private fun trackLoop() {
        val ext = extractor!!
        val dec = decoder!!
        val info = MediaCodec.BufferInfo()
        var inputEosQueued = false
        var decoderEos = false
        var realRate = srcRate
        var realChannels = srcChannels

        while (!cancelled) {
            var busy = false

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

            val outIdx = dec.dequeueOutputBuffer(info, if (decoderEos) 0L else TIMEOUT_US)
            if (outIdx >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    dec.releaseOutputBuffer(outIdx, false)
                    decoderEos = true
                } else if (info.size > 0) {
                    val buf = dec.getOutputBuffer(outIdx)!!
                    val pcm = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(pcm)
                    val ptsUs = info.presentationTimeUs
                    dec.releaseOutputBuffer(outIdx, false)

                    if (!encoderReady && !createEncoderFromDecoder(realRate, realChannels)) {
                        return
                    }
                    feedPcm(pcm, ptsUs)
                    busy = true
                } else {
                    dec.releaseOutputBuffer(outIdx, false)
                }
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = dec.outputFormat
                realRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, srcRate)
                realChannels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, srcChannels)
                if (!encoderReady && !createEncoderFromDecoder(realRate, realChannels)) {
                    return
                }
            }

            drainEncoder()

            if (decoderEos) {
                if (loopEnabled) {
                    // fill the remaining iteration with silence, then restart
                    fillSilenceToBoundary()
                    val ts = pacer.awaitSend(iterOffsetUs + loopLengthUs)
                    if (ts < 0) throw CancelledException()
                    iterOffsetUs += loopLengthUs
                    try {
                        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        dec.flush()
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "audio loop restart hiccup")
                    }
                    inputEosQueued = false
                    decoderEos = false
                } else {
                    drainEncoderFinal()
                    return
                }
            }

            if (!busy) Thread.sleep(2)
        }
        drainEncoderFinal()
    }

    private fun createEncoderFromDecoder(rate: Int, channels: Int): Boolean =
        createEncoder(rate, channels)

    /**
     * Decoded PCM enters the AAC encoder through a bounded one-slot queue:
     * if the encoder is momentarily busy we hold ONE chunk (drop-oldest)
     * instead of unbounded buffering, so memory stays constant and audio
     * gaps stay in the low milliseconds even under load.
     */
    private var pendingPcm: ByteArray? = null
    private var pendingPtsUs = 0L

    private fun feedPcm(pcm: ByteArray, ptsUs: Long) {
        pendingPcm = pcm
        pendingPtsUs = ptsUs
        flushPendingPcm()
    }

    private fun flushPendingPcm() {
        val pcm = pendingPcm ?: return
        val enc = encoder ?: return
        val inIdx = enc.dequeueInputBuffer(TIMEOUT_US)
        if (inIdx < 0) return
        val buf = enc.getInputBuffer(inIdx)!!
        buf.clear()
        val n = minOf(pcm.size, buf.capacity())
        buf.put(pcm, 0, n)
        val streamPts = pendingPtsUs - trackStartUs + iterOffsetUs
        enc.queueInputBuffer(inIdx, 0, n, streamPts, 0)
        pendingPcm = null
    }

    // ------------------------ silence ------------------------

    private fun silenceLoop() {
        val frameBytes = 1024 * 2 * encChannels
        while (!cancelled) {
            feedSilenceFrame(frameBytes)
            drainEncoder()
            silencePtsUs += frameDurUs
            if (silencePtsUs >= loopLengthUs) {
                if (!loopEnabled) {
                    drainEncoderFinal()
                    return
                }
                val ts = pacer.awaitSend(iterOffsetUs + loopLengthUs)
                if (ts < 0) throw CancelledException()
                iterOffsetUs += loopLengthUs
                silencePtsUs = 0
            }
        }
        drainEncoderFinal()
    }

    private fun fillSilenceToBoundary() {
        val frameBytes = 1024 * 2 * encChannels
        val target = iterOffsetUs + loopLengthUs
        while (!cancelled) {
            val current = iterOffsetUs + silencePtsUs
            if (current + frameDurUs >= target) break
            feedSilenceFrame(frameBytes)
            drainEncoder()
            silencePtsUs += frameDurUs
        }
        silencePtsUs = 0
    }

    private fun feedSilenceFrame(frameBytes: Int) {
        val enc = encoder ?: return
        val inIdx = enc.dequeueInputBuffer(TIMEOUT_US)
        if (inIdx < 0) return
        val buf = enc.getInputBuffer(inIdx)!!
        buf.clear()
        val silence = ByteArray(frameBytes.coerceAtMost(buf.capacity()))
        buf.put(silence)
        val pts = iterOffsetUs + silencePtsUs
        enc.queueInputBuffer(inIdx, 0, silence.size, pts, 0)
    }

    // ------------------------ encoder drain ------------------------

    private fun drainEncoder(final: Boolean = false) {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            flushPendingPcm()
            val i = enc.dequeueOutputBuffer(info, if (final) 20_000L else TIMEOUT_US)
            if (i >= 0) {
                val buf = enc.getOutputBuffer(i)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    val csd = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(csd)
                    audioSpecificConfig = csd
                    enc.releaseOutputBuffer(i, false)
                    sendSequenceHeader()
                } else if (info.size > 0) {
                    val aac = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(aac)
                    enc.releaseOutputBuffer(i, false)
                    sendAac(aac, info.presentationTimeUs)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    continue
                } else {
                    enc.releaseOutputBuffer(i, false)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                enc.outputFormat.getByteBuffer("csd-0")?.let { b ->
                    val csd = ByteArray(b.remaining())
                    b.get(csd)
                    audioSpecificConfig = csd
                    sendSequenceHeader()
                }
            } else {
                return
            }
            if (!final && cancelled) return
        }
    }

    private fun drainEncoderFinal() {
        if (encoder != null) {
            try {
                encoder!!.signalEndOfInputStream()
            } catch (ignore: Throwable) {
            }
            drainEncoder(final = true)
        }
    }

    private fun sendSequenceHeader() {
        val asc = audioSpecificConfig ?: return
        try {
            val ts = kotlin.math.max(pacer.lastStampMs(), 0L).toInt()
            sink.send(FlvMuxer.aacSequenceHeader(asc, ts))
        } catch (c: CancelledException) {
            throw c
        } catch (t: Throwable) {
            // dropped during reconnect; engine resends on reconnect
        }
    }

    private fun sendAac(aac: ByteArray, presentationTimeUs: Long) {
        val ts = pacer.awaitSend(presentationTimeUs)
        if (ts < 0) throw CancelledException()
        try {
            sink.send(FlvMuxer.aacFrame(aac, ts))
            counters.audioFramesSent.incrementAndGet()
            counters.audioClockUs.set(presentationTimeUs)
        } catch (c: CancelledException) {
            throw c
        } catch (t: Throwable) {
            // dropped; next frame carries on
        }
    }
}
