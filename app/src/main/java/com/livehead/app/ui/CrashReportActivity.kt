package com.livehead.app.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Emergency error screen, shown automatically when the app crashes.
 *
 * Deliberately uses ONLY programmatic framework views (no XML layouts, no
 * styles, no drawables, no app resources) so it can render even if the
 * crash was caused by a resource or theme problem. The full stack trace is
 * selectable so it can be copied and reported without adb.
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        REPORT_SHOWING.set(true)
        try {
            val report = intent.getStringExtra(EXTRA_REPORT)
                ?: readReport()
                ?: "No crash report found."

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF0D1117.toInt())
            }

            val title = TextView(this).apply {
                text = "LIVE HEAD — problem report"
                setTextColor(0xFFFF4655.toInt())
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(20), dp(24), dp(20), dp(8))
            }
            val hint = TextView(this).apply {
                text = "The app hit an unexpected error. The details below can be " +
                    "selected and copied — please send them to the developer.\n"
                setTextColor(0xFF93A1B5.toInt())
                textSize = 13f
                setPadding(dp(20), 0, dp(20), dp(8))
            }
            val body = TextView(this).apply {
                text = report
                setTextColor(0xFFE9EEF6.toInt())
                textSize = 11f
                setTextIsSelectable(true)
                setPadding(dp(20), dp(8), dp(20), dp(24))
            }

            val scroll = ScrollView(this).apply {
                addView(
                    body,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }

            root.addView(title)
            root.addView(hint)
            root.addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            setContentView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        } catch (ignore: Throwable) {
            // absolutely never crash inside the crash reporter
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        REPORT_SHOWING.set(false)
    }

    private fun readReport(): String? = try {
        java.io.File(filesDir, "last_crash.txt").takeIf { it.exists() }?.readText()
    } catch (ignore: Throwable) {
        null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_REPORT = "report"

        /** Guards against a crash loop where this screen itself crashes. */
        val REPORT_SHOWING = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
