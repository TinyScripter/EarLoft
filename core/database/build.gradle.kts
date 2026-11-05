plugins {
    alias(libs.plugins.tinyamp.android.library)
    alias(libs.plugins.tinyamp.hilt)
    alias(libs.plugins.tinyamp.room)
}

android {
    namespace = "de.kleinabersmart.tinyamp.core.database"

    // Migrationstests (Robolectric) lesen die exportierten Schemas als Assets.
    sourceSets.getByName("test").assets.directories.add("$projectDir/schemas")
}

dependencies {
    api(projects.core.model)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Neue Schemas entstehen im selben Lauf; erst danach dürfen die Test-Assets zusammengestellt werden.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("UnitTestAssets") }.configureEach {
    dependsOn("copyRoomSchemas")
}
