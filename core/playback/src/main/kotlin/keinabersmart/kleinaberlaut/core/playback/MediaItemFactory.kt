package keinabersmart.kleinaberlaut.core.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import keinabersmart.kleinaberlaut.core.model.Album
import keinabersmart.kleinaberlaut.core.model.AppUris
import keinabersmart.kleinaberlaut.core.model.Song

/** Media-IDs im Browse-Tree der Session (Konzept 7.1). */
internal object MediaIds {
    const val ROOT = "root"
    const val ALBUMS = "albums"
    private const val SONG_PREFIX = "song:"
    private const val ALBUM_PREFIX = "album:"

    fun song(songId: Long) = "$SONG_PREFIX$songId"
    fun album(albumId: Long) = "$ALBUM_PREFIX$albumId"
    fun songId(mediaId: String): Long? = mediaId.removePrefix(SONG_PREFIX).takeIf { mediaId.startsWith(SONG_PREFIX) }?.toLongOrNull()
    fun albumId(mediaId: String): Long? = mediaId.removePrefix(ALBUM_PREFIX).takeIf { mediaId.startsWith(ALBUM_PREFIX) }?.toLongOrNull()
}

internal const val EXTRA_ALBUM_ID = "keinabersmart.kleinaberlaut.ALBUM_ID"

/** Die Song-ID eines von [toMediaItem] erzeugten Items. */
val MediaItem.songId: Long? get() = MediaIds.songId(mediaId)

/** Die Album-ID eines von [toMediaItem] erzeugten Items. */
val MediaMetadata.albumIdExtra: Long? get() = extras?.getLong(EXTRA_ALBUM_ID, -1L)?.takeIf { it >= 0 }

/**
 * Die URI zeigt nur auf `kal://song/<id>` – welche Datei bzw. welcher Stream gespielt wird,
 * entscheidet [SongDataSource] erst beim Laden.
 */
fun Song.toMediaItem(): MediaItem {
    val extras = Bundle().apply { putLong(EXTRA_ALBUM_ID, albumId) }
    return MediaItem.Builder()
        .setMediaId(MediaIds.song(id))
        .setUri(AppUris.song(id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(albumTitle)
                .setArtworkUri(Uri.parse(AppUris.albumCover(albumId)))
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

internal fun Album.toBrowsableMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(MediaIds.album(id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(Uri.parse(AppUris.albumCover(id)))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                .build(),
        )
        .build()

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
