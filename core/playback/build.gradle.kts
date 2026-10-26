plugins {
    alias(libs.plugins.kleinaberlaut.android.library)
}

android {
    namespace = "keinabersmart.kleinaberlaut.core.playback"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.download)
}
