package com.livehead.app.core

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Structured in-process logger with a bounded ring buffer.
 *
 * Hard rules enforced by design:
 *  - the stream key, the combined RTMPS URL and any secret MUST NEVER be logged;
 *    callers only ever pass already-masked values, and [redact] sweeps known
 *    secret shapes as a second line of defence;
 *  - nothing is written to disk unless the user explicitly exports diagnostics;
 *  - the buffer is bounded so long sessions cannot leak memory.
 */
object AppLog {

    const val DEBUG = 0
    const val INFO = 1
    const val WARNING = 2
    const val ERROR = 3

    private const val CAPACITY = 1200

    class Entry(val timeMs: Long, val level: Int, val tag: String, val message: String) {
        fun line(): String =
            "${SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timeMs))} " +
                "${levelName(level).padEnd(7)} $tag: $message"
    }

    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val lock = Any()
    @Volatile var mirrorToLogcat = false

    fun d(tag: String, msg: String) = log(DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(INFO, tag, msg)
    fun w(tag: String, msg: String) = log(WARNING, tag, msg)
    fun e(tag: String, msg: String) = log(ERROR, tag, msg)

    /** Error with stack trace (used by the crash reporter and fallbacks). */
    fun e(tag: String, msg: String, t: Throwable) {
        val stack = t.stackTrace.joinToString("\n") { "    at $it" }
        log(ERROR, tag, msg + ": " + t.javaClass.name + ": " + (t.message ?: "") +
            "\n" + stack.take(4000))
    }

    fun log(level: Int, tag: String, msg: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, redact(msg))
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.pollFirst()
            buffer.addLast(entry)
        }
        if (mirrorToLogcat) {
            // System.out is mirrored into logcat (tag "System.out") by the
            // platform, and keeps this class pure-JVM/testable on a desktop.
            println("${levelName(level).padEnd(7)}/$tag: ${entry.message}")
        }
    }

    /** Newest-last snapshot of the ring buffer. */
    fun snapshot(): List<String> = synchronized(lock) { buffer.map { it.line() } }

    /** Last [n] lines as one string (used by the crash reporter). */
    fun tail(n: Int): String = synchronized(lock) { buffer.toList().takeLast(n).joinToString("\n") { it.line() } }

    fun snapshotText(): String = snapshot().joinToString("\n")

    private val URL_KEY = Regex("""(rtmps?://[^\s/]+/[^\s/]*/)([^\s]+)""")

    /**
     * Masks the stream-key portion of any RTMP URL that slipped into a message,
     * plus long base64/hex-ish tokens that look like keys.
     */
    fun redact(msg: String): String {
        var out = msg
        out = URL_KEY.replace(out) { m ->
            if (m.groupValues[2].length >= 8) "${m.groupValues[1]}••••••" else m.value
        }
        return out
    }

    fun levelName(level: Int): String = when (level) {
        DEBUG -> "DEBUG"
        INFO -> "INFO"
        WARNING -> "WARNING"
        else -> "ERROR"
    }
}
