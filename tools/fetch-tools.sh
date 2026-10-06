#!/usr/bin/env bash
# Fetches the complete Android build toolchain into $LH_TOOLS (default /tmp/lh-tools)
# using only networks available in this sandbox: npm registry + GitHub git repos.
#
#   java (JRE 25)      <- pip  jdk4py            (Temurin, GPL+CE)
#   kotlinc 2.4.20     <- npm  kotlin-compiler   (Apache 2.0, JetBrains)
#   aapt2 (BT 34-era)  <- npm  aaptjs3           (Apache 2.0, AOSP)
#   apksigner          <- npm  @postar/apktool-node (Apache 2.0, AOSP)
#   d8 (r8)            <- git  ReversecLabs/drozer (BSD-style, Google)
#   android.jar API 33 <- git  CirQ/android-platforms (Google SDK terms)
#   zipalign + libs    <- git  LineageOS/android_prebuilts_build-tools (Apache 2.0, AOSP)
#
# Everything is verified after download. Re-runs are cheap (skips what exists).
set -euo pipefail

TOOLS="${LH_TOOLS:-/tmp/lh-tools}"
VENV="${LH_VENV:-/tmp/venv}"
CACHE="${LH_CACHE:-/tmp/lh-fetch-cache}"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing dependency: $1" >&2; exit 2; }; }
need curl; need git; need python3; need npm; need unzip; need zip

mkdir -p "$TOOLS/bin" "$TOOLS/lib" "$TOOLS/kotlinc" "$CACHE"

echo "== 1/6 JRE (jdk4py via pip) =="
if [ ! -x "$VENV/bin/python" ]; then
  python3 -m venv "$VENV"
fi
if ! "$VENV/bin/python" -c "import jdk4py" >/dev/null 2>&1; then
  "$VENV/bin/pip" install --quiet jdk4py
fi
JAVA_HOME_DIR="$("$VENV/bin/python" -c 'import jdk4py,os;print(os.path.join(str(jdk4py.JAVA_HOME),"bin"))')"
[ -x "$JAVA_HOME_DIR/java" ] || { echo "jdk4py java not found" >&2; exit 3; }
echo "   java OK"

echo "== 2/6 Kotlin compiler (npm kotlin-compiler) =="
if [ ! -f "$TOOLS/kotlinc/lib/kotlin-compiler.jar" ]; then
  (cd "$CACHE" && [ -f kotlin-compiler-*.tgz ] || npm pack kotlin-compiler --silent >/dev/null)
  tar -xzf "$CACHE"/kotlin-compiler-*.tgz -C "$TOOLS/kotlinc" --strip-components=1
  chmod +x "$TOOLS/kotlinc/bin/"* 2>/dev/null || true
fi
[ -f "$TOOLS/kotlinc/lib/kotlin-compiler.jar" ] || { echo "kotlinc fetch failed" >&2; exit 3; }
echo "   kotlinc OK"

echo "== 3/6 aapt2 (npm aaptjs3) =="
if [ ! -x "$TOOLS/bin/aapt2" ]; then
  (cd "$CACHE" && [ -f aaptjs3-*.tgz ] || npm pack aaptjs3 --silent >/dev/null)
  rm -rf "$CACHE/aaptjs3" && mkdir -p "$CACHE/aaptjs3"
  tar -xzf "$CACHE"/aaptjs3-*.tgz -C "$CACHE/aaptjs3"
  cp "$CACHE/aaptjs3/package/bin/x64/linux/aapt2" "$TOOLS/bin/aapt2"
  chmod +x "$TOOLS/bin/aapt2"
fi
"$TOOLS/bin/aapt2" version >/dev/null 2>&1 || { echo "aapt2 broken" >&2; exit 3; }
echo "   aapt2 OK"

