plugins {
    alias(libs.plugins.kleinaberlaut.android.library)
}

android {
    namespace = "keinabersmart.kleinaberlaut.core.download"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.database)
    implementation(projects.core.network)
}
