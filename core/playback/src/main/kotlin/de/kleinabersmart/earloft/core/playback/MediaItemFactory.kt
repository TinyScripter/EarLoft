package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.AppUris
import de.kleinabersmart.earloft.core.model.Artist
import de.kleinabersmart.earloft.core.model.Playlist
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.model.SpokenMode

/**
 * Media-IDs im Browse-Tree der Session (Konzept 7.1).
 *
 * Songs im Browse-Tree tragen ihren Ordner mit (`album:3/song:42`): Android Auto schickt beim Antippen nur die
 * Media-ID, und aus dem Ordner wird dann die ganze Liste als Warteschlange geladen ([inContext], [contextOf]).
 * In der Warteschlange selbst heißen die Songs immer `song:<id>`. Bücher und Folgen heißen im Browse-Tree
 * `spoken:<id>` ([spoken]); erst die Session macht daraus die Dateien `book:<id>/<n>` ([BookIds]).
 */
internal object MediaIds {
    const val ROOT = "root"

    /** Tab „Zuletzt“, wenn mehr als ein Bereich eingeschaltet ist: das Neueste aus jedem Bereich. */
    const val HOME = "home"
    const val MUSIC = "area:music"
    const val PODCASTS = "area:podcasts"
    const val BOOKS = "area:books"

    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val FAVORITES = "favorites"
    const val RECENT = "recent"
    const val LIBRARY = "library"

    const val EPISODES_CONTINUE = "podcasts:continue"
    const val EPISODES_NEW = "podcasts:new"
    const val FEEDS = "podcasts:feeds"
    const val EPISODES_DOWNLOADED = "podcasts:downloaded"

    const val BOOKS_CONTINUE = "books:continue"
    const val BOOKS_RECENT = "books:recent"
    const val BOOKS_ALL = "books:all"
    const val AUTHORS = "books:authors"
    const val SERIES = "books:series"

    private const val SONG_PREFIX = "song:"
    private const val ALBUM_PREFIX = "album:"
    private const val ARTIST_PREFIX = "artist:"
    private const val PLAYLIST_PREFIX = "playlist:"
    private const val SEARCH_PREFIX = "search:"
    private const val FEED_PREFIX = "feed:"
    private const val AUTHOR_PREFIX = "author:"
    private const val SERIES_PREFIX = "series:"
    private const val SPOKEN_PREFIX = "spoken:"
    private const val PODCASTS_PREFIX = "podcasts:"
    private const val BOOKS_PREFIX = "books:"
    private const val CONTEXT_SEPARATOR = '/'

    private val MUSIC_FOLDERS = setOf(MUSIC, ALBUMS, ARTISTS, PLAYLISTS, FAVORITES, RECENT, LIBRARY)
    private val MUSIC_PREFIXES = listOf(SONG_PREFIX, ALBUM_PREFIX, ARTIST_PREFIX, PLAYLIST_PREFIX, SEARCH_PREFIX)

    fun song(songId: Long) = "$SONG_PREFIX$songId"
    fun album(albumId: Long) = "$ALBUM_PREFIX$albumId"
    fun artist(artistId: Long) = "$ARTIST_PREFIX$artistId"
    fun playlist(playlistId: Long) = "$PLAYLIST_PREFIX$playlistId"

    /** Ein Podcast-Abo; die Kennung des Abos kann selbst Doppelpunkte und Schrägstriche enthalten (Abos vom Gerät). */
    fun feed(feedId: String) = "$FEED_PREFIX$feedId"
    fun feedId(mediaId: String): String? = mediaId.removePrefix(FEED_PREFIX).takeIf { mediaId.startsWith(FEED_PREFIX) }

    fun author(name: String) = "$AUTHOR_PREFIX$name"
    fun authorName(mediaId: String): String? = mediaId.removePrefix(AUTHOR_PREFIX).takeIf { mediaId.startsWith(AUTHOR_PREFIX) }

