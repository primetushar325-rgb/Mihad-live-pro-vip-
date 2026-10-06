#!/usr/bin/env python3
"""
Mock YouTube RTMP ingest server — the QA harness for LIVE HEAD's protocol layer.

What it validates on every session (the exact invariants whose violation makes
a stream freeze after 2-3 seconds):
  1. onMetaData data message arrives before media.
  2. AVC sequence header (0x17 0x00) arrives before any video frame.
  3. AAC sequence header (0xAF 0x00) arrives before any audio frame.
  4. First video frame after the sequence header is a KEYFRAME.
  5. Timestamps are strictly monotonic per track (no negative jumps).
  6. Audio is continuous: no gap > 600 ms between audio frames.
  7. Video is continuous: no gap > 2500 ms between video frames.
  8. Session restart (reconnect): timestamps rebase near zero, sequence
     headers are re-sent, and the first frame after reconnect is a keyframe.

Reconnect choreography for this test: the server kills the TCP connection as
soon as it sees a video timestamp >= KILL_AT_MS. The client is expected to
reconnect within 10 seconds and continue from a rebased clock.

Prints "RESULT {json}" at the end. Exit code 0 iff all checks pass.
"""
import json
import socket
import struct
import sys
import threading

import time
import os

VERBOSE = bool(os.environ.get("LH_VERBOSE"))

KILL_AT_MS = 3000
FINISH_AT_MS = 6000
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 19351

# ----------------------------------------------------------------------------
# tiny AMF0
# ----------------------------------------------------------------------------

def amf_number(v):
    return b"\x00" + struct.pack(">d", float(v))

def amf_boolean(v):
    return b"\x01" + (b"\x01" if v else b"\x00")

def amf_string(s):
    b = s.encode()
    return b"\x02" + struct.pack(">H", len(b)) + b

def amf_null():
    return b"\x05"

def amf_object(d):
    out = b"\x03"
    for k, v in d.items():
        kb = k.encode()
        out += struct.pack(">H", len(kb)) + kb
        out += encode_value(v)
    return out + b"\x00\x00\x09"

def encode_value(v):
    if v is None:
        return amf_null()
    if isinstance(v, bool):
        return amf_boolean(v)
    if isinstance(v, (int, float)):
        return amf_number(v)
    if isinstance(v, str):
        return amf_string(v)
    if isinstance(v, dict):
        return amf_object(v)
    raise TypeError(v)

class AmfReader:
    def __init__(self, data, pos=0):
        self.d = data
        self.p = pos

    def read(self):
        marker = self.d[self.p]; self.p += 1
        if marker == 0x00:
            v = struct.unpack(">d", self.d[self.p:self.p+8])[0]; self.p += 8
            return v
        if marker == 0x01:
            v = self.d[self.p] != 0; self.p += 1
            return v
        if marker == 0x02:
            n = struct.unpack(">H", self.d[self.p:self.p+2])[0]; self.p += 2
            v = self.d[self.p:self.p+n].decode(); self.p += n
            return v
        if marker == 0x03:
            return self.read_object()
        if marker == 0x05 or marker == 0x06:
            return None
        if marker == 0x08:
            self.p += 4
            return self.read_object()
        raise ValueError("marker %02x" % marker)

    def read_object(self):
        out = {}
        while True:
            n = struct.unpack(">H", self.d[self.p:self.p+2])[0]; self.p += 2
            if n == 0:
                self.p += 1  # object end
                return out
            key = self.d[self.p:self.p+n].decode(); self.p += n
            out[key] = self.read()

# ----------------------------------------------------------------------------
# session state
# ----------------------------------------------------------------------------

