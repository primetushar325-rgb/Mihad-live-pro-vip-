# LIVE HEAD — Camera → RTMPS → YouTube Live

**v2.0.0 is a camera live-streaming app**: stream your phone's **camera +
microphone** to a **YouTube Live** RTMPS endpoint, with anti-freeze
protection as the top engineering priority and launch reliability as the
first commandment. (v1.x streamed a local video file; that protocol stack is
kept in the tree — see `stream/RtmpStreamingEngine` — but the product is now
camera-first.)

Startup is featherweight by design: `App.onCreate()` only creates the
notification channel and installs the crash reporter. The camera opens ONLY
when the user taps **Enable camera preview**; the streaming engine is created
ONLY by the foreground service after **START LIVE**.

Built with **zero third-party libraries** — no AndroidX, no Jetpack Compose,
no OkHttp, no ExoPlayer, no Kotlin coroutines. Plain `android.view` UI,
hand-written `StateFlow`, a hand-written **RTMP/RTMPS client** and
`MediaCodec` pipelines. The release APK is ~780 KB.

---

## Features

| Area | What it does |
|---|---|
| Input | Any local video (SAF picker). Video is re-encoded to H.264; audio (any codec) is decoded to PCM and re-encoded to AAC-LC. Silent videos get generated silence. |
| Output | RTMP **and RTMPS** (TLS) to YouTube ingestion URLs (`rtmps://a.rtmp.youtube.com/live2` etc.). Stream key parsed from the URL or entered separately. |
| Resolution | Auto (≤1920×1088, even dims, rotation-corrected), 1080p, 720p, 480p. FPS 30/24/60. Bitrate ladder 4M/3.2M/2.5M/1.8M/1.2M/800k (auto-clamped per resolution, audio 128k stereo). |
| Looping | Seamless loop: both tracks wrap to their own boundary, keyframe forced at wrap, timestamps continue monotonically (never restart to 0 mid-stream). |
| Anti-freeze | Pacer with virtual clock: if the network stalls, encoding pauses and resumes without timestamp gaps or bursts (no unbounded buffering, no freeze-drops). Watchdogs: STALL 5 s / WRITE_HANG 8 s. |
| Reconnect | Exponential backoff (2 s → max interval, default 60 s, configurable). On reconnect: metadata + AVC/AAC sequence headers resent, timestamps rebased to ~0, keyframe requested. Survives YouTube ingest drops. |
| Security | Stream URL+key and settings stored **AES-256/GCM** encrypted; the AES key is wrapped by an **AndroidKeyStore** hardware key (StrongBox when available). Keys never appear in logs (`toString` redacts). |
| Foreground service | Persistent notification with live stats, STOP action, wake lock, battery/thermal/network monitoring, auto-stop on terminal state. |
| UI | Setup screen (dark, red live-accent), live screen with 1 s stats grid (elapsed, resolution, bitrate, network, dropped frames, reconnects, loops, data sent, battery), error/thermal/low-battery banners, Diagnostics screen (logs, video probe info, app info). All in English per spec. |

## Why a hand-written RTMP client?

The classic failure mode of file-to-RTMP streamers on YouTube is a **freeze
after 2–3 seconds**. It is always caused by one of a handful of protocol
violations, so this client is built to make them impossible:

1. `onMetaData` data message before any media tag.
2. AVC sequence header (`0x17 0x00`, full `AVCDecoderConfigurationRecord`) before any video frame.
3. AAC sequence header (`0xAF 0x00`, AudioSpecificConfig) before any audio frame.
4. First video frame of every connection/loop iteration is a **keyframe**.
5. Per-track timestamps strictly monotonic; audio continuous (no >600 ms gaps).
6. On reconnect, sequence headers resent and the clock rebased to ~0.

All six are enforced by an automated integration test (below), not just by
code review.

## Repository layout

```
app/src/main/java/com/livehead/app/
  App.kt                       Application: config load, crash-safe logging
  core/StateFlow.kt            minimal observable state (no coroutines)
  core/Fmt.kt, core/AppLog.kt  formatting helpers, in-memory ring logger (JVM-pure)
  data/Model.kt                StreamSettings, choices, constants
  data/SecureStore.kt          AES/GCM + AndroidKeyStore encrypted storage
  data/ConfigStore.kt          plaintext config (defaults, last selections)
  data/YouTubeController.kt    URL/key parsing + validation (uses RtmpEndpoint)
  stream/Amf0.kt               AMF0 encoder/decoder
  stream/Flv.kt                FLV audio/video/data tag muxer (AnnexB→AVCC, AAC ASC)
  stream/Pacer.kt              virtual-clock pacer (freeze/unfreeze, rebase)
  stream/GlScaler.kt           GPU (GLES2) video scaler + rotation
  stream/Pipelines.kt          MediaSink contract, StreamCounters, VideoPipeline
  stream/AudioPipeline.kt      decode→PCM→AAC pipeline + silence generator
  stream/StreamingEngine.kt    interface + RtmpStreamingEngine (supervisor, watchdog, reconnect)
  stream/rtmp/RtmpEndpoint.kt  rtmp/rtmps URL parsing, key extraction
  stream/rtmp/RtmpConnection.kt RTMP client: handshake, chunking, commands, TLS
  service/StreamingService.kt  foreground service, notification, env monitoring
  controller/StreamingController.kt UI-facing state bridge (no Context refs)
  ui/HomeActivity.kt           setup + live screens
  ui/SettingsActivity.kt       stream settings
  ui/DiagnosticsActivity.kt    logs + device probe
```

