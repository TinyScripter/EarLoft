package de.kleinabersmart.earloft.core.playback

import androidx.media3.common.MediaItem
import de.kleinabersmart.earloft.core.data.spoken.BookPlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hängt sich die App nach einem Neustart an eine laufende Cast-Sitzung, kennt Media3 nur noch die Einträge des Empfängers:
 * Bücher und Folgen kommen ohne Adresse auf dem Handy zurück, und beim Wechsel aufs Handy fielen sie weg („Nichts wird
 * gespielt“). Hier werden sie aus der Datenbank neu gebaut, mit derselben `mediaId` und Datei, samt geladener Datei,
 * Cover und Cast-Adressen.
 */
@Singleton
class SpokenItemRebuilder @Inject constructor(private val source: BookPlaybackSource) {

    /**
     * [items] mit neu gebauten Büchern und Folgen, wo die Adresse fehlt; alles andere bleibt. Synchron für den Wechsel
     * von Media3, liest nur die Datenbank. Was sich nicht bauen lässt (Buch gelöscht), bleibt, wie es war.
     */
    fun playable(items: List<MediaItem>): List<MediaItem> {
        val missing = items.filter { it.localConfiguration == null }.mapNotNullTo(LinkedHashSet()) { it.bookId }
        if (missing.isEmpty()) return items
        val rebuilt = runBlocking(Dispatchers.IO) {
            missing.associateWith { bookId -> source.rebuild(bookId)?.toMediaItems().orEmpty() }
        }
        return items.map { item ->
            if (item.localConfiguration != null) return@map item
            val bookId = item.bookId ?: return@map item
            item.bookTrackIndex?.let { rebuilt[bookId]?.getOrNull(it) } ?: item
        }
    }
}
