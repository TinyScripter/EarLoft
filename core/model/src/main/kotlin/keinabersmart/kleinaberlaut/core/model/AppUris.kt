package keinabersmart.kleinaberlaut.core.model

/**
 * App-interne URIs. Sie enthalten nur lokale IDs – welche Quelle tatsächlich geladen wird,
 * entscheidet die App erst beim Zugriff (siehe Konzept 7.2).
 */
object AppUris {
    const val SCHEME = "kal"
    private const val SONG_PREFIX = "$SCHEME://song/"
    private const val ALBUM_COVER_PREFIX = "$SCHEME://cover/album/"

    fun song(songId: Long): String = "$SONG_PREFIX$songId"

    fun albumCover(albumId: Long): String = "$ALBUM_COVER_PREFIX$albumId"

    fun parseSongId(uri: String): Long? = uri.removePrefixOrNull(SONG_PREFIX)?.toLongOrNull()

    fun parseAlbumCoverId(uri: String): Long? = uri.removePrefixOrNull(ALBUM_COVER_PREFIX)?.toLongOrNull()

    private fun String.removePrefixOrNull(prefix: String): String? =
        if (startsWith(prefix)) removePrefix(prefix) else null
}
