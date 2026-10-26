plugins {
    alias(libs.plugins.kleinaberlaut.android.library)
    alias(libs.plugins.kleinaberlaut.hilt)
    alias(libs.plugins.kleinaberlaut.room)
}

android {
    namespace = "keinabersmart.kleinaberlaut.core.database"
}

dependencies {
    api(projects.core.model)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
