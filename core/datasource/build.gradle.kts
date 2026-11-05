plugins {
    alias(libs.plugins.tinyamp.android.library)
    alias(libs.plugins.tinyamp.hilt)
}

android {
    namespace = "de.kleinabersmart.tinyamp.core.datasource"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    api(projects.core.network)

    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.media3.inspector)
}
