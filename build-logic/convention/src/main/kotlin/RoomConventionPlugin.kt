import androidx.room.gradle.RoomExtension
import de.kleinabersmart.earloft.buildlogic.libs
import de.kleinabersmart.earloft.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Room mit KSP; exportierte Schemas liegen versioniert unter `<modul>/schemas`. */
class RoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("androidx.room")

            extensions.configure<RoomExtension> {
                schemaDirectory("$projectDir/schemas")
            }

            dependencies {
                "implementation"(libs.library("androidx-room-runtime"))
                "implementation"(libs.library("androidx-room-ktx"))
                "ksp"(libs.library("androidx-room-compiler"))
                "testImplementation"(libs.library("androidx-room-testing"))
            }
        }
    }
}
