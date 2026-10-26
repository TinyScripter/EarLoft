import com.android.build.api.dsl.ApplicationExtension
import keinabersmart.kleinaberlaut.buildlogic.BuildConfig
import keinabersmart.kleinaberlaut.buildlogic.libs
import keinabersmart.kleinaberlaut.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            extensions.configure<ApplicationExtension> {
                compileSdk = BuildConfig.COMPILE_SDK
                defaultConfig {
                    minSdk = BuildConfig.MIN_SDK
                    targetSdk = BuildConfig.TARGET_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = BuildConfig.JAVA_VERSION
                    targetCompatibility = BuildConfig.JAVA_VERSION
                }
            }

            dependencies {
                "testImplementation"(libs.library("junit"))
            }
        }
    }
}
