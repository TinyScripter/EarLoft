package de.kleinabersmart.tinyamp.core.model

/** Herkunft einer physischen Song-Quelle. */
enum class SourceType(
    /** Ein eingerichteter Musikserver (Favoriten, Scrobbles, Streams, Cast) – kein Gerät. */
    val isServer: Boolean,
) {
    /** Musik auf dem Gerät (MediaStore). */
    LOCAL(isServer = false),

    /** Navidrome bzw. anderer (Open)Subsonic-Server. */
    SUBSONIC(isServer = true),

    /** Jellyfin-Server (Konzept 14). */
    JELLYFIN(isServer = true),

    /**
     * Nur auf der Uhr: Der Song liegt allein auf dem gekoppelten Handy und kommt per Data Layer
     * (Wear-Konzept 7.1). Es gibt keine Datenquelle dafür, abspielbar ist nur der fertige Download.
     */
    PHONE(isServer = false),
}
