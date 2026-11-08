plugins {
    alias(libs.plugins.earloft.android.library)
    alias(libs.plugins.earloft.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "de.kleinabersmart.earloft.core.wearsync"
}

// Protokoll zwischen Handy und Uhr (Wear-Konzept 4) – von :app und :wear gemeinsam genutzt.
dependencies {
    api(projects.core.model)

    api(libs.play.services.wearable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.kotlinx.coroutines.test)
}
