package com.livehead.app.stream

/**
 * FLV framing for RTMP ingest (YouTube).
 *
 * RTMP does not carry raw H.264/AAC — media travels as FLV tag payloads.
 * This file builds exactly the three tag families YouTube expects:
 *
 *   - AVC sequence header (SPS/PPS in AVCDecoderConfigurationRecord)
 *   - AVC frames (AVCC: 4-byte length-prefixed NAL units)
 *   - AAC sequence header (AudioSpecificConfig) and AAC frames
 *   - onMetaData (@setDataFrame)
 *
 * Pure JVM: unit-testable on a desktop.
 */

enum class FlvTagType(val rtmpMessageTypeId: Int) {
    AUDIO(8),
    VIDEO(9),
    DATA(18),
}

class FlvTag(
    val type: FlvTagType,
    /** Final, monotonically increasing, already-rebased timestamp in milliseconds. */
    val timestampMs: Int,
    val keyframe: Boolean,
    val payload: ByteArray,
)

object FlvMuxer {

    // FLV video tag constants
    private const val FRAME_FLAG_KEYFRAME = 0x10
    private const val FRAME_FLAG_INTER = 0x20
    private const val CODEC_ID_AVC = 0x07

    private const val AVC_PACKET_SEQ_HEADER = 0x00
    private const val AVC_PACKET_NALU = 0x01

    // FLV audio tag constants (AAC)
    private const val AUDIO_BYTE_AAC = 0xAF   // AAC, 44.1kHz-flag, 16-bit, stereo
    private const val AAC_PACKET_SEQ_HEADER = 0x00
    private const val AAC_PACKET_RAW = 0x01

    /** onMetaData data tag. timestamp must be 0. */
    fun onMetaData(
        width: Int,
        height: Int,
        fps: Int,
        videoBitrateBps: Int,
        audioBitrateBps: Int,
        audioSampleRate: Int,
        audioChannels: Int,
        hasAudio: Boolean,
        timestampMs: Int = 0,
    ): FlvTag {
        val w = Amf0.Writer()
        w.string("@setDataFrame")
        w.string("onMetaData")
        w.obj(
            linkedMapOf(
                "duration" to 0.0,
                "width" to width.toDouble(),
                "height" to height.toDouble(),
                "videocodecid" to 7.0,
                "avcprofile" to 100.0,
                "avclevel" to 31.0,
                "videodatarate" to (videoBitrateBps / 1000.0 / 1000.0), // KB/s, conventional
                "framerate" to fps.toDouble(),
                "audiocodecid" to 10.0,
                "audiodatarate" to (audioBitrateBps / 1000.0 / 1000.0),
                "audiosamplerate" to audioSampleRate.toDouble(),
                "audiosamplesize" to 16.0,
                "stereo" to (audioChannels >= 2),
                "audiochannels" to audioChannels.toDouble(),
                "hasAudio" to hasAudio,
                "encoder" to "LIVE HEAD",
            )
        )
        return FlvTag(FlvTagType.DATA, timestampMs, false, w.bytes())
    }

    /**
     * AVC sequence header: SPS/PPS wrapped in an AVCDecoderConfigurationRecord.
     * [sps]/[pps] must be raw NAL units WITHOUT start codes.
     *
     * Layout (13 fixed bytes + SPS + 3 bytes + PPS):
     *   [0] 0x17  [1] 0x00  [2..4] composition time 0
     *   [5] 1 (version)  [6..8] profile/compat/level  [9] 0xFF (4-byte NAL lengths)
     *   [10] 0xE1 (one SPS)  [11..12] SPS length  SPS
     *   [16] 0x01 (one PPS)  [17..18] PPS length  PPS
     */
    fun avcSequenceHeader(sps: ByteArray, pps: ByteArray, timestampMs: Int = 0): FlvTag {
        val body = ByteArray(16 + sps.size + pps.size)
        var i = 0
        body[i++] = (FRAME_FLAG_KEYFRAME or CODEC_ID_AVC).toByte()   // 0x17
        body[i++] = AVC_PACKET_SEQ_HEADER.toByte()
        body[i++] = 0; body[i++] = 0; body[i++] = 0                  // composition time
        body[i++] = 1                                                 // configurationVersion
        body[i++] = sps[1]                                            // profile
        body[i++] = sps[2]                                            // profile compat
        body[i++] = sps[3]                                            // level
        body[i++] = 0xFF.toByte()                                     // reserved(6) + lengthSizeMinusOne(3) = 4-byte lengths
        body[i++] = 0xE1.toByte()                                     // numOfSPS = 1
        body[i++] = ((sps.size shr 8) and 0xFF).toByte()
        body[i++] = (sps.size and 0xFF).toByte()
        System.arraycopy(sps, 0, body, i, sps.size); i += sps.size
        body[i++] = 1                                                 // numOfPPS = 1
        body[i++] = ((pps.size shr 8) and 0xFF).toByte()
        body[i++] = (pps.size and 0xFF).toByte()
        System.arraycopy(pps, 0, body, i, pps.size)
        return FlvTag(FlvTagType.VIDEO, timestampMs, true, body)
    }

