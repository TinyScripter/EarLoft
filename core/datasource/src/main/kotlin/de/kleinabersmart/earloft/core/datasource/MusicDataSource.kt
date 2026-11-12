package de.kleinabersmart.earloft.core.datasource

import android.graphics.Bitmap
import android.net.Uri
import de.kleinabersmart.earloft.core.model.Lyrics
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.flow.Flow

/** Eine Musikquelle (lokal oder Server). Konzept Abschnitt 6.1. */
interface MusicDataSource {
    val sourceId: String
    val type: SourceType

    /** Anzeigename für die Quellenliste. */
    val displayName: String

    /**
     * Liefert die komplette Bibliothek in Batches. Wirft bei Fehlern (z. B. fehlende Berechtigung),
     * damit der Abgleich abbricht, statt die Bibliothek als leer zu interpretieren.
     */
    fun fullScan(): Flow<SourceBatch>

    /**
     * Wie [fullScan]; [isUnchanged] sagt, ob die Quelle einen Track mit genau diesem `lastModified` schon kennt.
     * Quellen, die für neue Tracks aufwendig nachlesen müssen (lokale Tags), sparen sich das für bekannte.
     */
    fun fullScan(isUnchanged: suspend (remoteId: String, lastModified: Long) -> Boolean): Flow<SourceBatch> = fullScan()

    /** Wird zur Ladezeit vom Player aufgerufen. */
    suspend fun resolvePlayback(remoteId: String): PlaybackLocation

    suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap?
}

/**
 * Nutzerdaten, die eine Server-Quelle hält (Favoriten, Scrobbles, Playlists). Fehler werden als
 * Ausnahmen gemeldet: `IOException` = später erneut versuchen.
 */
interface RemoteLibraryActions {
    suspend fun setFavorite(remoteId: String, favorite: Boolean)

    /** [playedAt] = Beginn des Hörens in ms seit Epoche. */
    suspend fun scrobble(remoteId: String, playedAt: Long)

    /** Eigene, änderbare Playlists des Kontos, ohne Songs. */
    suspend fun playlists(): List<RemotePlaylistInfo>

    suspend fun playlist(remoteId: String): RemotePlaylist

    /** @return der Stand danach. */
    suspend fun createPlaylist(name: String, songRemoteIds: List<String>): RemotePlaylistInfo

    /** Ersetzt Name und Songs von [current]. @return der Stand danach. */
    suspend fun replacePlaylist(current: RemotePlaylist, name: String, songRemoteIds: List<String>): RemotePlaylistInfo

    suspend fun deletePlaylist(remoteId: String)
}

/**
 * Meldet eine gerade gezählte Wiedergabe wie ein Player des Servers, damit Statistik und Plugins des Servers (Last.fm,
 * ListenBrainz) sie als Wiedergabe sehen. Nachgereichtes (offline Gehörtes, Wiedergaben der Uhr) geht weiter über
 * [RemoteLibraryActions.scrobble] mit dem richtigen Zeitpunkt. Fehler: `IOException`.
 */
interface RemotePlaybackReporting {
    /** [durationMs] = Länge des Songs; gemeldet wird er als zu Ende gehört. */
    suspend fun reportPlayback(remoteId: String, durationMs: Long)
}

/**
 * Billiger Zwischenabgleich für Server (statt des Vollabgleichs): erfasst neu hinzugekommene Alben und den
 * Favoriten-Stand, den andere Clients geändert haben. Gelöschte oder geänderte Songs findet nur der Vollabgleich.
 */
interface RemoteQuickSync {
    /** Songs der Alben, die nach [since] (ms seit Epoche) auf den Server kamen. */
    suspend fun tracksAddedSince(since: Long): List<RawTrack>

    /** Alle favorisierten Songs: `remoteId` → Zeitpunkt des Sterns (ms; 0 = unbekannt). */
    suspend fun starredSongs(): Map<String, Long>
}

