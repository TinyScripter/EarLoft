package de.kleinabersmart.earloft.core.model

/** What is loaded onto the watch as a whole (Wear concept 5). */
enum class WearSelectionKind {
    ALBUM,
    PLAYLIST,
    FAVORITES,
    ;

    /** Key of the selection, e.g. `album:12` or `favorites`; [refId] is the ID on the phone. */
    fun key(refId: Long?): String = when (this) {
        ALBUM -> "album:${requireNotNull(refId)}"
        PLAYLIST -> "playlist:${requireNotNull(refId)}"
        FAVORITES -> "favorites"
    }
}
