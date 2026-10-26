plugins {
    alias(libs.plugins.kleinaberlaut.android.library)
    alias(libs.plugins.kleinaberlaut.hilt)
}

android {
    namespace = "keinabersmart.kleinaberlaut.core.data"
}

dependencies {
    api(projects.core.model)
    api(projects.core.datasource)
    implementation(projects.core.common)
    implementation(projects.core.database)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
