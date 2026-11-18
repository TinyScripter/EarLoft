package de.kleinabersmart.earloft.core.database.model

import androidx.room.Embedded
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.SourceType

/** Projektion für Song-Listen (Song + Albumtitel). */
data class SongRow(
    val songId: Long,
    val title: String,
    val artistDisplay: String,
    val albumId: Long,
    val albumTitle: String,
    val durationMs: Long,
    val discNumber: Int,
    val trackNumber: Int,
    val isFavorite: Boolean,
)

data class AlbumRow(
    val albumId: Long,
    val title: String,
    val albumArtistDisplay: String,
    val year: Int?,
    val songCount: Int,
)

data class ArtistRow(
    val artistId: Long,
    val name: String,
    val albumCount: Int,
)

/** Ein Genre (Schreibweisen ohne Unterschied von Groß/Klein zusammengefasst) mit seinen sichtbaren Alben und Songs. */
data class AlbumGenreRow(
    val albumId: Long,
    val genre: String,
)

data class DecadeRow(
    val startYear: Int,
    val albumCount: Int,
)

data class GenreRow(
    val name: String,
    val albumCount: Int,
    val songCount: Int,
)

/** Zu welchem Album ein Song gehört. */
data class SongAlbumRow(val songId: Long, val albumId: Long)

/** Eine Song-Quelle samt Eigenschaften ihrer Quelle – Grundlage der Quellenwahl beim Abspielen. */
data class PlayableSourceRow(
    val uid: String,
    val songId: Long,
    val sourceId: String,
    val remoteId: String,
    val coverRemoteId: String?,
    val sourceType: SourceType,
    val sourcePriority: Int,
    val mimeType: String?,
    val bitRate: Int?,
)

/** Was ein Buch bzw. eine Folge für ihr Bild braucht (ohne Beschreibung und andere lange Spalten). */
data class BookCoverRow(
    val bookId: Long,
    val serverId: String,
    val kind: BookKind,
    val coverUrl: String?,
)

/** Der ganze Autoren- bzw. Sprechereintrag eines Buchs; die einzelnen Namen trennt der Aufrufer (`parsePeople`). */
data class BookPeopleRow(
    val bookId: Long,
    val names: String,
)

/** ReplayGain-Werte einer Song-Quelle. */
data class ReplayGainRow(
    val rgTrackGainDb: Float?,
    val rgTrackPeak: Float?,
    val rgAlbumGainDb: Float?,
    val rgAlbumPeak: Float?,
)

/** Offline-Verfügbarkeit eines Songs: lokale Quelle und ggf. Download (`state == null`: keiner). */
data class SongOfflineRow(
    val songId: Long,
    val isLocal: Boolean,
    val state: DownloadState?,
    val bytesDone: Long?,
    val bytesTotal: Long?,
)

/** Ein Album mit mindestens einem Download. */
data class AlbumDownloadRow(
    val albumId: Long,
    val title: String,
    val albumArtistDisplay: String,
    val year: Int?,
    val songCount: Int,
    val doneCount: Int,
    val pendingCount: Int,
    val failedCount: Int,
    val doneBytes: Long,
)

/** Download-Stand eines Albums in Zahlen, für die Markierung auf Kacheln; `localCount` = Songs mit lokaler Quelle. */
data class AlbumDownloadSummaryRow(
    val albumId: Long,
    val songCount: Int,
    val localCount: Int,
    val doneCount: Int,
    val pendingCount: Int,
    val failedCount: Int,
)

/** Summen über alle Downloads. */
data class DownloadTotalsRow(
    val doneCount: Int,
    val pendingCount: Int,
    val failedCount: Int,
    val doneBytes: Long,
    val runningBytesDone: Long,
    val runningBytesTotal: Long,
)

data class PlaylistRow(
    val playlistId: Long,
    val name: String,
    val boundSourceId: String?,
    val serverName: String?,
    val songCount: Int,
    val durationMs: Long,
)

data class PlaylistEntryRow(
    val entryId: Long,
    @Embedded val song: SongRow,
    val missingOnServer: Boolean,
)

/** Angaben, mit denen ein Album-Cover im Internet gesucht wird. */
data class AlbumLookupRow(val albumId: Long, val title: String, val artist: String, val mbid: String?)

