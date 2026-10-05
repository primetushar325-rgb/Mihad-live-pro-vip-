package com.livehead.app.stream

/**
 * THE FREEZE-PREVENTION CLOCK.
 *
 * Every media packet that goes on the wire is stamped and scheduled through
 * this class. The rules that keep YouTube from freezing after 2-3 seconds:
 *
 *  1. Stream time is derived from a monotonic clock source, never wall time.
 *  2. Output timestamps are REBASED so a fresh (re)connection starts at ~0.
 *  3. Timestamps are strictly monotonic on the wire: tsNext > tsPrev, always.
 *     Any violation (odd source PTS, loop wrap, encoder glitch) is clamped to
 *     +1 ms and counted; the receiver never sees a jump backwards.
 *  4. Pacing is wall-clock bound: a packet with stream time T is written once
 *     the stream has been running for T real milliseconds. The stream cannot
 *     run ahead of real time, and small decoder hiccups self-heal because the
 *     target is absolute, not incremental.
 *  5. [freeze]/[unfreeze] parks the pipelines during a reconnect without
 *     destroying codec state, so the playback position is preserved.
 *
 * Pure JVM with an injectable clock/sleeper — unit-testable at warp speed.
 */
class Pacer(
    private val clockMs: () -> Long,
    private val sleeper: (Long) -> Unit,
) {

    /** Set by the engine to abort blocked senders immediately. */
    @Volatile var cancelled: Boolean = false
        private set

    @Volatile private var frozen: Boolean = true

    private var t0Ms: Long = 0L            // clockMs() that maps to stream time 0
    private var rebaseUs: Long = 0L        // subtracted from stream pts before stamping
    private var lastStampMs: Long = -1L    // last timestamp that went on the wire

    /** Count of monotonicity clamps (surfaced in diagnostics). */
    @Volatile var timestampClamps: Long = 0
        private set
    /** Count of packets written more than [lateThresholdMs] behind schedule. */
    @Volatile var latePackets: Long = 0
        private set

    var lateThresholdMs: Long = 1500

    /** Starts the stream clock. The first packet is due immediately. */
    fun start() {
        synchronized(this) {
            t0Ms = clockMs()
            rebaseUs = 0L
            lastStampMs = -1L
            frozen = false
        }
    }

    /** Parks senders (reconnect / network loss) without losing position. */
    fun freeze() {
        synchronized(this) { frozen = true }
    }

    /**
     * Resumes after a freeze so that the packet stamped last is "due now" and
     * later packets follow in real time.
     */
    fun unfreeze() {
        synchronized(this) {
            t0Ms = clockMs() - lastStampMs.coerceAtLeast(0L)
            frozen = false
        }
    }

    /**
     * Rebases so the NEXT packets go out with timestamps starting near zero —
     * used when a reconnect creates a brand-new RTMP publish session.
     */
    fun rebaseToZero() {
        synchronized(this) {
            rebaseUs += lastStampMs.coerceAtLeast(0L) * 1000L
            lastStampMs = -1L
        }
    }

    /**
     * Blocks until the packet with media time [streamPtsUs] is due, then
     * returns the (monotonic, 32-bit) millisecond timestamp to stamp.
     *
     * Returns -1 when cancelled — callers must abort their send loop.
     */
    fun awaitSend(streamPtsUs: Long): Int {
        while (true) {
            if (cancelled) return -1
            val stampMs: Long = synchronized(this) { (streamPtsUs - rebaseUs) / 1000L }
            val isFrozen: Boolean = synchronized(this) { frozen }

            if (isFrozen) {
                sleeper(50L)
                continue
            }

            val dueMs = t0Ms + stampMs
            val delta = dueMs - clockMs()
            when {
                delta <= 1 -> return finalizeStamp(stampMs)
                delta <= 40 -> sleeper(delta)
                else -> sleeper(50L)
            }
        }
    }

    private fun finalizeStamp(rawStampMs: Long): Int {
        var stamp = rawStampMs
        synchronized(this) {
            if (stamp <= lastStampMs) {
                val behind = lastStampMs - stamp
                stamp = lastStampMs + 1
                if (behind > 100) timestampClamps++
            }
            lastStampMs = stamp
        }
        val nowLate = clockMs() - (t0Ms + rawStampMs)
        if (nowLate > lateThresholdMs) latePackets++
        return (stamp and 0xFFFFFFFFL).toInt()
    }

    /** Last on-wire timestamp (ms of stream time, before rebase). */
    fun lastStampMs(): Long = synchronized(this) { lastStampMs }

    fun cancel() {
        cancelled = true
    }
}
