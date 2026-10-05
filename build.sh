#!/usr/bin/env bash
# ============================================================================
# LIVE HEAD — offline release build (no Gradle, no Android Studio needed)
#
# Pipeline: aapt2 compile+link -> R.kt -> kotlinc -> d8 -> zip -> zipalign
#           -> keytool (once) -> apksigner
#
# The Gradle files in this repo build the same sources in Android Studio;
# this script exists for reproducible builds without any network after the
# tools are fetched once (tools/fetch-tools.sh).
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${LH_TOOLS:-/tmp/lh-tools}"
VENV="${LH_VENV:-/tmp/venv}"

VERSION_NAME="${VERSION_NAME:-1.0.0}"
VERSION_CODE="${VERSION_CODE:-1}"
OUT_DIR="${OUT_DIR:-$ROOT/release}"
KEYSTORE="${KEYSTORE:-$ROOT/keystore/livehead-release.jks}"
KS_PASS="${KS_PASS:-livehead}"

BUILD="$ROOT/.cli-build"
APP="$ROOT/app/src/main"

# --- toolchain ---------------------------------------------------------------
if [ ! -x "$TOOLS/bin/aapt2" ] || [ ! -f "$TOOLS/lib/android-33.jar" ]; then
  "$ROOT/tools/fetch-tools.sh"
fi

# JDK: prefer the sandbox's jdk4py venv; fall back to any pre-set JAVA_HOME
# (this is how CI provides the JDK).
if [ -x "$VENV/bin/python" ] && "$VENV/bin/python" -c 'import jdk4py' >/dev/null 2>&1; then
  JH="$("$VENV/bin/python" -c 'import jdk4py;print(jdk4py.JAVA_HOME)')"
else
  JH="${JAVA_HOME:-$(command -v java >/dev/null 2>&1 && dirname "$(dirname "$(command -v java)")" || true)}"
fi
if [ -z "$JH" ] || [ ! -x "$JH/bin/java" ]; then
  echo "ERROR: no JDK found (set JAVA_HOME or create $VENV with jdk4py)" >&2
  exit 1
fi
export JAVA_HOME="$JH"
export PATH="$JH/bin:$PATH"

JAVA="$JH/bin/java"
KOTLINC="$TOOLS/kotlinc/bin/kotlinc"
AAPT2="$TOOLS/bin/aapt2"
D8_JAR="$TOOLS/lib/d8.jar"
APKSIGNER="$TOOLS/lib/apksigner.jar"
PLATFORM="$TOOLS/lib/android-33.jar"
STDLIB="$TOOLS/kotlinc/lib/kotlin-stdlib.jar"

echo "== cleaning =="
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT_DIR"

echo "== aapt2 compile =="
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "== aapt2 link =="
"$AAPT2" link -o "$BUILD/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$BUILD/res.zip"

echo "== R.java -> R.kt =="
python3 "$ROOT/tools/gen_r_kt.py" "$BUILD/gen/com/livehead/app/R.java" "$BUILD/R.kt"

echo "== kotlinc =="
find "$APP/java" -name '*.kt' | sort > "$BUILD/sources.txt"
# The kotlinc script needs JAVA_HOME; give it a quiet, deterministic run.
"$KOTLINC" \
  -jvm-target 17 \
  -cp "$PLATFORM" \
  -d "$BUILD/classes" \
  -nowarn \
  "@$BUILD/sources.txt" "$BUILD/R.kt"
echo "   compiled $(find "$BUILD/classes" -name '*.class' | wc -l) classes"

echo "== d8 (dex, min-api 26, kotlin-stdlib included) =="
(cd "$BUILD/classes" && zip -q -r "$BUILD/classes.zip" .)
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release \
  --lib "$PLATFORM" \
  --min-api 26 \
  --output "$BUILD/dex" \
  "$BUILD/classes.zip" "$STDLIB"
ls -la "$BUILD/dex/"

echo "== package =="
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
zip -q -j "$BUILD/unsigned.apk" "$BUILD/dex/classes.dex"

if LD_LIBRARY_PATH="$TOOLS/lib64" "$TOOLS/bin/zipalign" 2>&1 | grep -i "alignment" > /dev/null; then
  echo "== zipalign =="
  LD_LIBRARY_PATH="$TOOLS/lib64" "$TOOLS/bin/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
else
  echo "== zipalign skipped (tool unavailable) =="
  cp "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
fi

echo "== keystore =="
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  "$JH/bin/keytool" -genkeypair \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias livehead -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=LIVE HEAD, OU=Streaming, O=LiveHead" >/dev/null 2>&1
  echo "   created $KEYSTORE (password: $KS_PASS — replace for production!)"
fi

echo "== sign =="
"$JAVA" -jar "$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version 26 \
  --out "$OUT_DIR/LiveHead-v$VERSION_NAME.apk" \
  "$BUILD/aligned.apk"

echo "== verify =="
"$JAVA" -jar "$APKSIGNER" verify --print-certs "$OUT_DIR/LiveHead-v$VERSION_NAME.apk" | grep -E "Digest|DN" || true

echo
echo "SUCCESS: $OUT_DIR/LiveHead-v$VERSION_NAME.apk"
ls -la "$OUT_DIR/LiveHead-v$VERSION_NAME.apk"
"$AAPT2" dump badging "$OUT_DIR/LiveHead-v$VERSION_NAME.apk" | head -6 || true
