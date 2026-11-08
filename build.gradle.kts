// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Kotlin is built into AGP 9; this only puts the catalog's Kotlin version on the build classpath for it
    alias(libs.plugins.kotlin.android) apply false
}