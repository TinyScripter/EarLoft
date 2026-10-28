import keinabersmart.kleinaberlaut.buildlogic.BuildConfig

plugins {
    alias(libs.plugins.kleinaberlaut.android.application)
    alias(libs.plugins.kleinaberlaut.android.compose)
    alias(libs.plugins.kleinaberlaut.hilt)
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
}

dependencies {
    implementation(projects.core.wearsync)
    implementation(projects.core.database)
    implementation(projects.core.data)
    implementation(projects.core.download)
    implementation(projects.core.playback)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.remote.interactions)
    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.kotlinx.coroutines.guava)
    debugImplementation(libs.androidx.wear.compose.ui.tooling)
}
