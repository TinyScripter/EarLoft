package de.kleinabersmart.earloft.core.model

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val albumId: Long,
    val albumTitle: String,
    val durationMs: Long,
    val discNumber: Int,
    val trackNumber: Int,
    val isFavorite: Boolean,
)

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int?,
    val songCount: Int,
    /** Cover color (ARGB, `albums.seedColor`), `null` until computed. */
    val seedColor: Int? = null,
    /** When its newest song was added (epoch ms). */
    val addedAt: Long? = null,
    /** When it was last played (epoch ms); only filled in the order [AlbumSort.RECENTLY_PLAYED]. */
    val lastPlayedAt: Long? = null,
)

data class Artist(
    val id: Long,
    val name: String,
    val albumCount: Int,
)

data class Genre(
    val name: String,
    val albumCount: Int,
    val songCount: Int,
) {
    /** Spellings that differ only in upper/lower case are one genre. */
    val key: String get() = key(name)

    companion object {
        fun key(name: String): String = name.trim().lowercase()
    }
}

/** A decade of the album years, e.g. [startYear] 1980 for 1980–1989. */
data class Decade(
    val startYear: Int,
    val albumCount: Int,
) {
    val endYear: Int get() = startYear + 9
}

data class AlbumDetails(
    val album: Album,
    val songs: List<Song>,
)

data class ArtistDetails(
    val artist: Artist,
    val albums: List<Album>,
)

data class SearchResults(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val genres: List<Genre> = emptyList(),
) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && genres.isEmpty()

    companion object {
        val EMPTY = SearchResults(emptyList(), emptyList(), emptyList())
    }
}

/** Sort order of the albums in the library. */
enum class AlbumSort { TITLE, ARTIST, YEAR, RECENTLY_ADDED, RECENTLY_PLAYED, COLOR }

/** How the library shows albums: tiles, rows, shelves per sort group, or one album at a time (record crate, carousel). */
enum class AlbumView { GRID, LIST, SHELVES, CRATE, CAROUSEL }

/** Tile size of the album grid: a cover wall without text, the usual tiles, or one large tile per row on a phone. */
enum class AlbumGridSize { SMALL, MEDIUM, LARGE }

/** Sort order of the artists in the library. */
enum class ArtistSort { NAME, ALBUM_COUNT }

/** Sort order of the songs in the library. */
enum class SongSort { TITLE, ARTIST, ALBUM, RECENTLY_ADDED }

/** One source of a song, for the track info; [bitRate] in bit/s, gain in dB. */
data class SongSourceInfo(
    val sourceId: String,
    val sourceName: String,
    val sourceType: SourceType,
    val mimeType: String?,
    val bitRate: Int?,
    val sizeBytes: Long?,
    val trackGainDb: Float?,
    val albumGainDb: Float?,
    /** A finished download from this source is on the device. */
    val isDownloaded: Boolean,
)