/** Quellen, die Songtexte liefern. Fehler wie bei [RemoteLibraryActions]; `IOException` = später erneut versuchen. */
interface RemoteLyrics {
    /** [artist] und [title] dienen Servern ohne OpenSubsonic als Suchbegriff. [Lyrics.None], wenn es keinen Text gibt. */
    suspend fun lyrics(remoteId: String, artist: String, title: String): Lyrics
}

/** Server, die zu einem Song passende Songs vorschlagen (Mix). Fehler: Ausnahmen; leer, wenn der Server nichts weiß. */
interface RemoteSimilarSongs {
    /** `remoteId`s von bis zu [count] Songs, die zum Song [remoteId] passen, in der Reihenfolge des Servers. */
    suspend fun similarSongs(remoteId: String, count: Int): List<String>
}

/** Quellen, deren Dateien Songtexte einbetten. Fehler beim Lesen gelten als „kein Text“. */
interface LocalLyrics {
    suspend fun embeddedLyrics(remoteId: String): Lyrics
}

/**
 * Quellen, deren Songs ein anderes Gerät im Netz selbst abrufen kann (Chromecast). Die URLs enthalten
 * Zugangsdaten und dürfen weder gespeichert noch geloggt werden.
 */
interface ExternalPlayback {
    /** Original-Datei bzw. mit [transcodeTo] (Format, Bitrate in kbit/s) vom Server umgewandelt. */
    fun externalStreamUrl(remoteId: String, transcodeTo: Pair<String, Int>? = null): String

    fun externalCoverUrl(coverRemoteId: String, sizePx: Int): String
}

/**
 * Server, die eine Datei beim Abruf umwandeln (Downloads für die Uhr, Wear-Konzept 8.2). Die Auth übernimmt
 * wie bei [MusicDataSource.resolvePlayback] der gemeinsame OkHttpClient; die URL enthält keine Zugangsdaten.
 */
interface RemoteTranscoding {
    /** [codec] z. B. `opus`, [maxKbps] höchste Bitrate in kbit/s. */
    fun transcodedPlayback(remoteId: String, codec: String, maxKbps: Int): PlaybackLocation.Remote
}

/** [changedAt] = letzte Änderung auf dem Server (ms) – dient der Konflikterkennung. */
data class RemotePlaylistInfo(
    val remoteId: String,
    val name: String,
    val changedAt: Long,
)

/** [songRemoteIds] in Reihenfolge, Duplikate möglich. */
data class RemotePlaylist(
    val info: RemotePlaylistInfo,
    val songRemoteIds: List<String>,
)

data class SourceBatch(val tracks: List<RawTrack>)

sealed interface PlaybackLocation {
    data class LocalFile(val uri: Uri) : PlaybackLocation
    data class Remote(val uri: Uri, val cacheKey: String) : PlaybackLocation
}

data class RawArtist(
    val name: String,
    val mbid: String? = null,
)

/** Track, wie ihn eine Quelle liefert – noch nicht dedupliziert. */
data class RawTrack(
    val remoteId: String,
    val title: String,
    val artistDisplay: String,
    val artists: List<RawArtist>,
    val albumTitle: String,
    val albumArtistDisplay: String,
    val albumArtists: List<RawArtist>,
    val isCompilation: Boolean = false,
    val discNumber: Int = 1,
    val trackNumber: Int = 0,
    val durationMs: Long,
    val year: Int? = null,
    val genre: String? = null,
    val mbidTrack: String? = null,
    val mbidAlbum: String? = null,
    val rgTrackGainDb: Float? = null,
    val rgTrackPeak: Float? = null,
    val rgAlbumGainDb: Float? = null,
    val rgAlbumPeak: Float? = null,
    val coverRemoteId: String? = null,
    val mimeType: String? = null,
    val bitRate: Int? = null,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
    val dateAdded: Long? = null,
    /** Vom Server favorisiert seit (ms); lokale Quellen kennen keine Favoriten. */
    val starredAt: Long? = null,
)
