plugins {
    alias(libs.plugins.tinyamp.android.library)
    alias(libs.plugins.tinyamp.android.compose)
}

android {
    namespace = "de.kleinabersmart.tinyamp.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.materialkolor)
}
