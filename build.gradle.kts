// Root build file.
//
// NOTE FOR REVIEWERS: this Gradle setup exists so the project opens and builds
// normally in Android Studio (with internet access). The project has ZERO
// external dependencies by design — only the Kotlin stdlib and the Android
// platform. That makes it buildable both ways:
//
//   * Android Studio / Gradle : standard AGP build
//   * ./build.sh              : fully offline CLI build (aapt2 + kotlinc + d8 +
//                               apksigner, no Gradle, no network needed once
//                               tools are fetched)
//
// Both paths compile the exact same sources in app/src/main.

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
