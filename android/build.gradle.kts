buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        // AGP 9 ships an older built-in KGP; the AndroidX/Compose stack below is
        // compiled against a newer Kotlin, so pin the toolchain forward.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
}