// --- Uhr (Wear-Konzept 4.1/5) ---

/** Ein Song mit den Album-Angaben, die das Manifest für die Uhr braucht. */
data class WearSongRow(
    val songId: Long,
    val title: String,
    val artistDisplay: String,
    val discNumber: Int,
    val trackNumber: Int,
    val durationMs: Long,
    val year: Int?,
    val genre: String?,
    val mbid: String?,
    val albumTitle: String,
    val albumArtistDisplay: String,
    val isCompilation: Boolean,
    val albumMbid: String?,
)

data class WearSongArtistRow(val songId: Long, val name: String)

/** Eine Song-Quelle; [downloadPath] ist gesetzt, wenn sie fertig heruntergeladen ist. */
data class WearSourceRow(
    val uid: String,
    val songId: Long,
    val sourceId: String,
    val remoteId: String,
    val coverRemoteId: String?,
    val sourceType: SourceType,
    val sourcePriority: Int,
    val mimeType: String?,
    val bitRate: Int?,
    val sizeBytes: Long?,
    val rgTrackGainDb: Float?,
    val rgTrackPeak: Float?,
    val rgAlbumGainDb: Float?,
    val rgAlbumPeak: Float?,
    val downloadPath: String?,
    val downloadBytes: Long?,
)

data class SourceSongIdRow(val uid: String, val songId: Long)

data class RemoteSongIdRow(val remoteId: String, val songId: Long)

/** Eine Server-Quelle eines Songs mit dem, was der Planer der Uhr für die Speicherschätzung braucht. */
data class WearCandidateRow(
    val uid: String,
    val songId: Long,
    val sourceType: SourceType,
    val sourcePriority: Int,
    val bitRate: Int?,
    val sizeBytes: Long?,
    val durationMs: Long,
)

/** Fortschritt einer Auswahl auf der Uhr; jeder Song zählt einmal, auch bei Duplikaten in der Playlist. */
data class WearSelectionProgressRow(
    val key: String,
    val total: Int,
    val done: Int,
    val failed: Int,
    /** Fertig geladene Bytes der Auswahl. */
    val bytes: Long = 0,
    /** Geladener Anteil der gerade laufenden Songs (0–1 je Song, aufsummiert). */
    val runningFraction: Double = 0.0,
)

/**
 * Was der Mix über einen Song wissen muss: erster Künstler (Song-Künstler, sonst Album-Künstler), Genres, Jahr (Song, sonst
 * Album) und Hörverlauf.
 */
data class MixCandidateRow(
    val songId: Long,
    val albumId: Long,
    val artistId: Long?,
    /** Alle Genres des Songs, getrennt durch [GENRE_SEPARATOR]; `null` ohne Genre. */
    val genres: String?,
    val year: Int?,
    val isFavorite: Boolean,
    val playCount: Int,
    val lastPlayedAt: Long?,
) {
    companion object {
        /** Trennt die Genres in [genres] (`char(31)` in SQL). */
        const val GENRE_SEPARATOR = '\u001F'
    }
}

/** Eine Quelle eines Songs mit ihren Eigenschaften (Titel-Info). */
data class SongSourceInfoRow(
    val sourceId: String,
    val sourceName: String,
    val sourceType: SourceType,
    val mimeType: String?,
    val bitRate: Int?,
    val sizeBytes: Long?,
    val trackGainDb: Float?,
    val albumGainDb: Float?,
    val isDownloaded: Boolean,
)

/** Eine gezählte Wiedergabe eines sichtbaren Songs (Musik-Statistik). */
data class MusicPlayRow(val songId: Long, val albumId: Long, val playedAt: Long, val listenedMs: Long)

/**
 * Eine Zeile der Bestenlisten der Musik-Statistik: Song, Album, Künstler oder Genre. [id] ist die Kennung (bei Genres
 * `null`), [albumId] das Album für das Cover (bei Künstlern und Genres `null`).
 */
data class TopPlayedRow(
    val id: Long?,
    val title: String,
    val subtitle: String?,
    val albumId: Long?,
    val plays: Int,
    val listenedMs: Long,
)
