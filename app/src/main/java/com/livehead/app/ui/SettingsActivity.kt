package com.livehead.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.livehead.app.App
import com.livehead.app.R
import com.livehead.app.data.BitrateChoice
import com.livehead.app.data.FpsChoice
import com.livehead.app.data.PrefsConfigRepository
import com.livehead.app.data.ResolutionChoice
import com.livehead.app.data.SecureStore

class SettingsActivity : Activity() {

    private lateinit var config: PrefsConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = PrefsConfigRepository(this)
        setContentView(R.layout.activity_settings)
        setTitle(R.string.settings_title)

        val s = config.settings

        fun spinner(id: Int, items: List<String>, selected: Int): Spinner {
            val sp = findViewById<Spinner>(id)
            sp.adapter = ArrayAdapter(this, R.layout.item_spinner_selected, items).apply {
                setDropDownViewResource(R.layout.item_spinner)
            }
            sp.setSelection(selected)
            return sp
        }

        val spRes = spinner(
            R.id.spinner_default_resolution,
            ResolutionChoice.values().map { it.label }, s.resolution.ordinal
        )
        val spFps = spinner(R.id.spinner_default_fps, FpsChoice.values().map { it.label }, s.fps.ordinal)
        val spVbr = spinner(
            R.id.spinner_default_bitrate,
            BitrateChoice.values().map { it.label }, s.videoBitrate.ordinal
        )
        val spKeyframe = spinner(R.id.spinner_keyframe, listOf("1", "2", "3", "4"), s.keyframeIntervalSec - 1)
        val swLoop = findViewById<Switch>(R.id.switch_loop_default).apply { isChecked = s.loop }
        val swReconnect = findViewById<Switch>(R.id.switch_reconnect).apply { isChecked = s.reconnectEnabled }
        val spMaxRetry = spinner(
            R.id.spinner_max_retry,
            listOf("15", "30", "60", "120"),
            listOf("15", "30", "60", "120").indexOf(s.maxRetryIntervalSec.toString()).coerceAtLeast(0)
        )

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<Button>(R.id.btn_battery_guide).setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.btn_clear_key).setOnClickListener {
            SecureStore.clear(this)
            toast(getString(R.string.key_cleared))
        }
        findViewById<Button>(R.id.btn_view_diagnostics).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        findViewById<TextView>(R.id.about_version).text =
            "${getString(R.string.app_version)}: ${App.VERSION_NAME} (${App.VERSION_CODE})"

        // persist on close
        val persist = {
            config.settings = s.copy(
                resolution = ResolutionChoice.values()[spRes.selectedItemPosition],
                fps = FpsChoice.values()[spFps.selectedItemPosition],
                videoBitrate = BitrateChoice.values()[spVbr.selectedItemPosition],
                keyframeIntervalSec = spKeyframe.selectedItemPosition + 1,
                loop = swLoop.isChecked,
                reconnectEnabled = swReconnect.isChecked,
                maxRetryIntervalSec = listOf(15, 30, 60, 120)[spMaxRetry.selectedItemPosition],
            )
        }
        // persist immediately on each change for robustness
        listOf(spRes, spFps, spVbr, spKeyframe, spMaxRetry).forEach {
            it.onItemSelectedListener = object : SimpleListener() {
                override fun onPicked() = persist()
            }
        }
        swLoop.setOnCheckedChangeListener { _, _ -> persist() }
        swReconnect.setOnCheckedChangeListener { _, _ -> persist() }
    }

    private fun openBatterySettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (t: Throwable) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (t2: Throwable) {
                    toast("Open Settings → Battery → Unrestricted for LIVE HEAD.")
                }
            }
        } else {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t: Throwable) {
            }
            toast("Battery optimization is already unrestricted.")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private abstract class SimpleListener : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        override fun onItemSelected(
            parent: android.widget.AdapterView<*>?,
            view: android.view.View?,
            position: Int,
            id: Long
        ) {
            onPicked()
        }

        abstract fun onPicked()
    }
}
