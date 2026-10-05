package com.livehead.app.stream.rtmp

import com.livehead.app.core.AppLog
import com.livehead.app.stream.Amf0
import com.livehead.app.stream.FlvTag
import com.livehead.app.stream.FlvTagType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * One RTMP/RTMPS publish session against a YouTube-style ingest server.
 *
 * Responsibilities (and nothing more — reconnection policy lives in the engine):
 *  - TCP (+TLS for rtmps) connection
 *  - simple RTMP handshake
 *  - chunk protocol both directions (outgoing chunk size raised to 4096)
 *  - command flow: connect -> createStream -> publish -> NetStream.Publish.Start
 *  - serialized, chunked writes of FLV tags (audio, video, data)
 *  - reader thread: server chunk size, acknowledgements, ping/pong, onStatus
 *  - write watchdog hooks: [lastWriteProgressMs] / [bytesWritten]
 *
 * Pure JVM (javax.net.ssl + java.net only) so the whole protocol layer can be
 * exercised against a local mock server on a desktop.
 */
class RtmpConnection(
    private val endpoint: RtmpEndpoint,
    private val onEvent: (Event) -> Unit,
) {

    sealed class Event {
        object PublishStarted : Event()
        class CommandError(val friendlyMessage: String) : Event()
        class ClosedUnexpected(val reason: String) : Event()
        class Info(val message: String) : Event()
    }

    enum class State { IDLE, CONNECTING, HANDSHAKE, COMMANDS, LIVE, CLOSED }

    class RtmpException(val friendlyMessage: String, cause: Throwable? = null) : Exception(friendlyMessage, cause)

    @Volatile var state: State = State.IDLE
        private set

    // --- watchdog-visible counters -------------------------------------------------
    @Volatile var lastWriteProgressMs: Long = 0L
        private set
    @Volatile var bytesWritten: Long = 0L
        private set
    @Volatile var packetsWritten: Long = 0L
        private set

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readerThread: Thread? = null

    private val writeLock = Any()
    @Volatile private var outChunkSize = 128
    @Volatile private var inChunkSize = 128
    private var messageStreamId = 1

    // ack bookkeeping
    @Volatile private var peerWindowAckSize = 2_500_000L
    private var receivedSinceAck = 0L
    private var totalReceived = 0L

    @Volatile private var expectedClose = false
    @Volatile private var failed = false

    private class CommandReply(val isError: Boolean, val values: List<Any?>)

    private val replies = LinkedBlockingQueue<CommandReply>()

    // =========================================================================
    // Connect sequence
    // =========================================================================

    /**
     * Full blocking connect + publish handshake. Throws [RtmpException] with a
     * user-friendly message on failure. On success the connection is LIVE and
     * media can be sent.
     */
    fun connect(connectTimeoutMs: Int = 10_000, commandTimeoutMs: Long = 12_000) {
        check(state == State.IDLE) { "connection already used" }
        state = State.CONNECTING
        try {
            openSocket(connectTimeoutMs)
            state = State.HANDSHAKE
            handshake()
            startReader()

            state = State.COMMANDS
            // Raise our chunk size and tell the server before anything else.
            sendSetChunkSize(4096)
            outChunkSize = 4096
            sendWindowAckSize(2_500_000)

            sendConnectCommand()
            expectResult(1, commandTimeoutMs)

            sendCreateStream()
            val streamId = expectResult(2, commandTimeoutMs)
            val sid = (streamId.values.getOrNull(3) as? Double)?.toInt()
            if (sid == null || sid <= 0) {
                throw RtmpException("YouTube connection failed. Please verify your Stream URL and Stream Key.")
            }
            messageStreamId = sid

            sendPublish()
            // Servers confirm publish with onStatus(NetStream.Publish.Start)
            // rather than a _result — a _error/onStatus rejection also lands
            // in the same queue and is handled below.
            waitForPublishStart(20_000)

            state = State.LIVE
            AppLog.i(TAG, "publish started: $endpoint")
            onEvent(Event.PublishStarted)
        } catch (t: Throwable) {
            fail(
                when (t) {
                    is RtmpException -> t.friendlyMessage
                    else -> "Connection failed: ${t.javaClass.simpleName}"
                },
                t
            )
            throw t
        }
    }

    private fun openSocket(timeoutMs: Int) {
        try {
            val plain = Socket()
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            val sock: Socket = if (endpoint.secure) {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(plain, endpoint.host, endpoint.port, true) as SSLSocket
                ssl.startHandshake()
                ssl
            } else plain
            sock.soTimeout = READ_TIMEOUT_MS
            socket = sock
            input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
            output = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
            lastWriteProgressMs = System.currentTimeMillis()
            AppLog.i(TAG, "socket open (${if (endpoint.secure) "rtmps/TLS" else "rtmp"}): $endpoint")
        } catch (t: Throwable) {
            throw RtmpException(
                "Could not reach the streaming server. Check your Internet connection and Stream URL.",
                t
            )
        }
    }

    // =========================================================================
    // Handshake (simple mode — accepted by YouTube ingest)
    // =========================================================================

    private fun handshake() {
        val random = SecureRandom()
        val c1 = ByteArray(1536)
        random.nextBytes(c1)
        output!!.apply {
            write(0x03)
            write(c1)
            flush()
        }
        val s0 = readExact(1)[0].toInt() and 0xFF
        if (s0 != 0x03) throw RtmpException("Unexpected RTMP handshake version from server.")
        val s1 = readExact(1536)
        readExact(1536) // S2
        output!!.apply {
            write(s1) // C2 = echo of S1
            flush()
        }
        lastWriteProgressMs = System.currentTimeMillis()
    }

    private fun readExact(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input!!.read(buf, off, n - off)
            if (r < 0) throw IOException("EOF during handshake")
            off += r
        }
        return buf
    }

    // =========================================================================
    // Commands
    // =========================================================================

    private fun sendConnectCommand() {
        val props = linkedMapOf<String, Any?>(
            "app" to endpoint.app,
            "type" to "nonprivate",
            "flashVer" to "FMLE/3.0 (LIVE HEAD; Android)",
            "tcUrl" to endpoint.tcUrl,
            "fpad" to false,
            "capabilities" to 237.0,
            "audioCodecs" to 3575.0,
            "videoCodecs" to 252.0,
            "videoFunction" to 1.0,
            "encoding" to 0.0,
        )
        writeMessage(CSID_COMMANDS, 0, 20, Amf0.command("connect", 1, listOf(props)), 0)
    }

    private fun sendCreateStream() {
        writeMessage(CSID_COMMANDS, 0, 20, Amf0.command("createStream", 2, listOf(null)), 0)
    }

    private fun sendPublish() {
        writeMessage(
            CSID_COMMANDS, messageStreamId, 20,
            Amf0.command("publish", 3, listOf(null, endpoint.streamKey, "live")), 0
        )
    }

    private fun sendSetChunkSize(size: Int) {
        val p = ByteArray(4)
        p[0] = ((size shr 24) and 0x7F).toByte()
        p[1] = ((size shr 16) and 0xFF).toByte()
        p[2] = ((size shr 8) and 0xFF).toByte()
        p[3] = (size and 0xFF).toByte()
        writeMessage(CSID_PROTOCOL, 0, 1, p, 0)
    }

    private fun sendWindowAckSize(size: Long) {
        writeMessage(CSID_PROTOCOL, 0, 5, be32(size), 0)
    }

    private fun sendAcknowledgement(seq: Long) {
        writeMessage(CSID_PROTOCOL, 0, 3, be32(seq), 0)
    }

    private fun expectResult(txn: Int, timeoutMs: Long): CommandReply {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw RtmpException("Streaming server did not respond in time. Please try again.")
            val r = replies.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            if (r.isError) return r
            return r
        }
    }

    private fun waitForPublishStart(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (state != State.LIVE) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                throw RtmpException("YouTube did not accept the stream key. Please verify your Stream URL and Stream Key.")
            }
            val r = replies.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            if (r.isError) {
                throw RtmpException("YouTube connection failed. Please verify your Stream URL and Stream Key.")
            }
            // _result for publish already consumed above; a Publish.Start comes
            // as onStatus which the reader turns into a special reply marker.
            val code = (r.values.getOrNull(3) as? Map<*, *>)?.get("code") as? String
            if (code == "NetStream.Publish.Start") return
            if (code != null && code.startsWith("NetStream.Unpublish")) {
                throw RtmpException("The streaming server closed the publish session. Verify your stream key.")
            }
            if (code != null && code.contains("Error", ignoreCase = true)) {
                throw RtmpException("YouTube connection failed. Please verify your Stream URL and Stream Key.")
            }
        }
    }

    // =========================================================================
    // Media writes
    // =========================================================================

    val isLive: Boolean get() = state == State.LIVE

    /** Sends one FLV tag as an RTMP message. Blocking, serialized across threads. */
    fun sendTag(tag: FlvTag) {
        val payload = tag.payload
        val type = when (tag.type) {
            FlvTagType.AUDIO -> 8
            FlvTagType.VIDEO -> 9
            FlvTagType.DATA -> 18
        }
        val csid = when (tag.type) {
            FlvTagType.AUDIO -> CSID_AUDIO
            FlvTagType.VIDEO -> CSID_VIDEO
            FlvTagType.DATA -> CSID_DATA
        }
        writeMessage(csid, messageStreamId, type, payload, tag.timestampMs)
    }

    private fun writeMessage(csid: Int, streamId: Int, typeId: Int, payload: ByteArray, timestampMs: Int) {
        if (state == State.CLOSED) throw IOException("connection closed")
        synchronized(writeLock) {
            val out = output ?: throw IOException("connection closed")
            val ts = timestampMs.toLong() and 0xFFFFFFFFL
            val extTs = ts >= 0xFFFFFF
            val tsField = if (extTs) 0xFFFFFF else ts.toInt()

            // fmt0 header
            out.write(csid and 0x3F) // fmt=0
            out.write((tsField shr 16) and 0xFF)
            out.write((tsField shr 8) and 0xFF)
            out.write(tsField and 0xFF)
            out.write((payload.size shr 16) and 0xFF)
            out.write((payload.size shr 8) and 0xFF)
            out.write(payload.size and 0xFF)
            out.write(typeId)
            // message stream id is LITTLE-endian (RTMP quirk)
            out.write(streamId and 0xFF)
            out.write((streamId shr 8) and 0xFF)
            out.write((streamId shr 16) and 0xFF)
            out.write((streamId shr 24) and 0xFF)
            if (extTs) {
                out.write(((ts shr 24) and 0xFF).toInt())
                out.write(((ts shr 16) and 0xFF).toInt())
                out.write(((ts shr 8) and 0xFF).toInt())
                out.write((ts and 0xFF).toInt())
            }

            var off = 0
            while (true) {
                val len = minOf(outChunkSize, payload.size - off)
                out.write(payload, off, len)
                off += len
                if (off >= payload.size) break
                // continuation chunk: fmt3 basic header (+ extended ts repeat)
                out.write(0xC0 or (csid and 0x3F))
                if (extTs) {
                    out.write(((ts shr 24) and 0xFF).toInt())
                    out.write(((ts shr 16) and 0xFF).toInt())
                    out.write(((ts shr 8) and 0xFF).toInt())
                    out.write((ts and 0xFF).toInt())
                }
            }
            out.flush()
            bytesWritten += payload.size
            packetsWritten++
            lastWriteProgressMs = System.currentTimeMillis()
        }
    }

    // =========================================================================
    // Reader thread
    // =========================================================================

    private fun startReader() {
        readerThread = Thread({
            try {
                readLoop()
            } catch (t: Throwable) {
                if (!expectedClose && !failed) {
                    fail("Connection lost: ${t.javaClass.simpleName}: ${t.message}", t)
                }
            }
        }, "livehead-rtmp-reader").apply { isDaemon = true; start() }
    }

    private class ChunkState {
        var pendingType = 0
        var pendingLen = 0
        var pendingMsid = 0
        var pendingTs = 0
        var buffer = ByteArray(0)
        var filled = 0
        var lastTs = 0
        var lastDelta = 0
    }

    private val chunkStates = HashMap<Int, ChunkState>()

    private fun readLoop() {
        val inStream = input!!
        while (state != State.CLOSED) {
            val b0 = inStream.read()
            if (b0 < 0) throw IOException("EOF")
            val fmt = (b0 shr 6) and 0x03
            var csid = b0 and 0x3F
            if (csid == 0) csid = 64 + inStream.read()
            else if (csid == 1) csid = 64 + inStream.read() + 256 * inStream.read()

            val st = chunkStates.getOrPut(csid) { ChunkState() }

            when (fmt) {
                0 -> {
                    st.pendingTs = readU24(inStream)
                    st.pendingLen = readU24(inStream)
                    st.pendingType = inStream.read()
                    st.pendingMsid = readU32LE(inStream).toInt()
                    if (st.pendingTs == 0xFFFFFF) st.pendingTs = readU32(inStream).toInt()
                    st.lastTs = st.pendingTs
                    st.lastDelta = 0
                }
                1 -> {
                    st.lastDelta = readU24(inStream)
                    st.pendingLen = readU24(inStream)
                    st.pendingType = inStream.read()
                    if (st.lastDelta == 0xFFFFFF) st.lastDelta = readU32(inStream).toInt()
                    st.lastTs += st.lastDelta
                    st.pendingTs = st.lastTs
                }
                2 -> {
                    st.lastDelta = readU24(inStream)
                    if (st.lastDelta == 0xFFFFFF) st.lastDelta = readU32(inStream).toInt()
                    st.lastTs += st.lastDelta
                    st.pendingTs = st.lastTs
                }
                3 -> {
                    // continuation of pending message, or a repeated message
                    if (st.filled >= st.pendingLen && st.pendingLen > 0) {
                        // new message repeating the previous header
                        st.lastTs += st.lastDelta
                        st.pendingTs = st.lastTs
                        st.filled = 0
                    }
                }
            }

            if (st.pendingLen > 0 && st.buffer.size != st.pendingLen) {
                st.buffer = ByteArray(st.pendingLen)
                st.filled = 0
            }

            var remaining = st.pendingLen - st.filled
            while (remaining > 0) {
                val n = inStream.read(st.buffer, st.filled, minOf(remaining, inChunkSize))
                if (n < 0) throw IOException("EOF in chunk")
                st.filled += n
                remaining -= n
                if (remaining > 0) {
                    // skip fmt3 basic header of the next continuation chunk
                    val nb = inStream.read()
                    if (nb < 0) throw IOException("EOF")
                    val nfmt = (nb shr 6) and 0x03
                    var ncsid = nb and 0x3F
                    if (ncsid == 0) ncsid = 64 + inStream.read()
                    else if (ncsid == 1) ncsid = 64 + inStream.read() + 256 * inStream.read()
                    if (nfmt != 3 || ncsid != csid) {
                        throw IOException("unexpected chunk interleaving fmt=$nfmt csid=$ncsid")
                    }
                }
            }

            if (st.pendingLen == 0) continue
            if (st.filled < st.pendingLen) continue

            val payload = st.buffer.copyOf(st.pendingLen)
            st.filled = 0
            totalReceived += payload.size
            receivedSinceAck += payload.size
            handleMessage(st.pendingType, payload)

            if (receivedSinceAck >= peerWindowAckSize) {
                receivedSinceAck = 0
                try {
                    sendAcknowledgement(totalReceived)
                } catch (ignore: IOException) {
                }
            }
        }
    }

    private fun handleMessage(type: Int, payload: ByteArray) {
        when (type) {
            1 -> { // Set Chunk Size
                if (payload.size == 4) {
                    inChunkSize = ((payload[0].toLong() and 0x7F) shl 24 or
                        (payload[1].toLong() and 0xFF) shl 16 or
                        (payload[2].toLong() and 0xFF) shl 8 or
                        (payload[3].toLong() and 0xFF)).toInt()
                }
            }
            3 -> { /* Acknowledgement from server: informational */ }
            4 -> handleUserControl(payload)
            5 -> { // Window Ack Size
                if (payload.size == 4) peerWindowAckSize = be32ToLong(payload)
            }
            6 -> { // Set Peer Bandwidth -> reply with our Window Ack Size
                try {
                    sendWindowAckSize(2_500_000)
                } catch (ignore: IOException) {
                }
            }
            20 -> handleCommand(payload)
            17 -> { /* AMF3 command: YouTube uses AMF0; ignore */ }
            else -> { /* audio/video/data from server: not expected */ }
        }
    }

    private fun handleUserControl(payload: ByteArray) {
        if (payload.size < 2) return
        val eventType = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        when (eventType) {
            0x0003 -> { // Ping -> Pong with the same 4-byte data
                if (payload.size >= 6) {
                    try {
                        val body = ByteArray(6)
                        body[0] = 0; body[1] = 0x07
                        System.arraycopy(payload, 2, body, 2, 4)
                        writeMessage(CSID_PROTOCOL, 0, 4, body, 0)
                    } catch (ignore: IOException) {
                    }
                }
            }
            else -> { /* BufferEmpty, StreamBegin, ...: informational */ }
        }
    }

    private fun handleCommand(payload: ByteArray) {
        val values = try {
            Amf0.decode(payload)
        } catch (t: Throwable) {
            return
        }
        val name = values.firstOrNull() as? String ?: return
        when {
            name == "_result" -> replies.add(CommandReply(false, values))
            name == "_error" -> {
                if (state == State.LIVE) {
                    onEvent(Event.Info("Server reported an error mid-stream."))
                }
                replies.add(CommandReply(true, values))
            }
            name == "onStatus" -> {
                val info = values.getOrNull(3) as? Map<*, *>
                val code = info?.get("code") as? String
                val description = info?.get("description") as? String
                AppLog.d(TAG, "onStatus code=$code")
                when {
                    code == "NetStream.Publish.Start" -> {
                        replies.add(CommandReply(false, listOf("onStatus", 0.0, null, mapOf("code" to code))))
                        // Also let the engine know publishing is live.
                        if (state == State.COMMANDS) { /* waitForPublishStart consumes via replies */ }
                    }
                    code != null && code.contains("Rejec", ignoreCase = true) -> {
                        replies.add(CommandReply(true, values))
                    }
                    code != null && code.contains("Fail", ignoreCase = true) -> {
                        replies.add(CommandReply(true, values))
                    }
                    else -> replies.add(CommandReply(false, values))
                }
                if (description != null && code == null) {
                    AppLog.d(TAG, "onStatus: $description")
                }
            }
            name == "close" -> throw IOException("server sent close command")
            else -> AppLog.d(TAG, "command from server: $name")
        }
    }

    // =========================================================================
    // Close
    // =========================================================================

    /** Best-effort graceful close: unpublish, delete stream, close socket. */
    fun gracefulClose() {
        if (state == State.CLOSED) return
        expectedClose = true
        try {
            if (state == State.LIVE) {
                writeMessage(CSID_COMMANDS, messageStreamId, 20, Amf0.command("FCUnpublish", 0, listOf(null, endpoint.streamKey)), 0)
                writeMessage(CSID_COMMANDS, messageStreamId, 20, Amf0.command("deleteStream", 0, listOf(null, messageStreamId.toDouble())), 0)
            }
        } catch (ignore: Throwable) {
            // graceful means best-effort; a dead socket is closed below
        }
        closeQuietly("graceful close")
    }

    /** Hard close — used by the watchdog when a write is stuck. */
    fun forceClose(reason: String) {
        if (state == State.CLOSED) return
        expectedClose = true
        AppLog.w(TAG, "force close: $reason")
        closeQuietly(reason)
    }

    private fun fail(reason: String, cause: Throwable? = null) {
        if (failed) return
        failed = true
        AppLog.w(TAG, "connection failed: $reason" + (cause?.let { " (${it.javaClass.simpleName})" } ?: ""))
        closeQuietly(reason)
        if (!expectedClose) onEvent(Event.ClosedUnexpected(reason))
    }

    private fun closeQuietly(reason: String) {
        state = State.CLOSED
        try { socket?.close() } catch (ignore: IOException) {}
    }

    // =========================================================================
    // IO helpers
    // =========================================================================

    private fun readU24(s: InputStream): Int {
        val a = s.read(); val b = s.read(); val c = s.read()
        if (a < 0 || b < 0 || c < 0) throw IOException("EOF")
        return (a shl 16) or (b shl 8) or c
    }

    private fun readU32(s: InputStream): Long {
        val a = s.read(); val b = s.read(); val c = s.read(); val d = s.read()
        if (a < 0 || b < 0 || c < 0 || d < 0) throw IOException("EOF")
        return (a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()
    }

    private fun readU32LE(s: InputStream): Long {
        val a = s.read(); val b = s.read(); val c = s.read(); val d = s.read()
        if (a < 0 || b < 0 || c < 0 || d < 0) throw IOException("EOF")
        return (d.toLong() shl 24) or (c.toLong() shl 16) or (b.toLong() shl 8) or a.toLong()
    }

    private fun be32(v: Long): ByteArray = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun be32ToLong(b: ByteArray): Long =
        ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)

    companion object {
        private const val TAG = "RtmpConnection"
        private const val READ_TIMEOUT_MS = 20_000

        private const val CSID_PROTOCOL = 2
        private const val CSID_COMMANDS = 3
        private const val CSID_AUDIO = 4
        private const val CSID_DATA = 5
        private const val CSID_VIDEO = 6
    }
}
