import de.kleinabersmart.earloft.buildlogic.BuildConfig

plugins {
    alias(libs.plugins.earloft.android.application)
    alias(libs.plugins.earloft.android.compose)
    alias(libs.plugins.earloft.hilt)
    alias(libs.plugins.kotlin.serialization)
    // Nur der Export der Bibliotheken für die Lizenzliste der Handy-App; die Uhr zeigt keine eigene.
    alias(libs.plugins.aboutlibraries.base)
}

aboutLibraries {
    export {
        variant = "release"
        outputFile = layout.buildDirectory.file("aboutLibraries/wear-libraries.json")
    }
}

// Das Plugin hängt die Liste auch an die Ressourcen der Uhr-APK; dort wird sie nicht gebraucht.
tasks.matching { it.name.startsWith("prepareLibraryDefinitions") }.configureEach { enabled = false }

android {
    namespace = "de.kleinabersmart.earloft.wear"

    defaultConfig {
        // Muss mit der Phone-App übereinstimmen (Wearable Data Layer).
        applicationId = "de.kleinabersmart.earloft"
        minSdk = BuildConfig.MIN_SDK_WEAR
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            // Wie bei der Phone-App: Debug neben Release installierbar, applicationId beider Debug-Apps gleich.
            applicationIdSuffix = ".debug"
        }
        release {
            // Wie bei der Phone-App: nur androidx.** optimieren (kotlin.**/kotlinx.** führten dort zum Absturz beim Start).
            optimization {
                enable = true
                packageScope = setOf("androidx.**")
            }
        }
    }
}

dependencies {
    implementation(projects.core.wearsync)
    implementation(projects.core.database)
    implementation(projects.core.data)
    implementation(projects.core.download)
    implementation(projects.core.playback)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.materialkolor)
    // `dynamicColorScheme` von materialkolor liefert ein M3-ColorScheme; materialkolor bringt die Klasse nicht selbst mit
    // (compileOnly stürzte auf der Uhr mit ClassNotFoundException ab).
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.remote.interactions)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.kotlinx.coroutines.guava)
    // Die Preview-Annotationen (WearPreviewDevices u. a.) liegen hier und werden auch im Release-Quelltext gebraucht.
    implementation(libs.androidx.wear.compose.ui.tooling)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