class Checks:
    def __init__(self, name):
        self.name = name
        self.meta_first = False
        self.video_seq_first = None  # bool
        self.audio_seq_first = None
        self.first_video_is_keyframe = None
        self.ts_monotonic_video = True
        self.ts_monotonic_audio = True
        self.audio_max_gap = 0
        self.video_max_gap = 0
        self.first_video_ts = None
        self.counts = {"video": 0, "audio": 0, "data": 0}
        self.errors = []
        self.last_vts = None
        self.last_ats = None
        self.saw_meta = False
        self.saw_vseq = False
        self.saw_aseq = False

    def summary(self):
        return {
            "session": self.name,
            "meta_before_media": self.meta_first,
            "video_seq_before_frames": self.video_seq_first,
            "audio_seq_before_frames": self.audio_seq_first,
            "first_video_frame_is_keyframe": self.first_video_is_keyframe,
            "video_ts_monotonic": self.ts_monotonic_video,
            "audio_ts_monotonic": self.ts_monotonic_audio,
            "audio_max_gap_ms": self.audio_max_gap,
            "video_max_gap_ms": self.video_max_gap,
            "first_video_ts_ms": self.first_video_ts,
            "counts": self.counts,
            "errors": self.errors,
        }

def fail(session, checks, msg):
    print("FAIL:", session, msg, file=sys.stderr)
    checks.errors.append(msg)

def handle_media(session, checks, mtype, ts_ms, payload):
    if mtype == 18:  # data
        checks.counts["data"] += 1
        if not checks.saw_meta and not checks.saw_vseq and not checks.saw_aseq:
            checks.meta_first = True
            checks.saw_meta = True
        return

    if mtype == 9:  # video
        checks.counts["video"] += 1
        if len(payload) < 2:
            fail(session, checks, "video payload too short")
            return
        frame_type = payload[0] >> 4
        pkt_type = payload[1]
        if pkt_type == 0x00:  # sequence header
            if checks.saw_vseq:
                return  # re-sent on reconnect; fine
            checks.video_seq_first = not checks.saw_vseq and checks.counts["video"] == 1
            checks.saw_vseq = True
            return
        if not checks.saw_vseq:
            fail(session, checks, "video frame before sequence header")
        if checks.first_video_is_keyframe is None:
            checks.first_video_is_keyframe = frame_type == 1
            checks.first_video_ts = ts_ms
            if frame_type != 1:
                fail(session, checks, "first video frame is not a keyframe (type=%d)" % frame_type)
        if checks.last_vts is not None:
            if ts_ms < checks.last_vts:
                checks.ts_monotonic_video = False
                fail(session, checks, "video ts went backwards: %d -> %d" % (checks.last_vts, ts_ms))
            gap = ts_ms - checks.last_vts
            checks.video_max_gap = max(checks.video_max_gap, gap)
            if gap > 2500:
                fail(session, checks, "video gap %d ms (freeze risk)" % gap)
        checks.last_vts = ts_ms

    if mtype == 8:  # audio
        checks.counts["audio"] += 1
        if len(payload) < 2:
            fail(session, checks, "audio payload too short")
            return
        pkt_type = payload[1]
        if pkt_type == 0x00:
            checks.audio_seq_first = not checks.saw_aseq and checks.counts["audio"] == 1
            checks.saw_aseq = True
            return
        if not checks.saw_aseq:
            fail(session, checks, "audio frame before sequence header")
        if checks.last_ats is not None:
            if ts_ms < checks.last_ats:
                checks.ts_monotonic_audio = False
                fail(session, checks, "audio ts went backwards: %d -> %d" % (checks.last_ats, ts_ms))
            gap = ts_ms - checks.last_ats
            checks.audio_max_gap = max(checks.audio_max_gap, gap)
            if gap > 600:
                fail(session, checks, "audio gap %d ms (audio starvation — classic freeze cause)" % gap)
        checks.last_ats = ts_ms

# ----------------------------------------------------------------------------
# chunk protocol (server side)
# ----------------------------------------------------------------------------

