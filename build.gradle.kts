// Plugins are declared once here (apply false) so every module shares one
// Kotlin Gradle plugin version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
