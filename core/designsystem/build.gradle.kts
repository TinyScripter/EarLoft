plugins {
    alias(libs.plugins.earloft.android.library)
    alias(libs.plugins.earloft.android.compose)
}

android {
    namespace = "de.kleinabersmart.earloft.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.materialkolor)
}
