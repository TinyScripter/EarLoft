package keinabersmart.kleinaberlaut.core.database.model

import androidx.room.Embedded
import keinabersmart.kleinaberlaut.core.model.DownloadState
import keinabersmart.kleinaberlaut.core.model.SourceType

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
)
