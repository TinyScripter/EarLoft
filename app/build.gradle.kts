plugins {
    alias(libs.plugins.tinyamp.android.application)
    alias(libs.plugins.tinyamp.android.compose)
    alias(libs.plugins.tinyamp.hilt)
    alias(libs.plugins.kotlin.serialization)
    // Erzeugt beim Build die Liste aller Abhängigkeiten samt Lizenz (Einstellungen → Lizenzen).
    alias(libs.plugins.aboutlibraries.android)
}

android {
    namespace = "de.kleinabersmart.tinyamp"

    defaultConfig {
        applicationId = "de.kleinabersmart.tinyamp"
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            // Debug und Release lassen sich parallel installieren. Die Uhr bekommt dasselbe Suffix,
            // damit sich die Debug-Apps auf Handy und Uhr weiter finden (gleiche applicationId, Data Layer).
            applicationIdSuffix = ".debug"
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**")
            }
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.playback)
    implementation(projects.core.download)
    implementation(projects.core.wearsync)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    // Navigation Rail statt Bottom-Bar auf breiten Fenstern (Tablet, Foldable, Querformat).
    implementation(libs.androidx.compose.navigationsuite)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    // Podcast-Bilder kommen direkt aus dem Feed (Web-Adressen); ohne dies lädt Coil keine http(s)-URLs.
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    // Widget: Sekundentakt bei Tempo ≠ 1× nur, solange die App nicht selbst im Vordergrund ist.
    implementation(libs.androidx.lifecycle.process)
    // Chromecast nur auf dem Handy; :core:playback (auch von :wear genutzt) kennt nur PlayerFactory.
    implementation(libs.androidx.media3.cast)
    implementation(libs.aboutlibraries.compose.m3)

    // ViewModel-Tests (Review A3): echter Hilt-Graph unter Robolectric, nur die Wiedergabe als Fake.
    testImplementation(libs.hilt.android.testing)
    // Testdaten direkt in die Datenbank schreiben.
    testImplementation(projects.core.database)
    kspTest(libs.hilt.compiler)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
