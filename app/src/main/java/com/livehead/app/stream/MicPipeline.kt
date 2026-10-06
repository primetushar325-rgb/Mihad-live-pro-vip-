package com.livehead.app.stream

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import com.livehead.app.core.AppLog
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Microphone (AudioRecord) → AAC-LC encoder → FLV tags → [MediaSink].
 *
 * Created ONLY by the streaming engine after the user presses START LIVE.
 * Sample-count-based timestamps keep A/V drift near zero: the audio clock is
 * derived from the number of samples actually encoded, not wall-clock reads.
 */
class MicPipeline(
    context: Context,
    private val bitrateBps: Int,
    private val pacer: Pacer,
    private val sink: MediaSink,
    private val callbacks: PipelineCallbacks,
    private val counters: StreamCounters,
) {
    companion object {
        private const val TAG = "MicPipeline"
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        private const val TIMEOUT_US = 10_000L
        private const val CHUNK_SAMPLES = 2048 // ~46 ms of stereo PCM
    }

    private val appContext = context.applicationContext

    private var recorder: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private val cancelled = AtomicBoolean(false)

    @Volatile private var asc: ByteArray? = null
    private val samplesEncoded = AtomicLong(0)
    @Volatile private var lastPtsUs = -1L

    var sampleRateOut: Int = SAMPLE_RATE; private set
    var channelsOut: Int = CHANNELS; private set

    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission") // permission is verified by the caller
    fun prepare() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IOException("Microphone is not available on this device.")
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, CHUNK_SAMPLES * CHANNELS * 2 * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("Microphone is busy or blocked by a privacy switch.")
        }
        recorder = rec

        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_SAMPLES * CHANNELS * 2 * 2)
        }
        val enc = MediaCodec.createEncoderByType("audio/mp4a-latm")
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        encoder = enc
    }

    fun startThread() {
        Thread({ runLoop() }, "livehead-audio").apply {
            isDaemon = true
            start()
        }
    }

    private fun runLoop() {
        val rec = recorder ?: return
        val enc = encoder ?: return
        val pcm = ByteArray(CHUNK_SAMPLES * CHANNELS * 2) // 16-bit stereo
        try {
            rec.startRecording()
            while (!cancelled.get()) {
                // ---- read microphone ----
                val n = rec.read(pcm, 0, pcm.size)
                if (n < 0) throw IOException("Microphone read failed ($n).")
                if (n == 0) continue

                // ---- feed encoder (skip when busy: bounded backpressure) ----
                val inIdx = enc.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val inBuf = enc.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    val put = minOf(n, inBuf.capacity())
                    inBuf.put(pcm, 0, put)
                    val ptsUs = samplesEncoded.get() * 1_000_000L / SAMPLE_RATE
                    enc.queueInputBuffer(inIdx, 0, put, ptsUs, 0)
                    samplesEncoded.addAndGet((put / (CHANNELS * 2)).toLong())
                }
                drain(false)
            }
            // flush the tail
            drain(true)
        } catch (c: CancelledException) {
            // normal shutdown
        } catch (t: Throwable) {
            AppLog.e(TAG, "audio pipeline failed: ${t.javaClass.simpleName}: ${t.message}")
            callbacks.onPipelineError("audio", "Microphone streaming stopped: ${t.message}")
        }
    }

    private fun drain(final: Boolean) {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = enc.dequeueOutputBuffer(info, if (final) 20_000L else 0L)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!final) return
                continue
            }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = enc.outputFormat
                f.getByteBuffer("csd-0")?.let { csd ->
                    asc = ByteArray(csd.remaining()).also { csd.get(it) }
                    sampleRateOut = f.intOr("sample-rate", SAMPLE_RATE)
                    channelsOut = f.intOr("channel-count", CHANNELS)
                }
                continue
            }
            if (i < 0) continue
            val buf = enc.getOutputBuffer(i) ?: continue

            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                asc = ByteArray(info.size).also { buf.get(it) }
                enc.releaseOutputBuffer(i, false)
                continue
            }
            if (info.size > 0) {
                val aac = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(aac)

                var pts = info.presentationTimeUs
                if (pts <= lastPtsUs) pts = lastPtsUs + 1000
                lastPtsUs = pts

                val stamp = pacer.awaitSend(pts)
                if (stamp < 0) { enc.releaseOutputBuffer(i, false); throw CancelledException() }
                val tag = FlvMuxer.aacFrame(aac, stamp)
                try {
                    sink.send(tag)
                    counters.audioFramesSent.incrementAndGet()
                    counters.audioClockUs.set(pts)
                } catch (c: CancelledException) {
                    enc.releaseOutputBuffer(i, false)
                    throw c
                } catch (e: IOException) {
                    // connection down — supervisor is reconnecting; drop frame
                }
            }
            enc.releaseOutputBuffer(i, false)
            if (!final && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    fun sequenceHeader(timestampMs: Int): FlvTag? {
        val a = asc ?: return null
        return FlvMuxer.aacSequenceHeader(a, timestampMs)
    }

    fun cancel() {
        cancelled.set(true)
    }

    fun stopAndRelease() {
        cancelled.set(true)
        try { recorder?.stop() } catch (ignore: Throwable) {}
        try { recorder?.release() } catch (ignore: Throwable) {}
        recorder = null
        try { encoder?.stop() } catch (ignore: Throwable) {}
        try { encoder?.release() } catch (ignore: Throwable) {}
        encoder = null
    }
}
