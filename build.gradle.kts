// Root build file - declares the plugins used by the :app module.
// Versions chosen to be mutually compatible (AGP 8.5 + Kotlin 2.0).
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}
