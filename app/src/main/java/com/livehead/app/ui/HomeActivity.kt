package com.livehead.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import com.livehead.app.R
import com.livehead.app.controller.StreamingController
import com.livehead.app.core.AppLog
import com.livehead.app.core.Fmt
import com.livehead.app.data.BitrateChoice
import com.livehead.app.data.EngineState
import com.livehead.app.data.EngineStats
import com.livehead.app.data.FpsChoice
import com.livehead.app.data.PrefsConfigRepository
import com.livehead.app.data.ResolutionChoice
import com.livehead.app.data.SecureStore
import com.livehead.app.data.StreamSettings
import com.livehead.app.data.UploadHealth
import com.livehead.app.service.StreamingService
import com.livehead.app.stream.rtmp.RtmpEndpoint

class HomeActivity : Activity() {

    companion object {
        private const val TAG = "Home"
        private const val REQ_PICK_VIDEO = 41
        private const val REQ_NOTIFICATIONS = 42
    }

    private lateinit var config: PrefsConfigRepository

    // views
    private lateinit var statusPill: TextView
    private lateinit var flipper: ViewFlipper
    private lateinit var btnSelectVideo: Button
    private lateinit var preview: TextureView
    private lateinit var previewControls: LinearLayout
    private lateinit var btnPreviewToggle: Button
    private lateinit var videoName: TextView
    private lateinit var videoDetails: TextView
    private lateinit var inputUrl: EditText
    private lateinit var inputKey: EditText
    private lateinit var btnKeyVisibility: ImageButton
    private lateinit var switchRememberKey: Switch
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerFps: Spinner
    private lateinit var spinnerBitrate: Spinner
    private lateinit var switchLoop: Switch
    private lateinit var cardBattery: LinearLayout
    private lateinit var btnBatteryOpt: Button
    private lateinit var btnStart: Button
    private lateinit var liveVideoName: TextView
    private lateinit var liveStatsGrid: GridLayout
    private lateinit var btnStop: Button
    private lateinit var bannerError: TextView
    private lateinit var bannerUpload: TextView
    private lateinit var bannerThermal: TextView
    private lateinit var bannerBattery: TextView

    // preview player
    private var previewPlayer: MediaPlayer? = null
    private var previewing = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var unsubscribe: (() -> Unit)? = null
    private var selectedUri: Uri? = null
    private var selectedMeta: VideoSelection? = null

    private data class VideoSelection(
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val hasAudio: Boolean,
    )

