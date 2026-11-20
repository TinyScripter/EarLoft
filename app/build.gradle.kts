import de.kleinabersmart.earloft.buildlogic.Platform
import de.kleinabersmart.earloft.buildlogic.appVersion

plugins {
    alias(libs.plugins.earloft.android.application)
    alias(libs.plugins.earloft.android.compose)
    alias(libs.plugins.earloft.hilt)
    alias(libs.plugins.kotlin.serialization)
    // Generates the list of all dependencies with their licenses at build time (Settings → Licenses).
    alias(libs.plugins.aboutlibraries.android)
}

/**
 * Splits the library list of the watch app (`:wear:exportLibraryDefinitions`) into extra definitions for the AboutLibraries plugin
 * (`libraries/`, `licenses/`). That way the phone's license list also covers everything only the watch uses; the watch shows none of its own.
 */
abstract class WearLicenseDefinitions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wearLibraries: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun split() {
        val out = outputDir.get().asFile.apply { deleteRecursively() }
        @Suppress("UNCHECKED_CAST")
        val export = groovy.json.JsonSlurper().parse(wearLibraries.get().asFile) as Map<String, Any?>
        fun write(dir: String, name: String, value: Any?) =
            out.resolve(dir).apply { mkdirs() }.resolve("$name.json").writeText(groovy.json.JsonOutput.toJson(value))
        @Suppress("UNCHECKED_CAST")
        (export["libraries"] as List<Map<String, Any?>>).forEach { write("libraries", it["uniqueId"].toString().replace(':', '_'), it) }
        @Suppress("UNCHECKED_CAST")
        (export["licenses"] as Map<String, Any?>).forEach { (key, license) -> write("licenses", key, license) }
    }
}

val wearLicenseDefinitions = tasks.register<WearLicenseDefinitions>("wearLicenseDefinitions") {
    dependsOn(":wear:exportLibraryDefinitions")
    wearLibraries = rootProject.layout.projectDirectory.file("wear/build/aboutLibraries/wear-libraries.json")
    outputDir = layout.buildDirectory.dir("generated/wearLicenseDefinitions")
}

aboutLibraries {
    collect {
        configPath = wearLicenseDefinitions.flatMap { it.outputDir }
    }
}

/**
 * Puts `PRIVACY.md` and its translations `PRIVACY.<language>.md` from the project root into the app as assets
 * (About → Privacy policy), so they exist only once. Which file is shown is defined by the string resource
 * `privacy_asset`.
 */
abstract class PrivacyPolicyAsset : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        sources.forEach { it.copyTo(out.resolve(it.name)) }
    }
}

val privacyPolicyAsset = tasks.register<PrivacyPolicyAsset>("privacyPolicyAsset") {
    val privacyFile = Regex("""PRIVACY(\.[\w-]+)?\.md""")
    sources.from(rootProject.layout.projectDirectory.asFile.listFiles { file -> privacyFile.matches(file.name) }.orEmpty())
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(privacyPolicyAsset, PrivacyPolicyAsset::outputDir)
    }
}

android {
    namespace = "de.kleinabersmart.earloft"

    defaultConfig {
        applicationId = "de.kleinabersmart.earloft"
        versionCode = appVersion.versionCode(Platform.MOBILE)
        versionName = appVersion.name
    }

    buildTypes {
        debug {
            // Debug and release can be installed side by side. The watch gets the same suffix
            // so the debug apps on phone and watch still find each other (same applicationId, Data Layer).
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
    // Navigation rail instead of a bottom bar on wide windows (tablet, foldable, landscape).
    implementation(libs.androidx.compose.navigationsuite)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    // Podcast images come straight from the feed (web addresses); without this Coil doesn't load http(s) URLs.
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    // Widget: per-second ticks at speed ≠ 1× only while the app itself is not in the foreground.
    implementation(libs.androidx.lifecycle.process)
    // Keeps the process alive while files are going to the watch (WatchTransferWorker).
    implementation(libs.androidx.work.runtime)
    // Chromecast only on the phone; :core:playback (also used by :wear) only knows PlayerFactory.
    implementation(libs.androidx.media3.cast)
    implementation(libs.aboutlibraries.compose.m3)
    // Audiobookshelf sign-in via OpenID in an Auth Tab (concept 12.11).
    implementation(libs.androidx.browser)

    // ViewModel tests (review A3): real Hilt graph under Robolectric, only playback is faked.
    testImplementation(libs.hilt.android.testing)
    // Write test data directly into the database.
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