    fun series(name: String) = "$SERIES_PREFIX$name"
    fun seriesName(mediaId: String): String? = mediaId.removePrefix(SERIES_PREFIX).takeIf { mediaId.startsWith(SERIES_PREFIX) }

    /** Ein Buch oder eine Folge zum Abspielen (Antippen in Android Auto). */
    fun spoken(bookId: Long) = "$SPOKEN_PREFIX$bookId"
    fun spokenId(mediaId: String): Long? = idAfter(SPOKEN_PREFIX, mediaId)

    /**
     * Der Bereich, zu dem ein Ordner oder Eintrag gehört; `null` für die Wurzel, „Zuletzt“ und Bücher/Folgen
     * (`spoken:`, ob Buch oder Folge, weiß erst die Datenbank).
     */
    fun areaOf(mediaId: String): SpokenMode? = when {
        mediaId in MUSIC_FOLDERS || MUSIC_PREFIXES.any { mediaId.startsWith(it) } -> SpokenMode.MUSIC
        mediaId == PODCASTS || mediaId.startsWith(PODCASTS_PREFIX) || mediaId.startsWith(FEED_PREFIX) -> SpokenMode.PODCASTS
        mediaId == BOOKS || mediaId.startsWith(BOOKS_PREFIX) || mediaId.startsWith(AUTHOR_PREFIX) ||
            mediaId.startsWith(SERIES_PREFIX) -> SpokenMode.BOOKS
        else -> null
    }

    /** Ordner der Suchtreffer zu [query]. */
    fun search(query: String) = "$SEARCH_PREFIX$query"
    fun searchQuery(mediaId: String): String? = mediaId.removePrefix(SEARCH_PREFIX).takeIf { mediaId.startsWith(SEARCH_PREFIX) }

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

internal const val EXTRA_ALBUM_ID = "de.kleinabersmart.earloft.ALBUM_ID"

/** Die Song-ID eines von [toMediaItem] erzeugten Items. */
val MediaItem.songId: Long? get() = MediaIds.songId(mediaId)

/** Die Album-ID eines von [toMediaItem] erzeugten Items. */
val MediaMetadata.albumIdExtra: Long? get() = extras?.getLong(EXTRA_ALBUM_ID, -1L)?.takeIf { it >= 0 }

/**
 * Die URI zeigt nur auf `earloft://song/<id>` – welche Datei bzw. welcher Stream gespielt wird,
 * entscheidet [SongDataSource] erst beim Laden.
 *
 * Das Cover ist die [CoverProvider]-URI ([CoverProvider.queueCoverUri]), die auch Android Auto und die System-Oberfläche lesen
 * können (Medienkarte); `earloft://cover/…` kann nur die App selbst laden, den Bitmap-Loader der Session verstehen beide.
 */
fun Song.toMediaItem(): MediaItem = toMediaItem(MediaIds.song(id), CoverProvider.queueCoverUri(albumId))

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
    folderMediaItem(MediaIds.artist(id), name, MediaMetadata.MEDIA_TYPE_ARTIST, gridChildren = true)

internal fun Playlist.toBrowsableMediaItem(): MediaItem =
    folderMediaItem(MediaIds.playlist(id), name, MediaMetadata.MEDIA_TYPE_PLAYLIST)

/**
 * Ein Ordner im Browse-Tree. Mit [gridChildren] zeigt Android Auto seinen Inhalt als Raster statt als Liste (Cover-Ordner
 * wie Alben, Abos, Bücher).
 */
internal fun folderMediaItem(
    mediaId: String,
    title: String,
    mediaType: Int,
    gridChildren: Boolean = false,
    subtitle: String? = null,
    artworkUri: Uri? = null,
): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setArtworkUri(artworkUri)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .apply { if (gridChildren) setExtras(gridChildrenExtras()) }
                .build(),
        )
        .build()
