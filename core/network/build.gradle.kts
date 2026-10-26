plugins {
    alias(libs.plugins.kleinaberlaut.android.library)
}

android {
    namespace = "keinabersmart.kleinaberlaut.core.network"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
}
