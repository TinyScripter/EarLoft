plugins {
    alias(libs.plugins.kleinaberlaut.android.library)
}

android {
    namespace = "keinabersmart.kleinaberlaut.core.datasource"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.network)
}
