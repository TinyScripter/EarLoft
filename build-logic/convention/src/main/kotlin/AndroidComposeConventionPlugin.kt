import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import de.kleinabersmart.tinyamp.buildlogic.libs
import de.kleinabersmart.tinyamp.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * Compose-Compiler + BOM. Bewusst ohne Material-Bibliothek, damit das Plugin
 * für Phone (Material 3) und Wear (Wear Material 3) gleichermaßen passt.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.configure<ComposeCompilerGradlePluginExtension> {
                // Modellklassen aus Modulen ohne Compose-Compiler als stabil (Review C4).
                stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf"))
            }

            pluginManager.withPlugin("com.android.application") {
                extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
            }
            pluginManager.withPlugin("com.android.library") {
                extensions.configure<LibraryExtension> { buildFeatures.compose = true }
            }

            dependencies {
                val bom = platform(libs.library("androidx-compose-bom"))
                "implementation"(bom)
                "androidTestImplementation"(bom)
                "implementation"(libs.library("androidx-compose-ui"))
                "implementation"(libs.library("androidx-compose-ui-tooling-preview"))
                "debugImplementation"(libs.library("androidx-compose-ui-tooling"))
            }
        }
    }
}
