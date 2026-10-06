package com.livehead.app.data

import com.livehead.app.stream.rtmp.RtmpEndpoint

/**
 * YouTubeController — the seam where a future version could plug in the
 * YouTube Live Streaming API (create broadcast, fetch ingest URL/key via
 * OAuth). Version 1 implements exactly ONE strategy: the user pastes the
 * RTMPS ingest URL and stream key manually, per the product spec —
 * no API, no login, no cloud.
 */
interface YouTubeController {
    /** Validates inputs and builds the ingest endpoint, or explains why not. */
    fun buildEndpoint(url: String, streamKey: String): Result<RtmpEndpoint>
}

class ManualRtmpController : YouTubeController {
    override fun buildEndpoint(url: String, streamKey: String): Result<RtmpEndpoint> {
        val endpoint = RtmpEndpoint.parse(url, streamKey)
        return if (endpoint == null) {
            Result.failure(IllegalArgumentException("Stream URL is invalid. It should look like rtmps://a.rtmp.youtube.com/live2"))
        } else {
            Result.success(endpoint)
        }
    }
}
