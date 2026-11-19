import java.util.Properties

plugins {
    alias(libs.plugins.earloft.android.library)
    alias(libs.plugins.earloft.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Access to podcast directories (concept 12.10); never in the repo, only in local.properties.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun localString(name: String): String = "\"${localProperties.getProperty(name).orEmpty().trim()}\""

android {
    namespace = "de.kleinabersmart.earloft.core.network"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "PODCAST_INDEX_KEY", localString("podcastindex.key"))
        buildConfigField("String", "PODCAST_INDEX_SECRET", localString("podcastindex.secret"))
        buildConfigField("boolean", "FYYD_ENABLED", localProperties.getProperty("fyyd.enabled", "false").trim().toBoolean().toString())
    }
}

// Release without keys: build, but warn clearly (review V23). README and privacy policy mention Podcast Index; third-party
// builds without their own key should still work, the app then simply doesn't offer the directory.
if (listOf("podcastindex.key", "podcastindex.secret").any { localProperties.getProperty(it).isNullOrBlank() }) {
    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        doFirst {
            logger.warn(
                "w: Release ohne Podcast Index: podcastindex.key bzw. podcastindex.secret fehlen in local.properties. " +
                    "Die App bietet das Verzeichnis dann nicht an.",
            )
        }
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)

    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.okhttp.mockwebserver)
    // XmlPullParser implementation for the JVM tests (android.jar only has stubs).
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlinx.coroutines.test)

    // On the device: Android's XmlPullParser instead of kxml2 (review D24).
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
