import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HasUnitTestBuilder
import de.kleinabersmart.kleinaberlaut.buildlogic.BuildConfig
import de.kleinabersmart.kleinaberlaut.buildlogic.libs
import de.kleinabersmart.kleinaberlaut.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import java.util.Properties

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            extensions.configure<ApplicationAndroidComponentsExtension> {
                beforeVariants { variantBuilder ->
                    (variantBuilder as? HasUnitTestBuilder)?.enableUnitTest = true
                }
            }


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
                testOptions.unitTests {
                    isIncludeAndroidResources = true
                    all { it.jvmArgs(BuildConfig.ROBOLECTRIC_JVM_ARGS) }
                }

                // keystore.properties im Projektroot (nicht eingecheckt): storeFile, storePassword, keyAlias, keyPassword.
                // Fehlt die Datei, bleibt release unsigniert, damit der Build trotzdem läuft.
                val keystoreFile = rootProject.file("keystore.properties")
                if (keystoreFile.exists()) {
                    val props = Properties().apply { keystoreFile.inputStream().use(::load) }
                    signingConfigs {
                        create("release") {
                            storeFile = rootProject.file(props.getProperty("storeFile"))
                            storePassword = props.getProperty("storePassword")
                            keyAlias = props.getProperty("keyAlias")
                            keyPassword = props.getProperty("keyPassword")
                        }
                    }
                    buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
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
