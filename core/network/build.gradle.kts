plugins {
    alias(libs.plugins.kleinaberlaut.android.library)
    alias(libs.plugins.kleinaberlaut.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "keinabersmart.kleinaberlaut.core.network"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)

    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
