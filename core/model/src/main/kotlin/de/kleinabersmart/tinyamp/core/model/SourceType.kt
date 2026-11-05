package de.kleinabersmart.tinyamp.core.model

/** Herkunft einer physischen Song-Quelle. */
enum class SourceType {
    /** Musik auf dem Gerät (MediaStore). */
    LOCAL,

    /** Navidrome bzw. anderer (Open)Subsonic-Server. */
    SUBSONIC,

    /**
     * Nur auf der Uhr: Der Song liegt allein auf dem gekoppelten Handy und kommt per Data Layer
     * (Wear-Konzept 7.1). Es gibt keine Datenquelle dafür, abspielbar ist nur der fertige Download.
     */
    PHONE,
}
