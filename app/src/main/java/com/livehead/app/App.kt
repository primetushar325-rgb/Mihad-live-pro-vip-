package com.livehead.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.livehead.app.core.AppLog
import com.livehead.app.ui.CrashReportActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application entry point.
 *
 * Startup is intentionally featherweight (Phase-4/5 discipline):
 *  - create the notification channel (required for the streaming service)
 *  - install the crash reporter
 * Nothing else — no streaming engine, no network, no MediaCodec, no file
 * scanning. Heavy components are created only inside [com.livehead.app.service.StreamingService]
 * after the user presses START LIVE.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashReporter()
        createNotificationChannels()
    }

    /**
     * Captures every uncaught exception (any thread):
     *  1. writes the full report to files (internal + external app dir),
     *  2. keeps it in the in-memory log ring for the Diagnostics screen,
     *  3. shows [CrashReportActivity] so the user can read/copy the exact
     *     stack trace without adb,
     *  4. then hands over to the platform handler so the process still dies
     *     normally (no zombie states).
     */
    private fun installCrashReporter() {
        val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildReport(thread, throwable)
                writeReport(report)
                AppLog.e(TAG, "uncaught exception:\n$report")
                if (!CrashReportActivity.REPORT_SHOWING.get()) {
                    val intent = Intent(this, CrashReportActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(CrashReportActivity.EXTRA_REPORT, report.take(60_000))
                    startActivity(intent)
                    // give the error screen a moment to come up before the
                    // process is torn down by the platform handler
                    Thread.sleep(500)
                }
            } catch (ignore: Throwable) {
                // never crash inside the crash reporter
            }
            platformHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildReport(thread: Thread, t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return buildString {
            appendLine("LIVE HEAD crash report")
            appendLine("time: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}")
            appendLine("app version: ${versionName} (${versionCode})")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("thread: ${thread.name}")
            appendLine()
            appendLine(sw.toString())
            appendLine()
            appendLine("--- recent log ---")
            appendLine(AppLog.tail(60))
        }
    }

    private fun writeReport(report: String) {
        try {
            File(filesDir, "last_crash.txt").writeText(report)
        } catch (ignore: Throwable) {}
        try {
            getExternalFilesDir(null)?.let { File(it, "last_crash.txt").writeText(report) }
        } catch (ignore: Throwable) {}
    }

    private fun createNotificationChannels() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STREAMING,
                    "Live streaming",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Ongoing status of your live stream"
                    setShowBadge(false)
                }
            )
        } catch (ignore: Throwable) {
            // a missing channel degrades the notification, it must not kill the app
        }
    }

    val versionName: String
        get() = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (ignore: Throwable) { "?" }

    val versionCode: Long
        get() = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).longVersionCode
        } catch (ignore: Throwable) { -1L }

    companion object {
        private const val TAG = "App"

        lateinit var instance: App
            private set

        const val CHANNEL_STREAMING = "livehead_streaming"
    }
}
