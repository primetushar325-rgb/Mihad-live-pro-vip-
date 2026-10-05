#!/usr/bin/env bash
# Runs the pure-JVM protocol test suite:
#   1. spins up tools/rtmp-mock-server.py (validates YouTube-ingest invariants)
#   2. compiles the app's pure protocol layer + TestMain with kotlinc
#   3. runs it against the mock server (kill@3s → reconnect → finish)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${LH_TOOLS:-/tmp/lh-tools}"
VENV="${LH_VENV:-/tmp/venv}"
PORT="${TEST_PORT:-19351}"

JH="$("$VENV/bin/python" -c 'import jdk4py;print(jdk4py.JAVA_HOME)')"
export JAVA_HOME="$JH"
export PATH="$JH/bin:$PATH"

if [ ! -f "$TOOLS/kotlinc/lib/kotlin-compiler.jar" ]; then
  "$ROOT/tools/fetch-tools.sh"
fi

echo "== starting mock RTMP server on :$PORT =="
python3 "$ROOT/tools/rtmp-mock-server.py" "$PORT" &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT
sleep 0.6

echo "== compiling test =="
BUILD="$ROOT/.cli-build/jvm-test"
rm -rf "$BUILD"; mkdir -p "$BUILD/classes"
SRC="$ROOT/app/src/main/java/com/livehead/app"
"$TOOLS/kotlinc/bin/kotlinc" -jvm-target 17 -d "$BUILD/classes" -nowarn \
  "$SRC/core/AppLog.kt" \
  "$SRC/stream/Amf0.kt" \
  "$SRC/stream/Flv.kt" \
  "$SRC/stream/Pacer.kt" \
  "$SRC/stream/rtmp/RtmpEndpoint.kt" \
  "$SRC/stream/rtmp/RtmpConnection.kt" \
  "$ROOT/tools/jvm-test/TestMain.kt" 2>&1 | grep -v "warning:" || true

echo "== running =="
"$JH/bin/java" -cp "$BUILD/classes:$TOOLS/kotlinc/lib/kotlin-stdlib.jar" \
  com.livehead.app.test.TestMain "$PORT"

echo
echo "== mock server verdict =="
wait $SERVER_PID || true
