package de.kleinabersmart.kleinaberlaut.core.model

/** Was als Ganzes auf die Uhr geladen wird (Wear-Konzept 5). */
enum class WearSelectionKind {
    ALBUM,
    PLAYLIST,
    FAVORITES,
    ;

    /** Schlüssel der Auswahl, z. B. `album:12` oder `favorites`; [refId] ist die ID am Handy. */
    fun key(refId: Long?): String = when (this) {
        ALBUM -> "album:${requireNotNull(refId)}"
        PLAYLIST -> "playlist:${requireNotNull(refId)}"
        FAVORITES -> "favorites"
    }
}
