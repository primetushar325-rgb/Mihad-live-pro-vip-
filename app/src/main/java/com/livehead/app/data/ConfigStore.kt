package com.livehead.app.data

import android.content.Context
import android.content.SharedPreferences
import com.livehead.app.core.AppLog

/**
 * StreamConfigRepository — non-secret settings persistence.
 * (Secrets live in [SecureStore]; nothing here ever touches the stream key.)
 */
interface StreamConfigRepository {
    var streamUrl: String
    var rememberKey: Boolean
    var videoUri: String?
    var videoName: String?
    var settings: StreamSettings
}

class PrefsConfigRepository(context: Context) : StreamConfigRepository {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("livehead_config", Context.MODE_PRIVATE)

    override var streamUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value.trim()).apply()

    override var rememberKey: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, false)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER, value).apply()

    override var videoUri: String?
        get() = prefs.getString(KEY_URI, null)
        set(value) = prefs.edit().putString(KEY_URI, value).apply()

    override var videoName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    override var settings: StreamSettings
        get() = StreamSettings(
            resolution = enumOrDefault(prefs.getString(KEY_RES, null), ResolutionChoice.AUTO),
            fps = enumOrDefault(prefs.getString(KEY_FPS, null), FpsChoice.F30),
            videoBitrate = enumOrDefault(prefs.getString(KEY_VBR, null), BitrateChoice.AUTO),
            audioBitrateBps = 128_000,
            keyframeIntervalSec = prefs.getInt(KEY_KEYFRAME, 2).coerceIn(1, 6),
            loop = prefs.getBoolean(KEY_LOOP, true),
            reconnectEnabled = prefs.getBoolean(KEY_RECONNECT, true),
            maxRetryIntervalSec = prefs.getInt(KEY_MAX_RETRY, 30).coerceIn(5, 120),
        )
        set(value) = prefs.edit()
            .putString(KEY_RES, value.resolution.name)
            .putString(KEY_FPS, value.fps.name)
            .putString(KEY_VBR, value.videoBitrate.name)
            .putInt(KEY_KEYFRAME, value.keyframeIntervalSec)
            .putBoolean(KEY_LOOP, value.loop)
            .putBoolean(KEY_RECONNECT, value.reconnectEnabled)
            .putInt(KEY_MAX_RETRY, value.maxRetryIntervalSec)
            .apply()

    companion object {
        private const val KEY_URL = "stream_url"
        private const val KEY_REMEMBER = "remember_key"
        private const val KEY_URI = "video_uri"
        private const val KEY_NAME = "video_name"
        private const val KEY_RES = "res"
        private const val KEY_FPS = "fps"
        private const val KEY_VBR = "vbr"
        private const val KEY_KEYFRAME = "keyframe"
        private const val KEY_LOOP = "loop"
        private const val KEY_RECONNECT = "reconnect"
        private const val KEY_MAX_RETRY = "max_retry"

        private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
            name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
    }
}
