plugins {
    alias(libs.plugins.tinyamp.android.library)
    alias(libs.plugins.tinyamp.hilt)
}

android {
    namespace = "de.kleinabersmart.tinyamp.core.playback"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.download)

    api(libs.androidx.media3.session)
    implementation(projects.core.network)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