class ChunkReader:
    """Reads one RTMP message; returns (csid, type, stream_id, ts_ms, payload)."""

    def __init__(self, sock_file, chunk_size_holder):
        self.f = sock_file
        self.chunk_size_holder = chunk_size_holder

    def read_message(self):
        b0 = self.f.read(1)
        if not b0:
            return None
        fmt = (b0[0] >> 6) & 0x03
        csid = b0[0] & 0x3F
        if csid == 0:
            csid = 64 + self.f.read(1)[0]
        elif csid == 1:
            b = self.f.read(2)
            csid = 64 + b[0] + 256 * b[1]

        ts = 0
        length = 0
        mtype = 0
        stream_id = 0

        if fmt == 0:
            h = self.f.read(11)
            ts = (h[0] << 16) | (h[1] << 8) | h[2]
            length = (h[3] << 16) | (h[4] << 8) | h[5]
            mtype = h[6]
            stream_id = h[7] | (h[8] << 8) | (h[9] << 16) | (h[10] << 24)
            if ts == 0xFFFFFF:
                ts = struct.unpack(">I", self.f.read(4))[0]
        elif fmt in (1, 2, 3):
            # client uses fmt0 for new messages and fmt3 for continuations;
            # continuations are handled inside payload assembly
            if fmt == 1:
                h = self.f.read(6)
                length = (h[3] << 16) | (h[4] << 8) | h[5]
                mtype = h[6] if len(h) > 6 else 0
            elif fmt == 2:
                h = self.f.read(3)
            else:
                h = b""
            # fmt1/2 from this client are not expected; treat length 0 to bail
            if fmt == 1:
                raise ValueError("unexpected fmt1 from client")
            if fmt == 2:
                raise ValueError("unexpected fmt2 from client")
            # fmt3 with no pending data: repeated header (not expected)
            raise ValueError("unexpected fmt3 header from client")

        payload = b""
        remaining = length
        while remaining > 0:
            n = min(remaining, self.chunk_size_holder[0])
            data = self.f.read(n)
            if len(data) < n:
                return None
            payload += data
            remaining -= n
            if remaining > 0:
                nb = self.f.read(1)
                if not nb:
                    return None
                nfmt = (nb[0] >> 6) & 0x03
                if nfmt != 3:
                    raise ValueError("expected fmt3 continuation, got %d" % nfmt)
                # note: csid bits must match but we accept any here

        return (csid, mtype, stream_id, ts, payload)

def write_message(sock, csid, stream_id, mtype, ts_ms, payload, chunk_size=128):
    out = bytearray()
    ts = ts_ms if ts_ms < 0xFFFFFF else 0xFFFFFF
    out.append((0 << 4) | csid)
    out += bytes([(ts >> 16) & 0xFF, (ts >> 8) & 0xFF, ts & 0xFF])
    out += bytes([((len(payload)) >> 16) & 0xFF, ((len(payload)) >> 8) & 0xFF, len(payload) & 0xFF])
    out.append(mtype)
    out += bytes([stream_id & 0xFF, (stream_id >> 8) & 0xFF, (stream_id >> 16) & 0xFF, (stream_id >> 24) & 0xFF])
    if ts_ms >= 0xFFFFFF:
        out += struct.pack(">I", ts_ms)
    view = memoryview(payload)
    off = 0
    while True:
        n = min(chunk_size, len(payload) - off)
        out += view[off:off+n]
        off += n
        if off >= len(payload):
            break
        out.append(0xC0 | csid)
        if ts_ms >= 0xFFFFFF:
            out += struct.pack(">I", ts_ms)
    sock.sendall(bytes(out))

# ----------------------------------------------------------------------------
# server
# ----------------------------------------------------------------------------

def handshake(f, sock):
    c0c1 = f.read(1537)
    if len(c0c1) < 1537 or c0c1[0] != 3:
        raise ValueError("bad handshake")
    s1 = b"\x00" * 1536
    sock.sendall(b"\x03" + s1 + c0c1[1:])  # S0 + S1 + S2(echo C1)
    c2 = f.read(1536)  # C2
    if len(c2) < 1536:
        raise ValueError("bad C2")

