import keinabersmart.kleinaberlaut.buildlogic.BuildConfig
import keinabersmart.kleinaberlaut.buildlogic.libs
import keinabersmart.kleinaberlaut.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Reine Kotlin-Module ohne Android-Abhängigkeit (schnelle JVM-Tests). */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = BuildConfig.JAVA_VERSION
                targetCompatibility = BuildConfig.JAVA_VERSION
            }
            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions.jvmTarget.set(JvmTarget.fromTarget(BuildConfig.JAVA_VERSION.toString()))
            }

            dependencies {
                "testImplementation"(libs.library("junit"))
            }
        }
    }
}