    /** AAC sequence header from the encoder's csd-0 (AudioSpecificConfig). */
    fun aacSequenceHeader(audioSpecificConfig: ByteArray, timestampMs: Int = 0): FlvTag {
        val body = ByteArray(2 + audioSpecificConfig.size)
        body[0] = AUDIO_BYTE_AAC.toByte()
        body[1] = AAC_PACKET_SEQ_HEADER.toByte()
        System.arraycopy(audioSpecificConfig, 0, body, 2, audioSpecificConfig.size)
        return FlvTag(FlvTagType.AUDIO, timestampMs, false, body)
    }

    /** One raw AAC access unit. */
    fun aacFrame(aac: ByteArray, timestampMs: Int): FlvTag {
        val body = ByteArray(2 + aac.size)
        body[0] = AUDIO_BYTE_AAC.toByte()
        body[1] = AAC_PACKET_RAW.toByte()
        System.arraycopy(aac, 0, body, 2, aac.size)
        return FlvTag(FlvTagType.AUDIO, timestampMs, false, body)
    }

    /**
     * One video frame. [avcc] is already in AVCC form (4-byte length-prefixed
     * NAL units, no start codes) — use [annexBToAvcc] to convert encoder output.
     */
    fun avcFrame(avcc: ByteArray, timestampMs: Int, keyframe: Boolean): FlvTag {
        val body = ByteArray(5 + avcc.size)
        body[0] = ((if (keyframe) FRAME_FLAG_KEYFRAME else FRAME_FLAG_INTER) or CODEC_ID_AVC).toByte()
        body[1] = AVC_PACKET_NALU.toByte()
        body[2] = 0; body[3] = 0; body[4] = 0   // composition time (no B-frames)
        System.arraycopy(avcc, 0, body, 5, avcc.size)
        return FlvTag(FlvTagType.VIDEO, timestampMs, keyframe, body)
    }

    class AvccResult(val avcc: ByteArray, val hasIdr: Boolean, val sps: ByteArray?, val pps: ByteArray?)

    /**
     * Converts an H.264 Annex-B elementary stream (start codes 00 00 01 /
     * 00 00 00 01, as produced by MediaCodec) to AVCC, dropping parameter-set
     * and AUD NALs that must not appear inside FLV frame bodies.
     */
    fun annexBToAvcc(data: ByteArray, size: Int): AvccResult {
        val nals = splitAnnexB(data, size)
        var hasIdr = false
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var outSize = 0
        val kept = ArrayList<ByteArray>(8)
        for (nal in nals) {
            if (nal.isEmpty()) continue
            val type = nal[0].toInt() and 0x1F
            when (type) {
                1 -> { kept.add(nal); outSize += nal.size + 4 }
                5 -> { kept.add(nal); outSize += nal.size + 4; hasIdr = true }
                7 -> { sps = nal }
                8 -> { pps = nal }
                9, 12, 20 -> { /* AUD, filler: drop */ }
                else -> { kept.add(nal); outSize += nal.size + 4 }
            }
        }
        val avcc = ByteArray(outSize)
        var i = 0
        for (nal in kept) {
            avcc[i++] = ((nal.size shr 24) and 0xFF).toByte()
            avcc[i++] = ((nal.size shr 16) and 0xFF).toByte()
            avcc[i++] = ((nal.size shr 8) and 0xFF).toByte()
            avcc[i++] = (nal.size and 0xFF).toByte()
            System.arraycopy(nal, 0, avcc, i, nal.size)
            i += nal.size
        }
        return AvccResult(avcc, hasIdr, sps, pps)
    }

    /** Splits an Annex-B buffer into raw NAL units (no start codes). */
    fun splitAnnexB(data: ByteArray, size: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>(12)
        var i = 0
        var nalStart = -1
        while (i < size) {
            // look ahead for a start code
            if (i + 2 < size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (nalStart >= 0) out.add(copyOf(data, nalStart, i))
                nalStart = i + 3
                i += 3
            } else if (i + 3 < size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                if (nalStart >= 0) out.add(copyOf(data, nalStart, i))
                nalStart = i + 4
                i += 4
            } else {
                i++
            }
        }
        if (nalStart in 0 until size) out.add(copyOf(data, nalStart, size))
        return out
    }

    /** Splits a csd-0 buffer (SPS+PPS, Annex-B) into [sps, pps]. */
    fun parseSpsPps(csd0: ByteArray): Pair<ByteArray, ByteArray>? {
        val nals = splitAnnexB(csd0, csd0.size)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (n in nals) {
            when (if (n.isNotEmpty()) n[0].toInt() and 0x1F else -1) {
                7 -> if (sps == null) sps = n
                8 -> if (pps == null) pps = n
            }
        }
        return if (sps != null && pps != null) sps to pps else null
    }

    private fun copyOf(src: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArray(to - from)
        System.arraycopy(src, from, out, 0, out.size)
        return out
    }
}
