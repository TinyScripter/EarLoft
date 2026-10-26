package keinabersmart.kleinaberlaut.core.model

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
) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()

    companion object {
        val EMPTY = SearchResults(emptyList(), emptyList(), emptyList())
    }
}
