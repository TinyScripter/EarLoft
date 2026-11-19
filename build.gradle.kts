// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Own R8 version instead of the one bundled with AGP (reason in the version catalog at `r8`).
buildscript {
    dependencies {
        classpath(libs.android.r8)
    }
}

// All plugins are declared here once so the convention plugins from build-logic find them
// with the same version in the same classloader.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.aboutlibraries.android) apply false
    alias(libs.plugins.aboutlibraries.base) apply false
}
