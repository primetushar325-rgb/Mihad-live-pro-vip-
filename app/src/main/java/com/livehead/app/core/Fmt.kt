package com.livehead.app.core

import java.util.Locale

/** Pure formatting helpers (no Android imports — unit-testable on the JVM). */
object Fmt {

    fun duration(totalMs: Long): String {
        val ms = if (totalMs < 0) 0 else totalMs
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun bitrate(bps: Long): String = when {
        bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        bps >= 1_000 -> String.format(Locale.US, "%d kbps", bps / 1_000)
        else -> "$bps bps"
    }

    fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

    fun res(w: Int, h: Int): String = "${w}×$h"
}
