import keinabersmart.kleinaberlaut.buildlogic.BuildConfig

plugins {
    alias(libs.plugins.kleinaberlaut.android.application)
    alias(libs.plugins.kleinaberlaut.android.compose)
    alias(libs.plugins.kleinaberlaut.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "keinabersmart.kleinaberlaut.wear"

    defaultConfig {
        // Muss mit der Phone-App übereinstimmen (Wearable Data Layer).
        applicationId = "keinabersmart.kleinaberlaut"
        minSdk = BuildConfig.MIN_SDK_WEAR
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            // Wie bei der Phone-App: Debug neben Release installierbar, applicationId beider Debug-Apps gleich.
            applicationIdSuffix = ".debug"
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
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.wear.remote.interactions)
    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.kotlinx.coroutines.guava)
    // Die Preview-Annotationen (WearPreviewDevices u. a.) liegen hier und werden auch im Release-Quelltext gebraucht.
    implementation(libs.androidx.wear.compose.ui.tooling)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
