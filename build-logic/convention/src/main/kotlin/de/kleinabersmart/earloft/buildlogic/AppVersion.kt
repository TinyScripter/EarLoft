package de.kleinabersmart.earloft.buildlogic

import org.gradle.api.Project

/** First digit of the versionCode, so phone and watch APKs in the same Play listing have different codes. */
enum class Platform(val prefix: Int) {
    MOBILE(1),
    WEAR_OS(2),
}

/**
 * Shared version of phone and watch, maintained in `gradle.properties` (`VERSION_MAJOR`, `VERSION_MINOR`,
 * `VERSION_PATCH`, `VERSION_TYPE`, `VERSION_BUILD_NUM`).
 *
 * versionCode = `P MM mm pp BB` (platform, major, minor, patch, build), e.g. 1.5.0-beta02 on the phone = 101050002.
 * BB: beta01–89 = 01–89, rc01–09 = 90–98, release = 99. This way the code rises from beta through rc to the release.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val type: Type,
    val buildNum: Int,
) {
    enum class Type(val maxBuildNum: Int) {
        BETA(89),
        RC(9),
        RELEASE(0),
    }

    init {
        listOf("VERSION_MAJOR" to major, "VERSION_MINOR" to minor, "VERSION_PATCH" to patch).forEach { (key, value) ->
            require(value in 0..99) { "$key must be between 0 and 99, is $value" }
        }
        if (type != Type.RELEASE) {
            require(buildNum in 1..type.maxBuildNum) {
                "VERSION_BUILD_NUM must be between 1 and ${type.maxBuildNum} for ${type.name.lowercase()}, is $buildNum"
            }
        }
    }

    val name: String
        get() {
            val base = "$major.$minor.$patch"
            return if (type == Type.RELEASE) base else "$base-${type.name.lowercase()}${buildNum.toString().padStart(2, '0')}"
        }

    private val buildCode: Int
        get() = when (type) {
            Type.BETA -> buildNum
            Type.RC -> 89 + buildNum
            Type.RELEASE -> 99
        }

    fun versionCode(platform: Platform): Int =
        platform.prefix * 100_000_000 + major * 1_000_000 + minor * 10_000 + patch * 100 + buildCode

    companion object {
        fun parse(property: (String) -> String?): AppVersion {
            fun int(key: String): Int {
                val raw = property(key)?.trim() ?: error("$key is missing in gradle.properties")
                return raw.toIntOrNull() ?: error("$key is not a number: '$raw'")
            }
            val typeRaw = property("VERSION_TYPE")?.trim().orEmpty()
            val type = when (typeRaw.lowercase()) {
                "beta" -> Type.BETA
                "rc" -> Type.RC
                "", "release" -> Type.RELEASE
                else -> error("VERSION_TYPE '$typeRaw' unknown, allowed: beta, rc, release")
            }
            return AppVersion(
                major = int("VERSION_MAJOR"),
                minor = int("VERSION_MINOR"),
                patch = int("VERSION_PATCH"),
                type = type,
                buildNum = if (type == Type.RELEASE) 0 else int("VERSION_BUILD_NUM"),
            )
        }
    }
}

val Project.appVersion: AppVersion
    get() = AppVersion.parse { key -> providers.gradleProperty(key).orNull }
