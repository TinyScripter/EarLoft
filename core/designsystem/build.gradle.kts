plugins {
    alias(libs.plugins.kleinaberlaut.android.library)
    alias(libs.plugins.kleinaberlaut.android.compose)
}

android {
    namespace = "de.kleinabersmart.kleinaberlaut.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.materialkolor)
}
