package de.kleinabersmart.earloft.core.playback

import de.kleinabersmart.earloft.core.data.repository.ResolvedPlayback
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Woher ein Song gespielt wird: Datei auf dem Gerät, Download (Kopie einer Server-Datei) oder Stream von [sourceId]. */
data class PlayedFrom(val kind: Kind, val sourceId: String?) {
    enum class Kind { DEVICE, DOWNLOAD, STREAM }
}

/**
 * Welche Quelle [SongDataSource] je Song zuletzt geöffnet hat (Konzept 7.2: entschieden wird erst beim Laden). Nur für die
 * Anzeige (Titel-Info); App und Wiedergabe laufen im selben Prozess. Nicht gespeichert: Nach einem Neustart weiß es erst der
 * nächste Ladevorgang.
 */
@Singleton
class PlayingSources @Inject constructor() {
    private val opened = MutableStateFlow<Map<Long, PlayedFrom>>(emptyMap())

    val bySong: StateFlow<Map<Long, PlayedFrom>> = opened

    internal fun record(songId: Long, playback: ResolvedPlayback) {
        val from = when {
            playback.location is PlaybackLocation.Remote -> PlayedFrom(PlayedFrom.Kind.STREAM, playback.sourceId)
            // Downloads haben keine Quelle (sie liegen auf dem Gerät, unabhängig vom Server), lokale Dateien schon.
            playback.sourceId == null -> PlayedFrom(PlayedFrom.Kind.DOWNLOAD, null)
            else -> PlayedFrom(PlayedFrom.Kind.DEVICE, playback.sourceId)
        }
        opened.update { current ->
            if (current[songId] == from) current else (current - songId).plus(songId to from).toList().takeLast(MAX_SONGS).toMap()
        }
    }

    private companion object {
        /** Mehr als die Titel um den laufenden herum braucht niemand. */
        const val MAX_SONGS = 50
    }
}
