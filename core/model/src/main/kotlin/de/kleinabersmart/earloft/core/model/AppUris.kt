package de.kleinabersmart.earloft.core.model

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * App-interne URIs. Sie enthalten nur lokale IDs – welche Quelle tatsächlich geladen wird,
 * entscheidet die App erst beim Zugriff (siehe Konzept 7.2).
 */
object AppUris {
    const val SCHEME = "earloft"
    private const val SONG_PREFIX = "$SCHEME://song/"
    private const val ALBUM_COVER_PREFIX = "$SCHEME://cover/album/"
    private const val ARTIST_IMAGE_PREFIX = "$SCHEME://cover/artist/"
    private const val BOOK_COVER_PREFIX = "$SCHEME://cover/book/"
    private const val AUTHOR_IMAGE_PREFIX = "$SCHEME://cover/author/"

    fun song(songId: Long): String = "$SONG_PREFIX$songId"

    fun albumCover(albumId: Long): String = "$ALBUM_COVER_PREFIX$albumId"

    fun artistImage(artistId: Long): String = "$ARTIST_IMAGE_PREFIX$artistId"

    fun bookCover(bookId: Long): String = "$BOOK_COVER_PREFIX$bookId"

    /** Autoren haben keine ID, nur den Namen (prozentkodiert). */
    fun authorImage(name: String): String = "$AUTHOR_IMAGE_PREFIX${URLEncoder.encode(name, "UTF-8")}"

    fun parseAuthorImageName(uri: String): String? = uri.removePrefixOrNull(AUTHOR_IMAGE_PREFIX)?.let { URLDecoder.decode(it, "UTF-8") }

    fun parseBookCoverId(uri: String): Long? = uri.removePrefixOrNull(BOOK_COVER_PREFIX)?.toLongOrNull()

    fun parseSongId(uri: String): Long? = uri.removePrefixOrNull(SONG_PREFIX)?.toLongOrNull()

    fun parseAlbumCoverId(uri: String): Long? = uri.removePrefixOrNull(ALBUM_COVER_PREFIX)?.toLongOrNull()

    fun parseArtistImageId(uri: String): Long? = uri.removePrefixOrNull(ARTIST_IMAGE_PREFIX)?.toLongOrNull()

    private fun String.removePrefixOrNull(prefix: String): String? =
        if (startsWith(prefix)) removePrefix(prefix) else null
}
