package com.livehead.app.stream

import android.media.MediaFormat

/**
 * MediaFormat.getInteger/getLong overloads WITH a default value were only
 * added in API 29; this app supports API 26+, so every optional-key read
 * must go through these guards.
 */
internal fun MediaFormat.intOr(key: String, def: Int): Int =
    if (containsKey(key)) getInteger(key) else def

internal fun MediaFormat.longOr(key: String, def: Long): Long =
    if (containsKey(key)) getLong(key) else def
