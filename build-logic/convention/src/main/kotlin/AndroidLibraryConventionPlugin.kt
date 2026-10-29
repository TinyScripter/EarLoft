import com.android.build.api.dsl.LibraryExtension
import keinabersmart.kleinaberlaut.buildlogic.BuildConfig
import keinabersmart.kleinaberlaut.buildlogic.libs
import keinabersmart.kleinaberlaut.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                compileSdk = BuildConfig.COMPILE_SDK
                defaultConfig {
                    minSdk = BuildConfig.MIN_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = BuildConfig.JAVA_VERSION
                    targetCompatibility = BuildConfig.JAVA_VERSION
                }
                testOptions.unitTests {
                    isIncludeAndroidResources = true
                    all { it.jvmArgs(BuildConfig.ROBOLECTRIC_JVM_ARGS) }
                }
            }

            tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
                compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(BuildConfig.JAVA_VERSION.toString()))
            }

            dependencies {
                "testImplementation"(libs.library("junit"))
            }
        }
    }
}
