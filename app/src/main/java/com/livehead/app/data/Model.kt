package com.livehead.app.data

/**
 * Domain model. Pure data — no Android imports.
 */

enum class ResolutionChoice(val label: String, val maxLongSide: Int) {
    AUTO("Auto", 1920),
    P360("360p", 360),
    P480("480p", 480),
    P720("720p", 720),
    P1080("1080p", 1080);

    override fun toString(): String = label
}

enum class FpsChoice(val label: String, val fps: Int) {
    F24("24", 24),
    F30("30", 30),
    F60("60", 60);

    override fun toString(): String = label
}

enum class BitrateChoice(val label: String, val bps: Int) {
    AUTO("Auto", 0),
    B2_0("2 Mbps", 2_000_000),
    B2_5("2.5 Mbps", 2_500_000),
    B3_0("3 Mbps", 3_000_000),
    B4_0("4 Mbps", 4_000_000),
    B5_0("5 Mbps", 5_000_000),
    B6_0("6 Mbps", 6_000_000),
    B8_0("8 Mbps", 8_000_000);

    override fun toString(): String = label
}

data class StreamSettings(
    val resolution: ResolutionChoice = ResolutionChoice.AUTO,
    val fps: FpsChoice = FpsChoice.F30,
    val videoBitrate: BitrateChoice = BitrateChoice.AUTO,
    val audioBitrateBps: Int = 128_000,
    val keyframeIntervalSec: Int = 2,
    val loop: Boolean = true,
    val reconnectEnabled: Boolean = true,
    val maxRetryIntervalSec: Int = 30,
) {
    /** Resolved video bitrate for a given output height (Auto profiles by resolution). */
    fun resolveVideoBitrate(outputHeight: Int): Int {
        if (videoBitrate != BitrateChoice.AUTO) return videoBitrate.bps
        return when {
            outputHeight <= 360 -> 1_200_000
            outputHeight <= 480 -> 2_000_000
            outputHeight <= 720 -> 3_200_000
            else -> 5_000_000
        }
    }

    companion object {
        /** Retry schedule mandated by the product spec: 5s, 10s, 20s, 30s, then capped. */
        fun backoffSeconds(attempt: Int, maxSec: Int): Int {
            val s = when (attempt) {
                0 -> 5
                1 -> 10
                2 -> 20
                else -> 30
            }
            return minOf(s, maxSec)
        }
    }
}

/** Everything we need to know about the selected local video before streaming. */
data class VideoMeta(
    val uri: String,
    val displayName: String,
    val durationUs: Long,
    val displayWidth: Int,     // rotation-corrected
    val displayHeight: Int,    // rotation-corrected
    val videoMime: String,     // e.g. video/avc, video/hevc
    val hasAudio: Boolean,
    val audioMime: String?,
    val audioSampleRate: Int,
    val audioChannels: Int,
    val audioBitrate: Int,
)

enum class EngineState {
    IDLE, PREPARING, CONNECTING, STREAMING, STALLING, RECONNECTING,
    STOPPING, STOPPED, FINISHED, ERROR
}

enum class UploadHealth { UNKNOWN, OK, MARGINAL, POOR }

data class EngineStats(
    val state: EngineState = EngineState.IDLE,
    val message: String? = null,
    val startedAtElapsedMs: Long = -1L,
    val nowElapsedMs: Long = -1L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val fpsSetting: Int = 0,
    val videoBitrateSettingBps: Int = 0,
    val audioBitrateSettingBps: Int = 0,
    val actualBitrateBps: Long = 0,
    val avgBitrateBps: Long = 0,
    val packetsSent: Long = 0,
    val bytesSent: Long = 0,
    val videoFramesSent: Long = 0,
    val audioFramesSent: Long = 0,
    val droppedFrames: Long = 0,
    val lateFrames: Long = 0,
    val reconnects: Long = 0,
    val loopCount: Long = 0,
    val networkAvailable: Boolean = true,
    val uploadHealth: UploadHealth = UploadHealth.UNKNOWN,
) {
    val elapsedMs: Long get() = if (startedAtElapsedMs < 0 || nowElapsedMs < 0) 0 else (nowElapsedMs - startedAtElapsedMs)
    val streaming: Boolean get() = state == EngineState.STREAMING || state == EngineState.STALLING || state == EngineState.RECONNECTING
}
