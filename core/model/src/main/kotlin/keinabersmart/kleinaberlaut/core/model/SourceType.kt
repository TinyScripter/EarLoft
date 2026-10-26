package keinabersmart.kleinaberlaut.core.model

/** Herkunft einer physischen Song-Quelle. */
enum class SourceType {
    /** Musik auf dem Gerät (MediaStore). */
    LOCAL,

    /** Navidrome bzw. anderer (Open)Subsonic-Server. */
    SUBSONIC,
}
