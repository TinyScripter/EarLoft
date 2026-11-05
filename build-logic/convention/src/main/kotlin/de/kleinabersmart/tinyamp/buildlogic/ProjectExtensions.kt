package de.kleinabersmart.tinyamp.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** Gemeinsame SDK- und Java-Level für alle Module. */
object BuildConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 26
    const val MIN_SDK_WEAR = 30
    val JAVA_VERSION = JavaVersion.VERSION_17

    /** Robolectric braucht auf JDK 25 Zugriff auf interne JDK-Klassen. */
    const val ROBOLECTRIC_JVM_ARGS = "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED"
}

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("Bibliothek '$alias' fehlt im Version Catalog") }
