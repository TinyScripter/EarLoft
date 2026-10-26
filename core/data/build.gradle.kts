plugins {
    alias(libs.plugins.kleinaberlaut.android.library)
}

android {
    namespace = "keinabersmart.kleinaberlaut.core.data"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datasource)
}