## Build

Two interchangeable paths, same output:

**A. Offline script (no Android SDK/Gradle needed):**

```bash
./build.sh                    # auto-fetches toolchain to /tmp/lh-tools if missing
```

Pipeline: `aapt2 compile+link → R.java→R.kt → kotlinc (jvm-target 17) → d8
(+kotlin-stdlib) → zip classes.dex into base.apk → zipalign → apksigner`.
Output: `release/LiveHead-v<version>.apk`.

**B. Android Studio / Gradle:** standard `app` module wrapper
(`build.gradle.kts`, `settings.gradle.kts`) with no dependencies — open and
Run. (`tools/fetch-tools.sh` re-downloads the offline toolchain if you ever
need path A on a new machine.)

Signing: dev keystore `keystore/livehead-release.jks` (PKCS12, alias
`livehead`, password `livehead`) — **replace before production**:

```bash
keytool -genkeypair -v -keystore keystore/prod.jks -alias livehead \
  -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12
```

## Startup safety & self-diagnosis

LIVE HEAD follows strict startup discipline:

- `Application.onCreate()` does only two things: create the notification
  channel and install the crash reporter. No streaming engine, no network,
  no MediaCodec, no file scanning at launch.
- The streaming engine exists **only** inside the foreground service, which
  starts **only** when the user presses START LIVE.
- Remembered-video re-probing runs on a background thread — app launch is
  never blocked by MediaMetadataRetriever.
- If anything in the launch path throws, `HomeActivity` falls back to a
  programmatic recovery screen showing the exact exception.
- On any uncaught exception the app shows **CrashReportActivity**: the full
  stack trace (selectable/copyable, no adb needed), also written to
  `filesDir/last_crash.txt` and `Android/data/com.livehead.app/files/last_crash.txt`,
  and shown in Diagnostics → LOG.

**If the app does not open on your phone:** install the latest APK, open it —
if there is a crash you will now see the red problem-report screen. Copy that
text and report it; it contains the exact root cause. If instead the phone
shows a black screen or nothing at all, also check that the downloaded file
matches the published SHA-256 (a truncated/mangled download can install but
fail to start).

## CI

GitHub Actions (`.github/workflows/android.yml`) runs the full test suite and
builds the release APK on every push — artifact name: `LIVE-HEAD-release`.

## Test

```bash
./tools/test-protocol.sh      # 35 unit + integration tests (JVM, no emulator)
```

- **Unit tests (JVM, no emulator):** Pacer virtual-clock behavior (6), AMF0
  round-trips (3), FLV/AVCC muxing invariants (13), RtmpEndpoint parsing +
  key redaction (9).
- **Integration test:** a mock YouTube RTMP ingest server
  (`tools/rtmp-mock-server.py`) accepts a real `RtmpConnection`, validates
  all six wire invariants above, **kills the connection at t=3 s** to force
  a reconnect, then verifies the second session continues with rebased
  timestamps, resent sequence headers, keyframe-first frames, ~33 ms video /
  ~23 ms audio cadence and monotonic timestamps. Verdict: `RESULT {"ok": true…}`.
- Set `LH_VERBOSE=1` for chunk-level wire logging.

The harness runs the **same production sources** (`Amf0/Flv/Pacer/RtmpEndpoint/
RtmpConnection/AppLog`) compiled for the JVM — it is not a reimplementation.

It caught two real bugs during development: an AVC sequence-header buffer
underrun (would have broken YouTube video immediately) and an RTMP
chunk-format decode error (`fmt` is the top **2** bits of the basic header,
not 4 — the misread made every reply unparseable).

## Security notes

- Stream credentials are AES-256/GCM encrypted at rest; the AES key is
  generated on-device, wrapped by an AndroidKeyStore key
  (`setStrongBoxBacked` when hardware supports it), and can never leave the
  keystore.
- `RtmpEndpoint.toString()` redacts the stream key; the mock server's logs do
  the same.
- No INTERNET use besides the user-configured ingest URL. No analytics, no
  telemetry, no third-party code.

## Limitations (V1)

- Signed with a **development** key — install via "unknown sources".
- Tested against the protocol-level mock (wire-correct, exhaustive on the
  freeze invariants) but **not yet on a physical device against live YouTube**.
  First real-world run may surface device-specific MediaCodec quirks; the
  engine handles codec fallbacks (H.264 profile fallback matrix, AAC rate
  fallback) but they are device-dependent.
- Battery optimization may still kill the service on some OEMs (the app shows
  the battery-restriction state in Diagnostics).
