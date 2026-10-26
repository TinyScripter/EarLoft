plugins {
    alias(libs.plugins.kleinaberlaut.android.library)
    alias(libs.plugins.kleinaberlaut.hilt)
    alias(libs.plugins.kleinaberlaut.room)
}

android {
    namespace = "keinabersmart.kleinaberlaut.core.database"
    testOptions.unitTests {
        isIncludeAndroidResources = true
        all {
            // Robolectric braucht auf JDK 25 Zugriff auf interne JDK-Klassen.
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

dependencies {
    api(projects.core.model)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
