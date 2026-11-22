import de.kleinabersmart.earloft.buildlogic.BuildConfig
import de.kleinabersmart.earloft.buildlogic.Platform
import de.kleinabersmart.earloft.buildlogic.appVersion

plugins {
    alias(libs.plugins.earloft.android.application)
    alias(libs.plugins.earloft.android.compose)
    alias(libs.plugins.earloft.hilt)
    alias(libs.plugins.kotlin.serialization)
    // Only the library export for the phone app's license list; the watch shows no list of its own.
    alias(libs.plugins.aboutlibraries.base)
}

aboutLibraries {
    export {
        variant = "release"
        outputFile = layout.buildDirectory.file("aboutLibraries/wear-libraries.json")
    }
}

// The plugin also attaches the list to the watch APK's resources; it is not needed there.
tasks.matching { it.name.startsWith("prepareLibraryDefinitions") }.configureEach { enabled = false }

android {
    namespace = "de.kleinabersmart.earloft.wear"

    defaultConfig {
        // Must match the phone app (Wearable Data Layer).
        applicationId = "de.kleinabersmart.earloft"
        minSdk = BuildConfig.MIN_SDK_WEAR
        versionCode = appVersion.versionCode(Platform.WEAR_OS)
        versionName = appVersion.name
    }

    // The languages for the system's per-app language setting come from the translations themselves, so the list can't drift
    // apart from them (review H57; the phone keeps `locales_config.xml` by hand). The default language is in
    // `res/resources.properties`.
    androidResources {
        generateLocaleConfig = true
    }

    buildTypes {
        debug {
            // Same as the phone app: debug installable next to release, applicationId of both debug apps identical.
            applicationIdSuffix = ".debug"
        }
        release {
            // Same as the phone app: only optimize androidx.** (kotlin.**/kotlinx.** caused a crash on startup there).
            optimization {
                enable = true
                packageScope = setOf("androidx.**")
            }
        }
    }
}

dependencies {
    implementation(projects.core.wearsync)
    implementation(projects.core.database)
    implementation(projects.core.data)
    implementation(projects.core.download)
    implementation(projects.core.playback)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.materialkolor)
    // `dynamicColorScheme` from materialkolor returns an M3 ColorScheme; materialkolor does not ship the class itself
    // (compileOnly crashed on the watch with ClassNotFoundException).
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.remote.interactions)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.kotlinx.coroutines.guava)
    // The preview annotations (WearPreviewDevices etc.) live here and are needed in the release sources too.
    implementation(libs.androidx.wear.compose.ui.tooling)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
