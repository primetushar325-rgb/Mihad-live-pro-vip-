package com.livehead.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.InputType
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.livehead.app.R
import com.livehead.app.controller.StreamingController
import com.livehead.app.core.AppLog
import com.livehead.app.core.Fmt
import com.livehead.app.data.EngineState
import com.livehead.app.data.PrefsConfigRepository
import com.livehead.app.data.SecureStore
import com.livehead.app.data.StreamSettings
import com.livehead.app.stream.rtmp.RtmpEndpoint

/**
 * Home screen — deliberately minimal (launch reliability is the top goal).
 *
 * Startup does ONLY: inflate layout, restore URL/key, wire buttons.
 * The camera is opened exclusively by the user tapping "Enable camera
 * preview", and the streaming engine is created exclusively by the
 * foreground service after START LIVE. Any startup exception falls back to
 * a programmatic recovery screen instead of crashing.
 */
class HomeActivity : Activity() {

    companion object {
        private const val TAG = "Home"
        private const val REQ_PERMS = 43
    }

    private lateinit var config: PrefsConfigRepository

    private lateinit var statusPill: TextView
    private lateinit var preview: TextureView
    private lateinit var btnPreviewToggle: Button
    private lateinit var inputUrl: EditText
    private lateinit var inputKey: EditText
    private lateinit var btnKeyVisibility: ImageButton
    private lateinit var switchRememberKey: Switch
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var statsLine: TextView
    private lateinit var bannerError: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var unsubscribe: (() -> Unit)? = null

