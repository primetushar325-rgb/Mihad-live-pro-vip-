package com.livehead.app.stream.rtmp

/**
 * Parsed RTMP/RTMPS ingest endpoint.
 *
 * The user pastes an ingest URL (usually "rtmps://a.rtmp.youtube.com/live2")
 * and a stream key separately; the app combines them here. A URL that already
 * contains the key ("/live2/abcd-1234-...") is also accepted — the trailing
 * segment is treated as the key when no separate key is provided.
 *
 * toString() NEVER includes the stream key — safe for logs and UI.
 */
class RtmpEndpoint(
    val secure: Boolean,
    val host: String,
    val port: Int,
    val app: String,
    val streamKey: String,
) {
    val tcUrl: String get() = "${if (secure) "rtmps" else "rtmp"}://$host:$port/$app"

    /** Log-safe description (no key). */
    override fun toString(): String = tcUrl

    companion object {
        /**
         * Parses and validates. Returns null when the URL is not a usable
         * RTMP/RTMPS ingest endpoint, or when no key can be determined.
         */
        fun parse(urlRaw: String?, keyRaw: String?): RtmpEndpoint? {
            val url = urlRaw?.trim().orEmpty()
            val key = keyRaw?.trim().orEmpty()
            if (url.isEmpty()) return null

            val schemeSep = url.indexOf("://")
            if (schemeSep <= 0) return null
            val scheme = url.substring(0, schemeSep).lowercase()
            val secure = when (scheme) {
                "rtmp" -> false
                "rtmps" -> true
                else -> return null
            }

            val rest = url.substring(schemeSep + 3)
            val pathStart = rest.indexOf('/')
            val authority = if (pathStart >= 0) rest.substring(0, pathStart) else rest
            val path = if (pathStart >= 0) rest.substring(pathStart + 1) else ""

            if (authority.isBlank()) return null
            val host: String
            val port: Int
            if (authority.contains(':')) {
                val parts = authority.split(':')
                host = parts[0]
                port = parts[1].toIntOrNull() ?: return null
            } else {
                host = authority
                port = if (secure) 443 else 1935
            }
            if (host.isBlank() || port !in 1..65535) return null

            val segments = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null

            val app = segments.first()
            val effectiveKey = when {
                key.isNotEmpty() -> key
                segments.size >= 2 -> segments.subList(1, segments.size).joinToString("/")
                else -> return null
            }
            if (effectiveKey.isEmpty()) return null
            // Never keep whitespace/newlines inside a key.
            if (effectiveKey.any { it.isWhitespace() }) return null

            return RtmpEndpoint(secure, host, port, app, effectiveKey)
        }
    }
}