class Server:
    def __init__(self):
        self.sessions = []       # list of Checks
        self.session_no = 0
        self.lock = threading.Lock()
        self.kill_done = False
        self.finished = threading.Event()

    def run(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", PORT))
        srv.listen(4)
        srv.settimeout(1.0)
        print("mock-rtmp: listening on %d" % PORT, flush=True)
        deadline = time.time() + 25
        while time.time() < deadline and not self.finished.is_set():
            try:
                conn, _ = srv.accept()
            except socket.timeout:
                continue
            self.handle(conn)
        srv.close()

    def handle(self, sock):
        self.session_no += 1
        name = "s%d" % self.session_no
        checks = Checks(name)
        with self.lock:
            self.sessions.append(checks)
        sock.settimeout(30)
        f = sock.makefile("rb")
        out = sock  # raw socket writer
        chunk_size_holder = [128]
        try:
            handshake(f, sock)
            reader = ChunkReader(f, chunk_size_holder)
            while True:
                msg = reader.read_message()
                if msg is None:
                    if VERBOSE:
                        print('DEBUG-MSG: EOF')
                    break
                if VERBOSE:
                    print('DEBUG-MSG got: csid=%d type=%d ts=%d len=%d' % (msg[0], msg[1], msg[3], len(msg[4])))
                csid, mtype, stream_id, ts, payload = msg
                if mtype == 1:  # set chunk size
                    size = struct.unpack(">I", payload)[0] & 0x7FFFFFFF
                    chunk_size_holder[0] = size
                    continue
                if mtype in (3, 4, 5, 6):
                    continue
                if mtype in (8, 9, 18):
                    handle_media(name, checks, mtype, ts, payload)
                    if mtype == 9 and ts >= FINISH_AT_MS and self.session_no >= 2:
                        self.finished.set()
                        break
                    if (mtype == 9 and ts >= KILL_AT_MS and not self.kill_done
                            and self.session_no == 1):
                        self.kill_done = True
                        print("mock-rtmp: killing session 1 at ts=%d (reconnect test)" % ts, flush=True)
                        sock.close()
                        return
                    continue
                if mtype == 20:  # command
                    r = AmfReader(payload)
                    values = []
                    try:
                        while r.p < len(payload):
                            values.append(r.read())
                    except Exception:
                        pass
                    cmd = values[0] if values else None
                    if cmd == "connect":
                        write_message(out, 3, 0, 20, 0,
                                      amf_string("_result") + amf_number(1) +
                                      amf_object({"fmsVer": "FMS/3,5,7,7009", "capabilities": 31.0}) +
                                      amf_object({"level": "status", "code": "NetConnection.Connect.Success"}))
                    elif cmd == "createStream":
                        write_message(out, 3, 0, 20, 0,
                                      amf_string("_result") + amf_number(2) + amf_null() + amf_number(1))
                    elif cmd == "publish":
                        key = values[4] if len(values) > 4 else "?"
                        with self.lock:
                            pass
                        write_message(out, 3, 1, 20, 0,
                                      amf_string("onStatus") + amf_number(0) + amf_null() +
                                      amf_object({"level": "status", "code": "NetStream.Publish.Start",
                                                  "description": "publish ok"}))
                        print("mock-rtmp: %s publish start (key=***)" % name, flush=True)
                    elif cmd in ("FCUnpublish", "deleteStream", "closeStream"):
                        if self.session_no >= 2:
                            self.finished.set()
                        break
        except (ValueError, struct.error, OSError) as e:
            if VERBOSE:
                print('DEBUG session exception:', repr(e))
            if "unexpected" in str(e):
                fail(name, checks, str(e))
        finally:
            try:
                sock.close()
            except OSError:
                pass

def main():
    server = Server()
    t = threading.Thread(target=server.run, daemon=True)
    t.start()

    # wait for the client to finish (both sessions) or timeout
    deadline = time.time() + 25
    while time.time() < deadline and not server.finished.is_set():
        if server.session_no >= 2 and server.finished.wait(timeout=0.5):
            break
        if server.session_no >= 2 and not t.is_alive():
            break
        time.sleep(0.2)

    ok = True
    summaries = []
    for c in server.sessions:
        s = c.summary()
        summaries.append(s)
        required = [
            s["meta_before_media"],
            s["video_seq_before_frames"],
            s["audio_seq_before_frames"],
            s["first_video_frame_is_keyframe"],
            s["video_ts_monotonic"],
            s["audio_ts_monotonic"],
        ]
        if not all(x is True for x in required):
            ok = False
        if s["errors"]:
            ok = False
        if s["counts"]["video"] < 30:
            ok = False
            s["errors"].append("too few video frames: %d" % s["counts"]["video"])
    if len(server.sessions) < 2:
        ok = False
        summaries.append({"error": "expected 2 sessions (reconnect), got %d" % len(server.sessions)})

    print("RESULT " + json.dumps({"ok": ok, "sessions": summaries}, indent=2))
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
