# Offline toolchain provenance

`tools/fetch-tools.sh` downloads every tool used by `build.sh` into
`/tmp/lh-tools`. Nothing here ships inside the APK; these are build-time
tools only. Verify licenses at the sources before redistribution.

| Tool | Source | License (upstream) |
|---|---|---|
| kotlin-compiler 2.4.20 (kotlinc + kotlin-stdlib.jar) | npm package `kotlin-compiler` → dist from Maven Central | Apache-2.0 (Kotlin) |
| aapt2 v2.20-15009934 (linux x64) | npm package `aaptjs3` | Apache-2.0 (AOSP); wrapper MIT |
| android-33 framework jar (compile classpath, stubs) | github.com/CirQ/android-platforms | Apache-2.0 (AOSP SDK) |
| d8.jar (DEX compiler) | github.com/ReversecLabs/drozer `lib/` | Apache-2.0 (AOSP tool, bundled) |
| zipalign + lib64 | github.com/LineageOS/android_prebuilts_build-tools | Apache-2.0 (AOSP) |
| apktool.jar / apksigner.jar | npm package `@postar/apktool-node` | Apache-2.0 |
| JDK runtime for tests | pip `jdk4py` (Temurin) | GPL-2.0+CE |

The Gradle wrapper path (B) uses the official Android Gradle Plugin with
**zero** app dependencies, so the APK contains only first-party code plus the
Kotlin stdlib (dexed in by d8).
