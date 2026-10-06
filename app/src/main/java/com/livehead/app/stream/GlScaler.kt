package com.livehead.app.stream

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import android.graphics.SurfaceTexture
import com.livehead.app.core.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * GPU pass-through scaler between the decoder surface and the encoder input
 * surface.
 *
 * decoder → SurfaceTexture (src size) → GL quad → encoder input surface (dst size)
 *
 * Frame timestamps are preserved end-to-end: the pipeline stamps each decoder
 * output with its final STREAM timestamp (via releaseOutputBuffer(index, ns)),
 * SurfaceTexture.getTimestamp() returns that exact value, and
 * eglPresentationTimeANDROID hands it to the encoder — so encoded PTS stay
 * monotonic across loops.
 */
class GlScaler(
    private val srcWidth: Int,
    private val srcHeight: Int,
    private val dstWidth: Int,
    private val dstHeight: Int,
    private val outputSurface: Surface,
) {
    private var thread: Thread? = null
    private var started = false

    @Volatile private var stopped = false
    private val pendingFrames = AtomicInteger(0)
    private lateinit var surfaceTexture: SurfaceTexture
    @Volatile var inputSurface: Surface? = null
        private set

    private val startLatch = CountDownLatch(1)
    @Volatile private var startError: Exception? = null

    fun start(): Surface {
        check(!started) { "already started" }
        thread = Thread({ glLoop() }, "livehead-glscaler").apply { start() }
        startLatch.await()
        startError?.let { throw it }
        started = true
        return inputSurface ?: throw IllegalStateException("scaler did not start")
    }

    fun stop() {
        if (!started) return
        stopped = true
        thread?.join(3000)
        started = false
    }

    // ---------------------------------------------------------------------

    private fun glLoop() {
        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var texId = 0
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay === EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no EGL display")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw IllegalStateException("eglInitialize failed")
            }

            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, configs.size, numConfigs, 0)
                || numConfigs[0] < 0
            ) {
                throw IllegalStateException("eglChooseConfig failed")
            }

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext === EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed")

            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputSurface, surfAttribs, 0)
            if (eglSurface === EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface failed")

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw IllegalStateException("eglMakeCurrent failed")
            }

            // External OES texture fed by SurfaceTexture
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            texId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            surfaceTexture = SurfaceTexture(texId)
            surfaceTexture.setDefaultBufferSize(srcWidth, srcHeight)
            surfaceTexture.setOnFrameAvailableListener({
                pendingFrames.incrementAndGet()
            })
            val surface = Surface(surfaceTexture)
            inputSurface = surface

            val program = buildProgram()
            val aPos = GLES20.glGetAttribLocation(program, "aPosition")
            val aUv = GLES20.glGetAttribLocation(program, "aUv")
            val uTex = GLES20.glGetUniformLocation(program, "uTex")
            val uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")

            val texMatrix = FloatArray(16)

            startLatch.countDown()

            while (!stopped) {
                if (pendingFrames.get() <= 0) {
                    Thread.sleep(4)
                    continue
                }
                pendingFrames.decrementAndGet()

                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(texMatrix)
                val timestampNs = surfaceTexture.timestamp

                GLES20.glViewport(0, 0, dstWidth, dstHeight)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                GLES20.glUniform1i(uTex, 0)
                GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

                // full-screen quad
                val quad = floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 0f, 1f,
                    -1f, 1f, 1f, 0f,
                    1f, 1f, 1f, 1f,
                )
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quadToBuffer(quad, 0))
                GLES20.glEnableVertexAttribArray(aUv)
                GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quadToBuffer(quad, 2))

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    throw IllegalStateException("eglSwapBuffers failed")
                }
                // drop any extra queued frames to stay realtime
                while (pendingFrames.get() > 1) {
                    pendingFrames.decrementAndGet()
                    surfaceTexture.updateTexImage()
                }
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "GL scaler failed: ${t.message ?: t.javaClass.simpleName}")
            if (startLatch.count == 1L) {
                startError = IllegalStateException("GPU scaler init failed", t)
                startLatch.countDown()
            }
        } finally {
            try { inputSurface?.release() } catch (ignore: Throwable) {}
            try { if (this::surfaceTexture.isInitialized) surfaceTexture.release() } catch (ignore: Throwable) {}
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglTerminate(eglDisplay)
            }
        }
    }

    private fun quadToBuffer(quad: FloatArray, offset: Int): java.nio.FloatBuffer {
        val bb = java.nio.ByteBuffer.allocateDirect(16)
        bb.order(java.nio.ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        for (i in 0 until 4) {
            fb.put(quad[i * 4 + offset])
            fb.put(quad[i * 4 + offset + 1])
        }
        fb.position(0)
        return fb
    }

    private fun buildProgram(): Int {
        val vs = """
            attribute vec4 aPosition;
            attribute vec2 aUv;
            uniform mat4 uTexMatrix;
            varying vec2 vUv;
            void main() {
                gl_Position = aPosition;
                vUv = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vUv);
            }
        """.trimIndent()
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            throw IllegalStateException("program link failed: " + GLES20.glGetProgramInfoLog(p))
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            throw IllegalStateException("shader compile failed: " + GLES20.glGetShaderInfoLog(s))
        }
        return s
    }

    companion object {
        private const val TAG = "GlScaler"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
