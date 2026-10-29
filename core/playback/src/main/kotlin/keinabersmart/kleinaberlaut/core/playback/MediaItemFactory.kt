package keinabersmart.kleinaberlaut.core.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import keinabersmart.kleinaberlaut.core.model.Album
import keinabersmart.kleinaberlaut.core.model.AppUris
import keinabersmart.kleinaberlaut.core.model.Artist
import keinabersmart.kleinaberlaut.core.model.Playlist
import keinabersmart.kleinaberlaut.core.model.Song

/**
 * Media-IDs im Browse-Tree der Session (Konzept 7.1).
 *
 * Songs im Browse-Tree tragen ihren Ordner mit (`album:3/song:42`): Android Auto schickt beim Antippen nur die
 * Media-ID, und aus dem Ordner wird dann die ganze Liste als Warteschlange geladen ([inContext], [contextOf]).
 * In der Warteschlange selbst heißen die Songs immer `song:<id>`.
 */
internal object MediaIds {
    const val ROOT = "root"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val FAVORITES = "favorites"
    const val RECENT = "recent"
    const val LIBRARY = "library"
    private const val SONG_PREFIX = "song:"
    private const val ALBUM_PREFIX = "album:"
    private const val ARTIST_PREFIX = "artist:"
    private const val PLAYLIST_PREFIX = "playlist:"
    private const val CONTEXT_SEPARATOR = '/'

    fun song(songId: Long) = "$SONG_PREFIX$songId"
    fun album(albumId: Long) = "$ALBUM_PREFIX$albumId"
    fun artist(artistId: Long) = "$ARTIST_PREFIX$artistId"
    fun playlist(playlistId: Long) = "$PLAYLIST_PREFIX$playlistId"

    /** Song [songId] als Eintrag des Ordners [contextId]. */
    fun inContext(contextId: String, songId: Long) = "$contextId$CONTEXT_SEPARATOR${song(songId)}"

    /** Der Ordner, in dem ein Song angeboten wurde; `null` bei einer einfachen Song-ID. */
    fun contextOf(mediaId: String): String? = mediaId.substringBeforeLast(CONTEXT_SEPARATOR, "").ifEmpty { null }

    fun songId(mediaId: String): Long? = idAfter(SONG_PREFIX, mediaId.substringAfterLast(CONTEXT_SEPARATOR))
    fun albumId(mediaId: String): Long? = idAfter(ALBUM_PREFIX, mediaId)
    fun artistId(mediaId: String): Long? = idAfter(ARTIST_PREFIX, mediaId)
    fun playlistId(mediaId: String): Long? = idAfter(PLAYLIST_PREFIX, mediaId)

    private fun idAfter(prefix: String, mediaId: String): Long? =
        mediaId.removePrefix(prefix).takeIf { mediaId.startsWith(prefix) }?.toLongOrNull()
}

internal const val EXTRA_ALBUM_ID = "keinabersmart.kleinaberlaut.ALBUM_ID"

/** Die Song-ID eines von [toMediaItem] erzeugten Items. */
val MediaItem.songId: Long? get() = MediaIds.songId(mediaId)

/** Die Album-ID eines von [toMediaItem] erzeugten Items. */
val MediaMetadata.albumIdExtra: Long? get() = extras?.getLong(EXTRA_ALBUM_ID, -1L)?.takeIf { it >= 0 }

/**
 * Die URI zeigt nur auf `kal://song/<id>` – welche Datei bzw. welcher Stream gespielt wird,
 * entscheidet [SongDataSource] erst beim Laden.
 *
 * Das Cover ist `kal://cover/…` für App, Benachrichtigung und Uhr; im Browse-Tree ([toBrowsableSongItem]) dagegen die
 * [CoverProvider]-URI, die auch Android Auto lesen kann.
 */
fun Song.toMediaItem(): MediaItem = toMediaItem(MediaIds.song(id), Uri.parse(AppUris.albumCover(albumId)))

internal fun Song.toBrowsableSongItem(context: Context, contextId: String): MediaItem =
    toMediaItem(MediaIds.inContext(contextId, id), CoverProvider.albumCoverUri(context, albumId))

private fun Song.toMediaItem(mediaId: String, artworkUri: Uri): MediaItem {
    val extras = Bundle().apply { putLong(EXTRA_ALBUM_ID, albumId) }
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(AppUris.song(id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(albumTitle)
                .setArtworkUri(artworkUri)
                .setTrackNumber(trackNumber)
                .setDiscNumber(discNumber)
                .setDurationMs(durationMs)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(extras)
                .build(),
        )
        .build()
}

internal fun Album.toBrowsableMediaItem(context: Context): MediaItem =
    MediaItem.Builder()
        .setMediaId(MediaIds.album(id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(CoverProvider.albumCoverUri(context, id))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                .build(),
        )
        .build()

internal fun Artist.toBrowsableMediaItem(): MediaItem =
    folderMediaItem(MediaIds.artist(id), name, MediaMetadata.MEDIA_TYPE_ARTIST)

internal fun Playlist.toBrowsableMediaItem(): MediaItem =
    folderMediaItem(MediaIds.playlist(id), name, MediaMetadata.MEDIA_TYPE_PLAYLIST)

internal fun folderMediaItem(mediaId: String, title: String, mediaType: Int): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build(),
        )
        .build()