echo "== 4/6 apksigner (npm @postar/apktool-node) + d8 (GitHub drozer) =="
if [ ! -f "$TOOLS/lib/apksigner.jar" ]; then
  (cd "$CACHE" && [ -f postar-apktool-node-*.tgz ] || npm pack @postar/apktool-node --silent >/dev/null)
  rm -rf "$CACHE/apktool-node" && mkdir -p "$CACHE/apktool-node"
  tar -xzf "$CACHE"/postar-apktool-node-*.tgz -C "$CACHE/apktool-node"
  cp "$CACHE/apktool-node/package/lib/apksigner.jar" "$TOOLS/lib/"
fi
if [ ! -f "$TOOLS/lib/d8.jar" ]; then
  if [ -f "$CACHE/drozer-d8.jar" ]; then
    cp "$CACHE/drozer-d8.jar" "$TOOLS/lib/d8.jar"
  else
    rm -rf "$CACHE/drozer"
    git clone --depth 1 --filter=blob:none --sparse \
      https://github.com/ReversecLabs/drozer.git "$CACHE/drozer" >/dev/null 2>&1
    (cd "$CACHE/drozer" && git sparse-checkout set src/drozer/lib >/dev/null 2>&1)
    cp "$CACHE/drozer/src/drozer/lib/d8.jar" "$TOOLS/lib/d8.jar"
    cp "$CACHE/drozer/src/drozer/lib/d8.jar" "$CACHE/drozer-d8.jar"
  fi
fi
[ -f "$TOOLS/lib/apksigner.jar" ] && [ -f "$TOOLS/lib/d8.jar" ] || { echo "sign/dex tool fetch failed" >&2; exit 3; }
echo "   apksigner + d8 OK"

echo "== 5/6 android.jar API 33 (GitHub CirQ/android-platforms) =="
if [ ! -f "$TOOLS/lib/android-33.jar" ]; then
  rm -rf "$CACHE/cirq"
  git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/CirQ/android-platforms.git "$CACHE/cirq" >/dev/null 2>&1
  (cd "$CACHE/cirq" && git sparse-checkout set android-33 >/dev/null 2>&1)
  cp "$CACHE/cirq/android-33/android.jar" "$TOOLS/lib/android-33.jar"
fi
unzip -l "$TOOLS/lib/android-33.jar" 2>/dev/null | grep resources.arsc > /dev/null || {
  echo "android.jar missing resources.arsc" >&2; exit 3; }
echo "   android-33.jar OK (with resources.arsc)"

echo "== 6/6 zipalign (GitHub LineageOS prebuilts) =="
if [ ! -x "$TOOLS/bin/zipalign" ]; then
  rm -rf "$CACHE/los"
  git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/LineageOS/android_prebuilts_build-tools.git "$CACHE/los" >/dev/null 2>&1
  (cd "$CACHE/los" && git sparse-checkout set --no-cone '/linux-x86/bin/zipalign' '/linux-x86/lib64/*' >/dev/null 2>&1)
  cp "$CACHE/los/linux-x86/bin/zipalign" "$TOOLS/bin/"
  cp -r "$CACHE/los/linux-x86/lib64" "$TOOLS/lib64"
  chmod +x "$TOOLS/bin/zipalign"
fi
if LD_LIBRARY_PATH="$TOOLS/lib64" "$TOOLS/bin/zipalign" 2>&1 | grep -i "alignment" > /dev/null; then
  echo "   zipalign OK"
else
  echo "   zipalign unavailable (optional; continuing without it)"
fi

echo
echo "Toolchain ready at $TOOLS"
echo "  java:      $JAVA_HOME_DIR/java"
echo "  kotlinc:   $TOOLS/kotlinc/bin/kotlinc"
echo "  aapt2:     $TOOLS/bin/aapt2"
echo "  d8:        $TOOLS/lib/d8.jar"
echo "  apksigner: $TOOLS/lib/apksigner.jar"
echo "  platform:  $TOOLS/lib/android-33.jar"
