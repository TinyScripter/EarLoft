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
)

/** Ein Jahrzehnt der Album-Jahre, z. B. [startYear] 1980 für 1980–1989. */
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

/** Reihenfolge der Alben in der Bibliothek. */
enum class AlbumSort { TITLE, ARTIST, YEAR, RECENTLY_ADDED, RECENTLY_PLAYED }

/** Reihenfolge der Künstler in der Bibliothek. */
enum class ArtistSort { NAME, ALBUM_COUNT }

/** Reihenfolge der Songs in der Bibliothek. */
enum class SongSort { TITLE, ARTIST, ALBUM, RECENTLY_ADDED }

/** Eine Quelle eines Songs, für die Titel-Info; [bitRate] in bit/s, Gain in dB. */
data class SongSourceInfo(
    val sourceId: String,
    val sourceName: String,
    val sourceType: SourceType,
    val mimeType: String?,
    val bitRate: Int?,
    val sizeBytes: Long?,
    val trackGainDb: Float?,
    val albumGainDb: Float?,
    /** Von dieser Quelle liegt ein fertiger Download auf dem Gerät. */
    val isDownloaded: Boolean,
)