    // ---------------------------------------------------------------------
    // Startup
    // ---------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            config = PrefsConfigRepository(this)
            setContentView(R.layout.activity_home)
            bindViews()
            restoreState()
            wireActions()
            observeController()
        } catch (t: Throwable) {
            AppLog.e(TAG, "startup failed", t)
            showStartupError(t)
        }
    }

    private fun bindViews() {
        statusPill = findViewById(R.id.status_pill)
        preview = findViewById(R.id.preview)
        btnPreviewToggle = findViewById(R.id.btn_preview_toggle)
        inputUrl = findViewById(R.id.input_url)
        inputKey = findViewById(R.id.input_key)
        btnKeyVisibility = findViewById(R.id.btn_key_visibility)
        switchRememberKey = findViewById(R.id.switch_remember_key)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        statsLine = findViewById(R.id.stats_line)
        bannerError = findViewById(R.id.banner_error)
        findViewById<ImageButton>(R.id.btn_diagnostics).setOnClickListener {
            startActivity(android.content.Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
    }

    private fun restoreState() {
        inputUrl.setText(config.streamUrl)
        switchRememberKey.isChecked = config.rememberKey
        if (config.rememberKey) {
            try {
                SecureStore.load(this)?.let { inputKey.setText(it) }
            } catch (ignore: Throwable) {}
        }
    }

    private fun wireActions() {
        btnPreviewToggle.setOnClickListener { toggleCameraPreview() }
        btnKeyVisibility.setOnClickListener { toggleKeyVisibility() }
        btnStart.setOnClickListener { onStartLiveClicked() }
        btnStop.setOnClickListener { StreamingController.stopStream(this) }
        switchRememberKey.setOnCheckedChangeListener { _, checked ->
            config.rememberKey = checked
            if (!checked) {
                try { SecureStore.clear(this) } catch (ignore: Throwable) {}
            } else {
                persistKeyIfRequested()
            }
        }
        findViewById<Button>(R.id.btn_clear_key).setOnClickListener {
            try { SecureStore.clear(this) } catch (ignore: Throwable) {}
            inputKey.setText("")
            toast("Saved stream key cleared")
        }
    }

    private fun observeController() {
        unsubscribe = StreamingController.state.subscribeWithCurrent { ui ->
            mainHandler.post { render(ui.stats, ui.serviceRunning) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (unsubscribe == null) observeController()
    }

    override fun onPause() {
        super.onPause()
        // keep the subscription while live so returning shows fresh state;
        // only the camera preview is released (the stream is service-owned)
        if (!StreamingController.state.value.stats.streaming) {
            closeCameraPreview()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCameraPreview()
        unsubscribe?.invoke()
        unsubscribe = null
    }

    // ---------------------------------------------------------------------
    // Fallback screen — the app must OPEN even if the main layout fails
    // ---------------------------------------------------------------------

    private fun showStartupError(t: Throwable) {
        try {
            val stack = android.util.Log.getStackTraceString(t)
            val scroll = android.widget.ScrollView(this)
            val tv = TextView(this)
            tv.text = "LIVE HEAD started in recovery mode.\n\n" +
                "The main screen could not load:\n\n$stack\n\n" +
                "This text can be selected and copied for a bug report. Try restarting the app."
            tv.textSize = 12f
            tv.setTextColor(0xFFE9EEF6.toInt())
            tv.setTextIsSelectable(true)
            tv.setPadding(48, 64, 48, 48)
            scroll.setBackgroundColor(0xFF0D1117.toInt())
            scroll.addView(tv)
            setContentView(scroll)
        } catch (ignore: Throwable) {}
    }

    // ---------------------------------------------------------------------
    // Camera preview — opened ONLY on user tap
    // ---------------------------------------------------------------------

    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var previewOn = false

    private fun toggleCameraPreview() {
        if (previewOn) closeCameraPreview() else openCameraPreview()
    }

    @SuppressLint("MissingPermission") // checked by caller
    private fun openCameraPreview() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_PERMS)
            return
        }
        val texture = preview.surfaceTexture ?: run {
            toast("Display not ready — tap again")
            return
        }
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var id: String? = null
            for (c in cm.cameraIdList) {
                if (cm.getCameraCharacteristics(c)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                ) { id = c; break }
            }
            if (id == null) id = cm.cameraIdList.firstOrNull() ?: run {
                toast("No camera found")
                return
            }
            val map = cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(SurfaceTexture::class.java)
                ?.maxByOrNull { it.width.toLong() * it.height } ?: run {
                toast("Camera is not available")
                return
            }

            cameraThread = HandlerThread("livehead-preview").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    try {
                        cameraDevice = device
                        texture.setDefaultBufferSize(size.width, size.height)
                        val surface = Surface(texture)
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                        }
                        device.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    try {
                                        s.setRepeatingRequest(request.build(), null, cameraHandler)
                                        cameraSession = s
                                    } catch (t: Throwable) {
                                        AppLog.w(TAG, "preview session failed: ${t.message}")
                                    }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    AppLog.w(TAG, "preview configuration failed")
                                }
                            },
                            cameraHandler,
                        )
                        mainHandler.post {
                            previewOn = true
                            btnPreviewToggle.text = getString(R.string.stop_preview)
                        }
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "preview open failed: ${t.message}")
                    }
                }

                override fun onDisconnected(device: CameraDevice) { device.close() }

                override fun onError(device: CameraDevice, error: Int) {
                    AppLog.w(TAG, "camera error $error")
                    device.close()
                }
            }, cameraHandler)
        } catch (t: Throwable) {
            AppLog.w(TAG, "camera preview failed: ${t.javaClass.simpleName}: ${t.message}")
            toast("Camera preview unavailable")
        }
    }

    private fun closeCameraPreview() {
        try { cameraSession?.stopRepeating() } catch (ignore: Throwable) {}
        try { cameraSession?.close() } catch (ignore: Throwable) {}
        cameraSession = null
        try { cameraDevice?.close() } catch (ignore: Throwable) {}
        cameraDevice = null
        try { cameraThread?.quitSafely() } catch (ignore: Throwable) {}
        cameraThread = null
        cameraHandler = null
        if (previewOn) {
            previewOn = false
            btnPreviewToggle.text = "Enable camera preview"
        }
    }

    // ---------------------------------------------------------------------
    // Key visibility + persistence
    // ---------------------------------------------------------------------

    private var keyVisible = false

    private fun toggleKeyVisibility() {
        keyVisible = !keyVisible
        val sel = inputKey.selectionEnd
        inputKey.inputType = if (keyVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputKey.setSelection(sel)
        btnKeyVisibility.setImageResource(if (keyVisible) R.drawable.ic_eye_off else R.drawable.ic_eye)
    }

    private fun persistKeyIfRequested() {
        if (config.rememberKey) {
            inputKey.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                try { SecureStore.save(this, it) } catch (ignore: Throwable) {}
            }
        }
    }

    // ---------------------------------------------------------------------
    // START LIVE
    // ---------------------------------------------------------------------

    private fun onStartLiveClicked() {
        persistKeyIfRequested()
        config.streamUrl = inputUrl.text.toString().trim()

        val url = inputUrl.text.toString().trim()
        val key = inputKey.text.toString().trim()
        if (RtmpEndpoint.parse(url, key) == null) {
            return fail(
                if (key.isBlank()) getString(R.string.err_no_key)
                else getString(R.string.err_bad_url)
            )
        }

        // The service needs the camera exclusively — release the preview.
        closeCameraPreview()

        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = need.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        startLive(url, key)
    }

    private fun startLive(url: String, key: String) {
        val s = config.settings
        val settings = StreamSettings(
            resolution = s.resolution,
            fps = s.fps,
            videoBitrate = s.videoBitrate,
            audioBitrateBps = s.audioBitrateBps,
            keyframeIntervalSec = s.keyframeIntervalSec,
            loop = false, // live camera has no loop
            reconnectEnabled = s.reconnectEnabled,
            maxRetryIntervalSec = s.maxRetryIntervalSec,
        )
        StreamingController.startStream(this, url, key, settings)
        toast("Starting stream…")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (permissions.contains(Manifest.permission.CAMERA) &&
            grantResults[permissions.indexOf(Manifest.permission.CAMERA)] == PackageManager.PERMISSION_GRANTED &&
            !previewOn
        ) {
            // this grant came from the preview button
            openCameraPreview()
            return
        }
        val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (allGranted && permissions.contains(Manifest.permission.RECORD_AUDIO)) {
            val url = inputUrl.text.toString().trim()
            val key = inputKey.text.toString().trim()
            if (url.isNotBlank() && key.isNotBlank()) startLive(url, key)
        } else if (!allGranted) {
            fail("Camera and microphone permissions are required to go live.")
        }
    }

    private fun fail(message: String) {
        toast(message)
        AppLog.w(TAG, "preflight: $message")
        bannerError.text = message
        bannerError.visibility = View.VISIBLE
        mainHandler.postDelayed({ bannerError.visibility = View.GONE }, 5000)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private fun render(stats: com.livehead.app.data.EngineStats, serviceRunning: Boolean) {
        val (label, color, bg) = when (stats.state) {
            EngineState.PREPARING -> Triple("PREPARING", R.color.warn_yellow, R.drawable.bg_pill_yellow)
            EngineState.CONNECTING -> Triple("CONNECTING", R.color.warn_yellow, R.drawable.bg_pill_yellow)
            EngineState.STREAMING -> Triple("LIVE", R.color.live_red, R.drawable.bg_pill_red)
            EngineState.RECONNECTING -> Triple("RECONNECTING", R.color.warn_yellow, R.drawable.bg_pill_yellow)
            EngineState.STALLING -> Triple("STALLED", R.color.warn_yellow, R.drawable.bg_pill_yellow)
            EngineState.STOPPING -> Triple("STOPPING", R.color.on_surface_muted, R.drawable.bg_pill_gray)
            EngineState.STOPPED -> Triple("STOPPED", R.color.on_surface_muted, R.drawable.bg_pill_gray)
            EngineState.FINISHED -> Triple("FINISHED", R.color.ok_green, R.drawable.bg_pill_green)
            EngineState.ERROR -> Triple("ERROR", R.color.live_red, R.drawable.bg_pill_red_dim)
            else -> Triple("OFFLINE", R.color.on_surface_muted, R.drawable.bg_pill_gray)
        }
        statusPill.text = label
        statusPill.setTextColor(getColor(color))
        statusPill.setBackgroundResource(bg)

        val live = stats.streaming || stats.state == EngineState.CONNECTING ||
            stats.state == EngineState.RECONNECTING || stats.state == EngineState.STALLING
        btnStart.visibility = if (live) View.GONE else View.VISIBLE
        btnStop.visibility = if (live) View.VISIBLE else View.GONE

        bannerError.visibility = if (stats.state == EngineState.ERROR) View.VISIBLE else View.GONE
        if (stats.state == EngineState.ERROR) bannerError.text = stats.message ?: "Streaming error"

        statsLine.text = if (live || stats.elapsedMs > 0) buildString {
            append("Status: $label")
            if (stats.videoWidth > 0) append("\nResolution: ${Fmt.res(stats.videoWidth, stats.videoHeight)} @ ${stats.fpsSetting}fps")
            if (stats.actualBitrateBps > 0) append("\nBitrate: ${Fmt.bitrate(stats.actualBitrateBps)}")
            if (stats.bytesSent > 0) append("\nSent: ${Fmt.mb(stats.bytesSent)}")
            append("\nFrames (v/a): ${stats.videoFramesSent}/${stats.audioFramesSent}")
            append("\nDropped: ${stats.droppedFrames}  Reconnects: ${stats.reconnects}")
            append("\nUptime: ${Fmt.duration(stats.elapsedMs)}")
        } else "Status: $label"
    }
}