    // ---------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = PrefsConfigRepository(this)
        setContentView(R.layout.activity_home)
        bindViews()
        restoreState()
        wireActions()
        maybeRequestNotificationPermission()
        observeController()
    }

    private fun bindViews() {
        statusPill = findViewById(R.id.status_pill)
        flipper = findViewById(R.id.flipper)
        btnSelectVideo = findViewById(R.id.btn_select_video)
        preview = findViewById(R.id.preview)
        previewControls = findViewById(R.id.preview_controls)
        btnPreviewToggle = findViewById(R.id.btn_preview_toggle)
        videoName = findViewById(R.id.video_name)
        videoDetails = findViewById(R.id.video_details)
        inputUrl = findViewById(R.id.input_url)
        inputKey = findViewById(R.id.input_key)
        btnKeyVisibility = findViewById(R.id.btn_key_visibility)
        switchRememberKey = findViewById(R.id.switch_remember_key)
        spinnerResolution = findViewById(R.id.spinner_resolution)
        spinnerFps = findViewById(R.id.spinner_fps)
        spinnerBitrate = findViewById(R.id.spinner_bitrate)
        switchLoop = findViewById(R.id.switch_loop)
        cardBattery = findViewById(R.id.card_battery)
        btnBatteryOpt = findViewById(R.id.btn_battery_opt)
        btnStart = findViewById(R.id.btn_start)
        liveVideoName = findViewById(R.id.live_video_name)
        liveStatsGrid = findViewById(R.id.live_stats_grid)
        btnStop = findViewById(R.id.btn_stop)
        bannerError = findViewById(R.id.banner_error)
        bannerUpload = findViewById(R.id.banner_upload)
        bannerThermal = findViewById(R.id.banner_thermal)
        bannerBattery = findViewById(R.id.banner_battery)
        findViewById<Button>(R.id.btn_stop)
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btn_diagnostics).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
    }

    private fun restoreState() {
        val s = config.settings
        inputUrl.setText(config.streamUrl)
        switchRememberKey.isChecked = config.rememberKey
        if (config.rememberKey) {
            SecureStore.load(this)?.let { inputKey.setText(it) }
        }
        switchLoop.isChecked = s.loop

        spinnerResolution.adapter = simpleAdapter(ResolutionChoice.values().map { it.label })
        spinnerResolution.setSelection(s.resolution.ordinal)
        spinnerFps.adapter = simpleAdapter(FpsChoice.values().map { it.label })
        spinnerFps.setSelection(s.fps.ordinal)
        spinnerBitrate.adapter = simpleAdapter(BitrateChoice.values().map { it.label })
        spinnerBitrate.setSelection(s.videoBitrate.ordinal)

        val uriStr = config.videoUri
        if (uriStr != null) {
            try {
                val u = Uri.parse(uriStr)
                contentResolver.takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                probeAndShow(u, config.videoName ?: "video", silent = true)
            } catch (ignore: Throwable) {
            }
        }
        updateBatteryCard()
    }

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.item_spinner_selected, items).apply {
            setDropDownViewResource(R.layout.item_spinner)
        }

    private fun wireActions() {
        btnSelectVideo.setOnClickListener { pickVideo() }
        btnPreviewToggle.setOnClickListener { togglePreview() }
        btnKeyVisibility.setOnClickListener { toggleKeyVisibility() }
        btnStart.setOnClickListener { validateAndStart() }
        btnStop.setOnClickListener {
            StreamingController.stopStream(this)
        }

        switchRememberKey.setOnCheckedChangeListener { _, checked ->
            config.rememberKey = checked
            if (checked) {
                inputKey.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                    if (!SecureStore.save(this, it)) {
                        toast("Secure storage unavailable — key kept for this session only.")
                    }
                }
            } else {
                SecureStore.clear(this)
            }
        }
        inputKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistKeyIfRequested()
        }
    }

    private fun persistKeyIfRequested() {
        if (config.rememberKey) {
            inputKey.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                SecureStore.save(this, it)
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Without notification permission the LIVE status is hidden, but streaming still works.")
        }
    }

    // ---------------------------------------------------------------------
    // Video picking + preview
    // ---------------------------------------------------------------------

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
        }
        startActivityForResult(intent, REQ_PICK_VIDEO)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_VIDEO) return
        if (resultCode != RESULT_OK || data == null) return
        val uri = data.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (ignore: Throwable) {
            // not all providers are persistable; the session still works
        }
        probeAndShow(uri, queryName(uri), silent = false)
    }

    private fun queryName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/') ?: "video"

    private fun probeAndShow(uri: Uri, name: String, silent: Boolean) {
        stopPreview()
        val meta = probe(uri)
        if (meta == null) {
            if (!silent) toast(getString(R.string.err_video_unreadable))
            videoName.text = name
            videoDetails.text = getString(R.string.err_video_unreadable)
            selectedUri = null
            selectedMeta = null
            return
        }
        selectedUri = uri
        selectedMeta = meta
        config.videoUri = uri.toString()
        config.videoName = name
        videoName.text = name
        videoDetails.text = buildString {
            append(Fmt.duration(meta.durationMs))
            append("  •  ${meta.width}×${meta.height}")
            append(if (meta.hasAudio) "  •  audio ✓" else "  •  silent (AAC silence will be added)")
        }
        preview.visibility = View.VISIBLE
        previewControls.visibility = View.VISIBLE
        btnPreviewToggle.text = getString(R.string.preview)
        previewing = false
    }

    private fun probe(uri: Uri): VideoSelection? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(this, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            r.release()
            if (w <= 0 || h <= 0 || dur <= 0) null
            else VideoSelection(uri, queryName(uri), dur, w, h, hasAudio)
        } catch (t: Throwable) {
            AppLog.w(TAG, "probe failed: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun togglePreview() {
        if (previewing) stopPreview()
        else startPreview()
    }

    private fun startPreview() {
        val uri = selectedUri ?: return
        if (preview.surfaceTexture == null) {
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: android.graphics.SurfaceTexture, w: Int, h: Int
                ) {
                    startPreviewOn(Surface(surface))
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: android.graphics.SurfaceTexture, w: Int, h: Int
                ) {}

                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
            }
            return
        }
        startPreviewOn(Surface(preview.surfaceTexture))
    }

    private fun startPreviewOn(surface: Surface) {
        stopPreview()
        try {
            val player = MediaPlayer()
            player.setDataSource(this, selectedUri!!)
            player.setSurface(surface)
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.setOnPreparedListener { p ->
                p.start()
                previewing = true
                btnPreviewToggle.text = getString(R.string.stop_preview)
            }
            player.setOnErrorListener { _, _, _ ->
                stopPreview()
                true
            }
            player.prepareAsync()
            previewPlayer = player
        } catch (t: Throwable) {
            AppLog.w(TAG, "preview failed: ${t.javaClass.simpleName}")
            toast("Preview unavailable for this video.")
        }
    }

    private fun stopPreview() {
        previewing = false
        btnPreviewToggle.text = getString(R.string.preview)
        try {
            previewPlayer?.let {
                it.stop()
                it.release()
            }
        } catch (ignore: Throwable) {
        }
        previewPlayer = null
    }

    // ---------------------------------------------------------------------
    // Key visibility
    // ---------------------------------------------------------------------

    private var keyVisible = false

    private fun toggleKeyVisibility() {
        keyVisible = !keyVisible
        val sel = inputKey.selectionEnd
        inputKey.inputType = if (keyVisible) {
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputKey.setSelection(sel)
        btnKeyVisibility.setImageResource(if (keyVisible) R.drawable.ic_eye_off else R.drawable.ic_eye)
        btnKeyVisibility.contentDescription = getString(if (keyVisible) R.string.hide else R.string.show)
    }

    // ---------------------------------------------------------------------
    // Battery optimization
    // ---------------------------------------------------------------------

    private fun updateBatteryCard() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        cardBattery.visibility = if (ignoring) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------------
    // Validation + start
    // ---------------------------------------------------------------------

    private fun validateAndStart() {
        stopPreview()
        persistKeyIfRequested()
        config.streamUrl = inputUrl.text.toString().trim()

        val uri = selectedUri
        val meta = selectedMeta
        if (uri == null || meta == null) return fail(getString(R.string.err_no_video))
        if (probe(uri) == null) return fail(getString(R.string.err_video_unreadable))
        if (RtmpEndpoint.parse(inputUrl.text.toString(), inputKey.text.toString()) == null) {
            return fail(
                if (inputKey.text.isNullOrBlank()) getString(R.string.err_no_key)
                else getString(R.string.err_bad_url)
            )
        }
        if (!networkAvailable()) return fail(getString(R.string.err_no_internet))

        val settings = StreamSettings(
            resolution = ResolutionChoice.values()[spinnerResolution.selectedItemPosition],
            fps = FpsChoice.values()[spinnerFps.selectedItemPosition],
            videoBitrate = BitrateChoice.values()[spinnerBitrate.selectedItemPosition],
            loop = switchLoop.isChecked,
            reconnectEnabled = config.settings.reconnectEnabled,
            maxRetryIntervalSec = config.settings.maxRetryIntervalSec,
            keyframeIntervalSec = config.settings.keyframeIntervalSec,
        )
        config.settings = settings

        StreamingController.startStream(
            this,
            uri.toString(),
            inputUrl.text.toString().trim(),
            inputKey.text.toString().trim(),
            settings,
        )
        toast("Starting stream…")
    }

    private fun fail(message: String) {
        toast(message)
        AppLog.w(TAG, "preflight: $message")
    }

    private fun networkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------------
    // Live state rendering
    // ---------------------------------------------------------------------

    private fun observeController() {
        unsubscribe = StreamingController.state.subscribeWithCurrent {
            mainHandler.post { render(it.stats, it.serviceRunning, it.env) }
        }
    }

    private fun render(stats: EngineStats, serviceRunning: Boolean, env: com.livehead.app.service.StreamingService.Environment? = null) {
        val live = stats.streaming || stats.state == EngineState.CONNECTING ||
            stats.state == EngineState.RECONNECTING || stats.state == EngineState.STALLING

        if (live && flipper.displayedChild == 0) flipper.displayedChild = 1
        if (!live && flipper.displayedChild == 1 &&
            (stats.state == EngineState.STOPPED || stats.state == EngineState.FINISHED ||
                stats.state == EngineState.ERROR || stats.state == EngineState.IDLE)
        ) {
            // switch back to setup a moment after terminal state
            mainHandler.postDelayed({ flipper.displayedChild = 0 }, 2500)
        }

        // status pill
        val (label, color, bg) = when (stats.state) {
            EngineState.STREAMING -> Triple(getString(R.string.status_live), R.color.on_surface, R.drawable.bg_pill_red)
            EngineState.CONNECTING -> Triple(getString(R.string.status_connecting), R.color.on_surface_muted, R.drawable.bg_pill_gray)
            EngineState.RECONNECTING -> Triple(getString(R.string.status_reconnecting), R.color.warn_yellow, R.drawable.bg_pill_yellow)
            EngineState.STALLING -> Triple(getString(R.string.status_stalled), R.color.warn_yellow, R.drawable.bg_pill_yellow)
            EngineState.FINISHED -> Triple(getString(R.string.status_finished), R.color.ok_green, R.drawable.bg_pill_green)
            EngineState.ERROR -> Triple(getString(R.string.status_error), R.color.live_red, R.drawable.bg_pill_red_dim)
            EngineState.STOPPED -> Triple(getString(R.string.status_stopped), R.color.on_surface_muted, R.drawable.bg_pill_gray)
            else -> Triple(getString(R.string.status_offline), R.color.on_surface_muted, R.drawable.bg_pill_gray)
        }
        statusPill.text = label
        statusPill.setTextColor(getColor(color))
        statusPill.setBackgroundResource(bg)

        if (live || stats.state == EngineState.ERROR) {
            liveVideoName.text = selectedMeta?.name ?: config.videoName ?: ""
            renderStatsGrid(stats, env)
            bannerError.visibility = if (stats.state == EngineState.ERROR) View.VISIBLE else View.GONE
            bannerError.text = stats.message ?: getString(R.string.err_connection_failed)
            bannerUpload.visibility =
                if (stats.uploadHealth == UploadHealth.POOR || stats.uploadHealth == UploadHealth.MARGINAL)
                    View.VISIBLE else View.GONE
            bannerThermal.visibility = if (env?.thermalHot == true) View.VISIBLE else View.GONE
            if (env != null && env.batteryPct in 1..20) {
                bannerBattery.visibility = View.VISIBLE
                bannerBattery.text = getString(R.string.battery_low, env.batteryPct)
            } else {
                bannerBattery.visibility = View.GONE
            }
        }

        if (!serviceRunning && stats.state == EngineState.IDLE) {
            updateBatteryCard()
        }
    }

    private fun renderStatsGrid(stats: EngineStats, env: com.livehead.app.service.StreamingService.Environment? = null) {
        liveStatsGrid.removeAllViews()
        addStat("Elapsed", Fmt.duration(stats.elapsedMs))
        addStat("Resolution", if (stats.videoWidth > 0) Fmt.res(stats.videoWidth, stats.videoHeight) else "–")
        addStat("Bitrate", if (stats.actualBitrateBps > 0) Fmt.bitrate(stats.actualBitrateBps) else "–")
        addStat("Network", if (stats.networkAvailable) getString(R.string.connection_stable) else getString(R.string.connection_unstable))
        addStat("Dropped frames", stats.droppedFrames.toString())
        addStat("Reconnects", stats.reconnects.toString())
        addStat("Loops", stats.loopCount.toString())
        addStat("Data sent", if (stats.bytesSent > 0) Fmt.mb(stats.bytesSent) else "–")
        addStat("Battery", env?.batteryPct?.takeIf { it > 0 }?.let { "$it%" } ?: "–")
    }

    private fun addStat(label: String, value: String) {
        val cell = layoutInflater.inflate(R.layout.cell_stat, liveStatsGrid, false)
        cell.findViewById<TextView>(R.id.stat_label).text = label
        cell.findViewById<TextView>(R.id.stat_value).text = value
        liveStatsGrid.addView(cell)
    }

    // ---------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        observeController()
        updateBatteryCard()
    }

    override fun onPause() {
        super.onPause()
        unsubscribe?.invoke()
        unsubscribe = null
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (StreamingController.isStreaming()) {
            toast("Still streaming — stop it from the LIVE screen or notification.")
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }
}
