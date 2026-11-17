import java.util.Properties

plugins {
    alias(libs.plugins.earloft.android.library)
    alias(libs.plugins.earloft.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Zugang zu Podcast-Verzeichnissen (Konzept 12.10); nie im Repo, nur in local.properties.
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

// Release ohne Schlüssel: bauen, aber deutlich warnen (Review V23). README und Datenschutzerklärung nennen Podcast Index; fremde
// Builds ohne eigenen Schlüssel sollen trotzdem gehen, die App bietet das Verzeichnis dann einfach nicht an.
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
    // XmlPullParser-Implementierung für die JVM-Tests (android.jar hat nur Stubs).
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlinx.coroutines.test)

    // Auf dem Gerät: der XmlPullParser von Android statt kxml2 (Review D24).
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
