package com.livehead.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import com.livehead.app.R
import com.livehead.app.controller.StreamingController
import com.livehead.app.core.AppLog
import com.livehead.app.core.Fmt
import com.livehead.app.data.EngineState
import com.livehead.app.data.UploadHealth

class DiagnosticsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var alive = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!alive) return
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_export).setOnClickListener { exportLog() }
    }

    override fun onResume() {
        super.onResume()
        alive = true
        handler.post(ticker)
    }

    override fun onPause() {
        alive = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun render() {
        val s = StreamingController.state.value.stats
        val values = findViewById<TextView>(R.id.diag_values)
        values.text = buildString {
            appendLine("Current state:  ${s.state}" + (s.message?.let { " — $it" } ?: ""))
            appendLine("Encoder:        H.264 (AVC)")
            appendLine("Audio:          AAC ${s.audioBitrateSettingBps / 1000} kbps")
            appendLine("Resolution:     ${if (s.videoWidth > 0) Fmt.res(s.videoWidth, s.videoHeight) else "–"}")
            appendLine("FPS:            ${if (s.fpsSetting > 0) s.fpsSetting else "–"}")
            appendLine("Video bitrate:  ${if (s.videoBitrateSettingBps > 0) Fmt.bitrate(s.videoBitrateSettingBps.toLong()) else "–"}")
            appendLine("Actual bitrate: ${if (s.actualBitrateBps > 0) Fmt.bitrate(s.actualBitrateBps) else "–"}")
            appendLine("Avg bitrate:    ${if (s.avgBitrateBps > 0) Fmt.bitrate(s.avgBitrateBps) else "–"}")
            appendLine("Upload health:  ${s.uploadHealth}")
            appendLine("Packets sent:   ${s.packetsSent}")
            appendLine("Video frames:   ${s.videoFramesSent}")
            appendLine("Audio frames:   ${s.audioFramesSent}")
            appendLine("Dropped frames: ${s.droppedFrames}")
            appendLine("Late packets:   ${s.lateFrames}")
            appendLine("Reconnects:     ${s.reconnects}")
            appendLine("Loop count:     ${s.loopCount}")
            appendLine("Data sent:      ${Fmt.mb(s.bytesSent)}")
            appendLine("Network:        ${if (s.networkAvailable) "Connected" else "Disconnected"}")
            appendLine("Elapsed:        ${Fmt.duration(s.elapsedMs)}")
        }

        findViewById<TextView>(R.id.diag_log).text = AppLog.snapshotText()
    }

    private fun exportLog() {
        val text = "LIVE HEAD diagnostics\n\n" + findViewById<TextView>(R.id.diag_values).text + "\n\n=== LOG ===\n" + AppLog.snapshotText()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_log)))
    }
}
