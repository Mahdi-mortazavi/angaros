plugins {
    id("com.android.application") version "9.4.1" apply false
    // Also pins the Kotlin Gradle plugin that AGP's built-in Kotlin compiles with (2.2.10 would be too old for
    // the 2026 Compose libraries' metadata).
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
