package de.kleinabersmart.earloft.core.database.model

import androidx.room.Embedded
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.SourceType

/** Projection for song lists (song + album title). */
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

/** A genre (spellings merged regardless of case) with its visible albums and songs. */
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

/** Which album a song belongs to. */
data class SongAlbumRow(val songId: Long, val albumId: Long)

/** A song source together with properties of its source – basis for choosing the source during playback. */
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

/** What a book or episode needs for its image (without description and other long columns). */
data class BookCoverRow(
    val bookId: Long,
    val serverId: String,
    val kind: BookKind,
    val coverUrl: String?,
)

/** The whole author or narrator entry of a book; the caller splits the individual names (`parsePeople`). */
data class BookPeopleRow(
    val bookId: Long,
    val names: String,
)

/** ReplayGain values of a song source. */
data class ReplayGainRow(
    val rgTrackGainDb: Float?,
    val rgTrackPeak: Float?,
    val rgAlbumGainDb: Float?,
    val rgAlbumPeak: Float?,
)

/** Offline availability of a song: local source and, if any, download (`state == null`: none). */
data class SongOfflineRow(
    val songId: Long,
    val isLocal: Boolean,
    val state: DownloadState?,
    val bytesDone: Long?,
    val bytesTotal: Long?,
)

/** An album with at least one download. */
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

/** Download state of an album in numbers, for the badge on tiles; `localCount` = songs with a local source. */
data class AlbumDownloadSummaryRow(
    val albumId: Long,
    val songCount: Int,
    val localCount: Int,
    val doneCount: Int,
    val pendingCount: Int,
    val failedCount: Int,
)

/** Totals over all downloads. */
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

/** An episode's ID and length (`PodcastDao.episodeDurations`). */
data class EpisodeDuration(val bookId: Long, val durationMs: Long)

/** Details used to search for an album cover on the internet. */
data class AlbumLookupRow(val albumId: Long, val title: String, val artist: String, val mbid: String?)

// --- Watch (Wear concept 4.1/5) ---

/** A song with the album details the manifest for the watch needs. */
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

/** A song source; [downloadPath] is set when it is fully downloaded. */
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

/** A server source of a song with what the watch's planner needs for the storage estimate. */
data class WearCandidateRow(
    val uid: String,
    val songId: Long,
    val sourceType: SourceType,
    val sourcePriority: Int,
    val bitRate: Int?,
    val sizeBytes: Long?,
    val durationMs: Long,
)

/** Progress of a selection on the watch; each song counts once, even with duplicates in the playlist. */
data class WearSelectionProgressRow(
    val key: String,
    val total: Int,
    val done: Int,
    val failed: Int,
    /** Fully downloaded bytes of the selection. */
    val bytes: Long = 0,
    /** Downloaded share of the currently running songs (0–1 per song, summed up). */
    val runningFraction: Double = 0.0,
)

/**
 * What the mix needs to know about a song: first artist (song artist, otherwise album artist), genres, year (song, otherwise
 * album) and listening history.
 */
data class MixCandidateRow(
    val songId: Long,
    val albumId: Long,
    val artistId: Long?,
    /** All genres of the song, separated by [GENRE_SEPARATOR]; `null` without a genre. */
    val genres: String?,
    val year: Int?,
    val isFavorite: Boolean,
    val playCount: Int,
    val lastPlayedAt: Long?,
) {
    companion object {
        /** Separates the genres in [genres] (`char(31)` in SQL). */
        const val GENRE_SEPARATOR = '\u001F'
    }
}

/** A source of a song with its properties (track info). */
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

/** A counted play of a visible song (music statistics). */
data class MusicPlayRow(val songId: Long, val albumId: Long, val playedAt: Long, val listenedMs: Long)

/**
 * A row of the music statistics' top lists: song, album, artist or genre. [id] is the ID (`null` for
 * genres), [albumId] the album for the cover (`null` for artists and genres).
 */
data class TopPlayedRow(
    val id: Long?,
    val title: String,
    val subtitle: String?,
    val albumId: Long?,
    val plays: Int,
    val listenedMs: Long,
)
