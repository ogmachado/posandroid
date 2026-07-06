// Top-level build file — declares plugin versions shared across modules.
// Single Gradle `:app` module per proposal.md (no reactor/multi-module structure).
plugins {
    id("com.android.application") version "8.4.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // KSP powers the Room annotation processor (task 0.4) — version pinned to
    // match the Kotlin version above (KSP releases are Kotlin-version-scoped).
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
