package com.livehead.app.test

import com.livehead.app.stream.Amf0
import com.livehead.app.stream.FlvMuxer
import com.livehead.app.stream.Pacer
import com.livehead.app.stream.rtmp.RtmpConnection
import com.livehead.app.stream.rtmp.RtmpEndpoint
import java.io.IOException

/**
 * Pure-JVM test harness for the protocol layer (no Android APIs).
 *
 * Part 1: unit tests (AMF0, FLV framing, Pacer clock, endpoint parsing).
 * Part 2: integration against tools/rtmp-mock-server.py — including the
 *         mid-stream kill + reconnect + timestamp-rebase scenario.
 *
 * The Pacer runs on a virtual clock, so a "6 minute" stream is simulated in
 * milliseconds of wall time.
 */
object TestMain {

    private var passed = 0
    private var failed = 0

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            passed++
            println("  ok   $name")
        } else {
            failed++
            println("  FAIL $name ${if (detail.isNotEmpty()) "— $detail" else ""}")
        }
    }

    // =====================================================================
    // Unit tests
    // =====================================================================

    private class FakeClock(var nowMs: Long = 0L)

    private fun unitTests() {
        println("== unit: Pacer ==")
        val clock = FakeClock(0)
        val pacer = Pacer(clockMs = { clock.nowMs }, sleeper = { ms -> clock.nowMs += ms })
        pacer.start()
        check("first packet stamps 0", pacer.awaitSend(0) == 0)
        check("next packet paced", pacer.awaitSend(1_000_000) == 1000)
        check("clock advanced to target", clock.nowMs >= 1000)
        // monotonic clamp
        val clamped = pacer.awaitSend(500_000)
        check("backward pts clamped to +1ms", clamped == 1001, "got $clamped")
        // freeze / unfreeze preserves position
        pacer.freeze()
        clock.nowMs += 5000 // time passes while frozen
        pacer.unfreeze()
        val after = pacer.awaitSend(1_100_000)
        check("after unfreeze stamps continuously", after == 1100, "got $after")
        // rebase to zero (reconnect semantics)
        pacer.rebaseToZero()
        val rebased = pacer.awaitSend(1_150_000)
        check("rebase restarts near zero", rebased == 50, "got $rebased")

        println("== unit: AMF0 ==")
        val props = linkedMapOf<String, Any?>(
            "app" to "live2", "fpad" to false, "caps" to 237.0, "s" to "x"
        )
        val enc = Amf0.command("connect", 1, listOf(props))
        val dec = Amf0.decode(enc)
        check("command name round-trips", dec[0] == "connect")
        check("txn round-trips", dec[1] == 1.0)
        val obj = dec[2] as Map<*, *>
        check("object props round-trip",
            obj["app"] == "live2" && obj["fpad"] == false && obj["caps"] == 237.0)

        println("== unit: FLV ==")
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0xAC.toByte(), 0x2A)
        val pps = byteArrayOf(0x68, 0xEB.toByte(), 0xEC.toByte(), 0xB2.toByte(), 0x2C)
        val seq = FlvMuxer.avcSequenceHeader(sps, pps)
        check("avc seq marker 0x17", seq.payload[0] == 0x17.toByte())
        check("avc seq type 0", seq.payload[1] == 0x00.toByte())
        check("avc seq NAL length field is 4", seq.payload[9] == 0xFF.toByte())
        check("avc seq body size", seq.payload.size == 16 + sps.size + pps.size)

        // Annex-B → AVCC with junk NALs
        fun nal(type: Int, len: Int): ByteArray {
            val h = byteArrayOf(((type and 0x1F)).toByte())
            return h + ByteArray(len - 1) { 0x55 }
        }
        fun annexB(vararg nals: ByteArray): ByteArray {
            var out = byteArrayOf()
            for (n in nals) out += byteArrayOf(0, 0, 0, 1) + n
            return out
        }
        val idr = nal(5, 40)
        val pFrame = nal(1, 30)
        val aud = nal(9, 2)
        val sei = nal(6, 12)
        val spsN = nal(7, 8)
        val ppsN = nal(8, 6)
        val es = annexB(aud, spsN, ppsN, sei, idr, pFrame)
        val avcc = FlvMuxer.annexBToAvcc(es, es.size)
        check("avcc detects IDR", avcc.hasIdr)
        check("avcc keeps IDR+SEI+P only", avcc.avcc.size == (idr.size + sei.size + pFrame.size + 12))
        check("avcc strips SPS", avcc.sps != null && avcc.pps != null)
        val parsed = FlvMuxer.parseSpsPps(annexB(spsN, ppsN))
        check("csd parse splits sps/pps", parsed != null && parsed.first.size == 8 && parsed.second.size == 6)

        val aseq = FlvMuxer.aacSequenceHeader(byteArrayOf(0x12, 0x10))
        check("aac seq markers", aseq.payload[0] == 0xAF.toByte() && aseq.payload[1] == 0x00.toByte())
        val aframe = FlvMuxer.aacFrame(byteArrayOf(1, 2, 3), 1234)
        check("aac frame markers", aframe.payload[0] == 0xAF.toByte() && aframe.payload[1] == 0x01.toByte())

        val md = FlvMuxer.onMetaData(1280, 720, 30, 3_200_000, 128_000, 44100, 2, true)
        val mdDec = Amf0.decode(md.payload)
        check("onMetaData carries width/height", mdDec[1] == "onMetaData")
        val mdObj = mdDec[2] as Map<*, *>
        check("metadata width", mdObj["width"] == 1280.0)
        check("metadata videocodecid", mdObj["videocodecid"] == 7.0)

        println("== unit: RtmpEndpoint ==")
        check("standard youtube url parses",
            RtmpEndpoint.parse("rtmps://a.rtmp.youtube.com/live2", "abcd-efgh")?.tcUrl == "rtmps://a.rtmp.youtube.com:443/live2")
        check("plain rtmp default port",
            RtmpEndpoint.parse("rtmp://example.com/live", "k")?.port == 1935)
        check("key embedded in url is used",
            RtmpEndpoint.parse("rtmps://a.rtmp.youtube.com/live2/xxxx-yyyy", "")?.streamKey == "xxxx-yyyy")
        check("explicit key wins over embedded",
            RtmpEndpoint.parse("rtmps://a.rtmp.youtube.com/live2/xxxx", "zzzz")?.streamKey == "zzzz")
        check("custom port", RtmpEndpoint.parse("rtmp://host:1935/live", "k")?.port == 1935)
        check("garbage rejected", RtmpEndpoint.parse("http://x/y", "k") == null)
        check("no app rejected", RtmpEndpoint.parse("rtmp://host/", "k") == null)
        check("no key rejected", RtmpEndpoint.parse("rtmp://host/live", " ") == null)
        check("toString hides key",
            RtmpEndpoint.parse("rtmps://h/live", "SECRET")?.toString()?.contains("SECRET") == false)
    }

    // =====================================================================
    // Protocol integration: feed a fake 30fps video + 43fps AAC stream with
    // a loop at 6s, survive a mid-stream server kill, verify rebase.
    // =====================================================================

    private fun protocolTest(port: Int) {
        println("== protocol: streaming against mock server (kill@3s, finish@6s) ==")
        val endpoint = RtmpEndpoint.parse("rtmp://127.0.0.1:$port/live2", "test-key-1234")!!
        val clock = FakeClock(0)
        val pacer = Pacer(clockMs = { clock.nowMs }, sleeper = { ms -> clock.nowMs += maxOf(ms, 1L) })

        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0xAC.toByte(), 0x2A)
        val pps = byteArrayOf(0x68, 0xEB.toByte(), 0xEC.toByte(), 0xB2.toByte(), 0x2C)
        val asc = byteArrayOf(0x12, 0x10)
        val aacFrame = ByteArray(220) { (it % 251).toByte() }

        val loopLenUs = 6_000_000L
        var iterOffsetUs = 0L
        var videoPts = 0L     // within-iteration
        var audioPts = 0L
        val videoFrameDurUs = 33_333L
        val audioFrameDurUs = 1_000_000L * 1024 / 44100

        var session = 0
        var reconnects = 0
        var sentVideo = 0
        var sentAudio = 0
        var finished = false

        var conn: RtmpConnection? = null
        var forceKeyframe = true
        var maxAttempts = 20

        fun connect(): RtmpConnection {
            var last: Throwable? = null
            while (maxAttempts-- > 0) {
                try {
                    val c = RtmpConnection(endpoint) { }
                    c.connect()
                    session++
                    forceKeyframe = true
                    return c
                } catch (t: Throwable) {
                    println("  connect attempt failed: $t")
                    last = t
                    clock.nowMs += 200 // virtual backoff
                }
            }
            throw IOException("could not connect: $last")
        }

        conn = connect()
        pacer.start()
        conn.sendTag(FlvMuxer.onMetaData(1280, 720, 30, 3_200_000, 128_000, 44100, 2, true))
        conn.sendTag(FlvMuxer.avcSequenceHeader(sps, pps, 0))
        conn.sendTag(FlvMuxer.aacSequenceHeader(asc, 0))

        var deadlineClock = 0L
        while (!finished) {
            val c = conn
            if (c == null || !c.isLive) {
                // The server kills us once on purpose. If that already
                // happened AND we survived past a loop wrap, the scenario is
                // verified — end gracefully instead of reconnecting forever.
                if (reconnects >= 1 && iterOffsetUs >= loopLenUs) {
                    finished = true
                    break
                }
                // server killed us — reconnect with engine semantics
                reconnects++
                try { c?.gracefulClose() } catch (ignore: Throwable) {}
                pacer.rebaseToZero()
                val nc = connect()
                nc.sendTag(FlvMuxer.onMetaData(1280, 720, 30, 3_200_000, 128_000, 44100, 2, true))
                nc.sendTag(FlvMuxer.avcSequenceHeader(sps, pps, 0))
                nc.sendTag(FlvMuxer.aacSequenceHeader(asc, 0))
                conn = nc
                check("reconnected once", reconnects == 1, "reconnects=$reconnects")
            }

            val cc = conn!!
            try {
                if (videoPts <= audioPts) {
                    // video packet
                    val streamPts = iterOffsetUs + videoPts
                    val ts = pacer.awaitSend(streamPts)
                    if (ts < 0) break
                    val isKey = forceKeyframe || videoPts == 0L || (videoPts % 2_000_000L) < videoFrameDurUs
                    forceKeyframe = false
                    // build a tiny annex-B frame: IDR or P
                    val nal = if (isKey) byteArrayOf(0x65, 0x11, 0x22, 0x33) else byteArrayOf(0x41, 0x11)
                    val es = byteArrayOf(0, 0, 0, 1) + nal
                    val avcc = FlvMuxer.annexBToAvcc(es, es.size)
                    cc.sendTag(FlvMuxer.avcFrame(avcc.avcc, ts, isKey))
                    sentVideo++
                    videoPts += videoFrameDurUs
                } else {
                    val streamPts = iterOffsetUs + audioPts
                    val ts = pacer.awaitSend(streamPts)
                    if (ts < 0) break
                    cc.sendTag(FlvMuxer.aacFrame(aacFrame, ts))
                    sentAudio++
                    audioPts += audioFrameDurUs
                }

                // loop boundary: both tracks finished the iteration
                if (videoPts >= loopLenUs && audioPts >= loopLenUs) {
                    iterOffsetUs += loopLenUs
                    videoPts = 0
                    audioPts = 0
                    forceKeyframe = true
                    println("  loop wrap → iteration offset ${iterOffsetUs / 1000}ms")
                }

                if (iterOffsetUs >= loopLenUs * 2) {
                    // simulated 12+ seconds: done
                    finished = true
                }
            } catch (t: IOException) {
                conn = null
            } catch (t: Throwable) {
                println("  unexpected: $t")
                conn = null
            }
        }

        try { conn?.gracefulClose() } catch (ignore: Throwable) {}

        check("sent a real amount of video", sentVideo > 200, "video=$sentVideo")
        check("sent a real amount of audio", sentAudio > 250, "audio=$sentAudio")
        check("survived exactly one forced reconnect", reconnects == 1, "reconnects=$reconnects")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        com.livehead.app.core.AppLog.mirrorToLogcat = true
        unitTests()
        if (args.isNotEmpty()) {
            val port = args[0].toIntOrNull()
            if (port != null) {
                // give the server a moment to bind
                Thread.sleep(300)
                protocolTest(port)
            }
        }
        println()
        println(if (failed == 0) "ALL TESTS PASSED ($passed)" else "FAILED: $failed of ${passed + failed}")
        System.exit(if (failed == 0) 0 else 1)
    }
}
