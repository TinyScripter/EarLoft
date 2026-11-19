plugins {
    alias(libs.plugins.earloft.android.library)
    alias(libs.plugins.earloft.hilt)
    alias(libs.plugins.earloft.room)
}

android {
    namespace = "de.kleinabersmart.earloft.core.database"

    // Migration tests (Robolectric) read the exported schemas as assets.
    sourceSets.getByName("test").assets.directories.add("$projectDir/schemas")
}

dependencies {
    api(projects.core.model)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}

// New schemas are created in the same run; only afterwards may the test assets be assembled.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("UnitTestAssets") }.configureEach {
    dependsOn("copyRoomSchemas")
}
