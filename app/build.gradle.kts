plugins {
    alias(libs.plugins.kleinaberlaut.android.application)
    alias(libs.plugins.kleinaberlaut.android.compose)
    alias(libs.plugins.kleinaberlaut.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "keinabersmart.kleinaberlaut"

    defaultConfig {
        applicationId = "keinabersmart.kleinaberlaut"
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            // Debug und Release lassen sich parallel installieren. Die Uhr bekommt dasselbe Suffix,
            // damit sich die Debug-Apps auf Handy und Uhr weiter finden (gleiche applicationId, Data Layer).
            applicationIdSuffix = ".debug"
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**")
            }
        }
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.playback)
    implementation(projects.core.download)
    implementation(projects.core.wearsync)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    // Chromecast nur auf dem Handy; :core:playback (auch von :wear genutzt) kennt nur PlayerFactory.
    implementation(libs.androidx.media3.cast)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
