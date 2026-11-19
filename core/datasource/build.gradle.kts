plugins {
    alias(libs.plugins.earloft.android.library)
    alias(libs.plugins.earloft.hilt)
}

android {
    namespace = "de.kleinabersmart.earloft.core.datasource"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    api(projects.core.network)

    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.media3.inspector)

    testImplementation(libs.okhttp.mockwebserver)
    // XmlPullParser for `metadata.opf` (android.jar only has stubs).
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlinx.coroutines.test)
}
