import java.util.Properties

plugins {
    alias(libs.plugins.kleinaberlaut.android.library)
    alias(libs.plugins.kleinaberlaut.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Zugang zu Podcast-Verzeichnissen (Konzept 12.10); nie im Repo, nur in local.properties.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun localString(name: String): String = "\"${localProperties.getProperty(name).orEmpty().trim()}\""

android {
    namespace = "keinabersmart.kleinaberlaut.core.network"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "PODCAST_INDEX_KEY", localString("podcastindex.key"))
        buildConfigField("String", "PODCAST_INDEX_SECRET", localString("podcastindex.secret"))
        buildConfigField("boolean", "FYYD_ENABLED", localProperties.getProperty("fyyd.enabled", "false").trim().toBoolean().toString())
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
}
