package com.livehead.app.stream

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.graphics.SurfaceTexture
import android.view.Surface
import com.livehead.app.core.AppLog
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera → H.264 encoder (Surface input) → FLV tags → [MediaSink].
 *
 * Created ONLY by the streaming engine after the user presses START LIVE —
 * never during app startup. The camera is opened in [prepare], frames flow
 * in [startThread]'s drain loop, and the pacer enforces real-time pacing
 * (a stalled network freezes sending; the encoder then naturally drops late
 * camera frames instead of buffering unboundedly — the anti-freeze rule).
 *
 * Timestamps: encoder presentation times are rebased so the first frame is
 * ~0 and strictly monotonic; the [Pacer] owns all further normalization.
 */
class CameraPipeline(
    context: Context,
    private val targetLongSide: Int,
    private val bitrateBps: Int,
    private val fps: Int,
    private val keyframeIntervalSec: Int,
    private val pacer: Pacer,
    private val sink: MediaSink,
    private val callbacks: PipelineCallbacks,
    private val counters: StreamCounters,
) {
    companion object {
        private const val TAG = "CameraPipeline"
        private const val TIMEOUT_US = 10_000L
    }

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var thread: Thread? = null
    private val cancelled = AtomicBoolean(false)
    private val opened = AtomicBoolean(false)

    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile private var firstPtsUs = -1L
    @Volatile private var lastPtsUs = -1L

    var widthOut: Int = 0; private set
    var heightOut: Int = 0; private set
    val isOpened: Boolean get() = opened.get()

    // ---------------------------------------------------------------------
    // Prepare — runs on the engine thread BEFORE any connection attempt so
    // camera/encoder failures surface as friendly errors, not mid-stream.
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission") // permission is verified by the caller
    fun prepare(previewSurface: Surface? = null) {
        val (cameraId, size) = pickCamera()
        widthOut = size.width
        heightOut = size.height
        AppLog.i(TAG, "camera: $cameraId ${size.width}x${size.height}, encoder vbr=${bitrateBps / 1000}kbps")

        cameraThread = HandlerThread("livehead-camera").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        // Encoder ------------------------------------------------------
        val format = MediaFormat.createVideoFormat("video/avc", size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSec.coerceIn(1, 6))
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val enc = MediaCodec.createEncoderByType("video/avc")
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        encoder = enc
        encoderSurface = surface

        // Camera -------------------------------------------------------
        val openLatch = CountDownLatch(1)
        var openError: Throwable? = null
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                openLatch.countDown()
            }

            override fun onDisconnected(device: CameraDevice) {
                openError = IOException("Camera became unavailable.")
                openLatch.countDown()
            }

            override fun onError(device: CameraDevice, error: Int) {
                openError = IOException("Camera error ($error). Close other camera apps and try again.")
                openLatch.countDown()
            }
        }, cameraHandler)

        if (!openLatch.await(8, TimeUnit.SECONDS)) {
            throw IOException("Camera did not open in time. Close other camera apps and try again.")
        }
        openError?.let { throw it }

        // Capture session ----------------------------------------------
        val device = camera ?: throw IOException("Camera is not available.")
        val targets = ArrayList<Surface>(2)
        targets.add(surface)
        previewSurface?.let { targets.add(it) }

        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            previewSurface?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            try {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            } catch (ignore: Throwable) { /* leave default range */ }
        }

        val sessionLatch = CountDownLatch(1)
        var sessionError: Throwable? = null
        device.createCaptureSession(
            targets,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    try {
                        s.setRepeatingRequest(request.build(), null, cameraHandler)
                        session = s
                    } catch (t: Throwable) {
                        sessionError = t
                    }
                    sessionLatch.countDown()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    sessionError = IOException("Camera configuration failed.")
                    sessionLatch.countDown()
                }
            },
            cameraHandler,
        )
        if (!sessionLatch.await(8, TimeUnit.SECONDS)) {
            throw IOException("Camera configuration timed out.")
        }
        sessionError?.let { throw it }

        opened.set(true)
    }

    private fun pickCamera(): Pair<String, android.util.Size> {
        var bestId: String? = null
        for (id in cameraManager.cameraIdList) {
            val ch = cameraManager.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                bestId = id
                break
            }
        }
        val id = bestId ?: cameraManager.cameraIdList.firstOrNull()
            ?: throw IOException("No camera found on this device.")
        val ch = cameraManager.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IOException("Camera capabilities are not readable.")
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            .sortedWith(compareByDescending<android.util.Size> { minOf(it.width, it.height) })
        // choose the largest size that fits the target long side and is 16:9-ish
        val candidate = sizes.firstOrNull {
            maxOf(it.width, it.height) <= targetLongSide &&
                maxOf(it.width, it.height) >= targetLongSide / 2
        } ?: sizes.firstOrNull { maxOf(it.width, it.height) <= 1920 } ?: sizes.first()
        return id to candidate
    }

    // ---------------------------------------------------------------------
    // Drain loop
    // ---------------------------------------------------------------------

    fun startThread() {
        thread = Thread({ drainLoop() }, "livehead-video").apply {
            isDaemon = true
            start()
        }
    }

    private fun drainLoop() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (!cancelled.get()) {
                val i = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = enc.outputFormat
                    val s = f.getByteBuffer("csd-0")
                    val p = f.getByteBuffer("csd-1")
                    if (s != null && p != null) {
                        sps = ByteArray(s.remaining()).also { s.get(it) }
                        pps = ByteArray(p.remaining()).also { p.get(it) }
                    }
                    continue
                }
                if (i < 0) continue

                val buf = enc.getOutputBuffer(i) ?: continue
                if (info.size > 0) {
                    val raw = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(raw)

                    val avcc = FlvMuxer.annexBToAvcc(raw, raw.size)

                    // CSD may also arrive in-band before the format change
                    if (avcc.sps != null && avcc.pps != null) { sps = avcc.sps; pps = avcc.pps }

                    if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                    var pts = info.presentationTimeUs - firstPtsUs
                    if (pts <= lastPtsUs) pts = lastPtsUs + 1000 // strictly monotonic
                    lastPtsUs = pts

                    val stamp = pacer.awaitSend(pts)
                    if (stamp < 0) { enc.releaseOutputBuffer(i, false); break }
                    val keyframe = avcc.hasIdr ||
                        (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val tag = FlvMuxer.avcFrame(avcc.avcc, stamp, keyframe)
                    try {
                        sink.send(tag)
                        counters.videoFramesSent.incrementAndGet()
                    } catch (e: CancelledException) {
                        enc.releaseOutputBuffer(i, false)
                        throw e
                    } catch (e: IOException) {
                        counters.videoDropped.incrementAndGet()
                    }
                }
                enc.releaseOutputBuffer(i, false)
            }
        } catch (c: CancelledException) {
            // normal shutdown
        } catch (t: Throwable) {
            AppLog.e(TAG, "video drain failed: ${t.javaClass.simpleName}: ${t.message}")
            counters.videoDropped.incrementAndGet()
            callbacks.onPipelineError("video", "Camera encoding stopped: ${t.message}")
        }
    }

    // ---------------------------------------------------------------------
    // Engine-facing helpers
    // ---------------------------------------------------------------------

    fun sequenceHeader(timestampMs: Int): FlvTag? {
        val s = sps ?: return null
        val p = pps ?: return null
        return FlvMuxer.avcSequenceHeader(s, p, timestampMs)
    }

    fun requestKeyframe() {
        try {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (ignore: Throwable) {}
    }

    fun cancel() {
        cancelled.set(true)
    }

    fun stopAndRelease() {
        cancelled.set(true)
        try { thread?.join(2500) } catch (ignore: Throwable) {}
        try { session?.stopRepeating() } catch (ignore: Throwable) {}
        try { session?.close() } catch (ignore: Throwable) {}
        session = null
        try { camera?.close() } catch (ignore: Throwable) {}
        camera = null
        try { encoder?.stop() } catch (ignore: Throwable) {}
        try { encoder?.release() } catch (ignore: Throwable) {}
        encoder = null
        try { encoderSurface?.release() } catch (ignore: Throwable) {}
        encoderSurface = null
        try { cameraThread?.quitSafely() } catch (ignore: Throwable) {}
        cameraThread = null
        cameraHandler = null
    }
}
