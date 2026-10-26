import keinabersmart.kleinaberlaut.buildlogic.BuildConfig

plugins {
    alias(libs.plugins.kleinaberlaut.android.application)
    alias(libs.plugins.kleinaberlaut.android.compose)
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.tooling.preview)
    debugImplementation(libs.androidx.wear.compose.ui.tooling)
}
