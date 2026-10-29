package keinabersmart.kleinaberlaut.core.playback

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import keinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future

/** Benutzerdefinierte Aktion „Favorit umschalten“ (Herz in Android Auto, Benachrichtigung, Uhr). */
internal const val ACTION_TOGGLE_FAVORITE = "keinabersmart.kleinaberlaut.TOGGLE_FAVORITE"

/**
 * Browse-Tree und Auflösung von Media-Items für alle Controller (App, System, Uhr, Android Auto).
 *
 * Die Wurzel hat höchstens vier Einträge, weil Android Auto sie als Tabs zeigt: Zuletzt gehört, Bibliothek,
 * Playlists, Favoriten. Songs in Listen tragen ihren Ordner in der Media-ID ([MediaIds.inContext]); wird einer
 * davon allein gesetzt (Antippen in Auto), lädt [onSetMediaItems] die ganze Liste als Warteschlange.
 *
 * Controller schicken Items ohne abspielbare URI über Prozessgrenzen; [onAddMediaItems] und
 * [onSetMediaItems] bauen sie deshalb anhand der Media-ID neu aus der Datenbank. Songs, die der
 * aktuelle Player nicht spielen kann ([SessionPlayerFactory.playableSongIds], z. B. beim Casten),
 * fallen dabei weg.
 */
@OptIn(UnstableApi::class)
internal class LibrarySessionCallback(
    private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val favoritesRepository: FavoritesRepository,
    private val queuePersistence: QueuePersistence,
    private val playerFactory: SessionPlayerFactory?,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        LibraryResult.ofItem(
            folderMediaItem(MediaIds.ROOT, context.getString(R.string.app_library), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            params,
        )
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val children = when {
            parentId == MediaIds.ROOT -> listOf(
                folderMediaItem(MediaIds.RECENT, context.getString(R.string.recently_played), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folderMediaItem(MediaIds.LIBRARY, context.getString(R.string.library), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folderMediaItem(MediaIds.PLAYLISTS, context.getString(R.string.playlists), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folderMediaItem(MediaIds.FAVORITES, context.getString(R.string.favorites), MediaMetadata.MEDIA_TYPE_PLAYLIST),
            )
            parentId == MediaIds.RECENT ->
                libraryRepository.recentlyPlayedAlbums(RECENT_ALBUMS).first()
                    .ifEmpty { libraryRepository.recentlyAddedAlbums(RECENT_ALBUMS).first() }
                    .map { it.toBrowsableMediaItem(context) }
            parentId == MediaIds.LIBRARY -> listOf(
                folderMediaItem(MediaIds.ALBUMS, context.getString(R.string.albums), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folderMediaItem(MediaIds.ARTISTS, context.getString(R.string.artists), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
            )
            parentId == MediaIds.ALBUMS -> libraryRepository.albums().first().map { it.toBrowsableMediaItem(context) }
            parentId == MediaIds.ARTISTS -> libraryRepository.artists().first().map { it.toBrowsableMediaItem() }
            parentId == MediaIds.PLAYLISTS -> playlistRepository.playlists().first().map { it.toBrowsableMediaItem() }
            MediaIds.artistId(parentId) != null ->
                libraryRepository.artist(MediaIds.artistId(parentId)!!).first()?.albums.orEmpty().map { it.toBrowsableMediaItem(context) }
            else -> songsOf(parentId)?.map { it.toBrowsableSongItem(context, parentId) }
                ?: return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }
        val paged = children.drop(page * pageSize).take(pageSize)
        LibraryResult.ofItemList(paged, params)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        val results = libraryRepository.search(query)
        session.notifySearchResultChanged(browser, query, results.albums.size + results.artists.size + results.songs.size, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val results = libraryRepository.search(query)
        val items = results.albums.map { it.toBrowsableMediaItem(context) } +
            results.artists.map { it.toBrowsableMediaItem() } +
            results.songs.map { it.toBrowsableSongItem(context, MediaIds.search(query)) }
        LibraryResult.ofItemList(items.drop(page * pageSize).take(pageSize), params)
    }

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).setAvailableSessionCommands(commands).build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> = scope.future {
        if (customCommand.customAction != ACTION_TOGGLE_FAVORITE) return@future SessionResult(SessionError.ERROR_NOT_SUPPORTED)
        session.player.currentMediaItem?.songId?.let { favoritesRepository.toggleFavorite(it) }
        SessionResult(SessionResult.RESULT_SUCCESS)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val songId = MediaIds.songId(mediaId)
        val item = songId?.let { libraryRepository.songs(listOf(it)).firstOrNull()?.toMediaItem() }
        if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = scope.future {
        val player = mediaSession.player
        val items = resolve(player, mediaItems).map { it.second }
        val room = (maxQueueSize(player) - player.mediaItemCount).coerceAtLeast(0)
        if (items.size <= room) return@future items
        playerFactory?.onQueueLimited(room, items.size)
        items.take(room)
    }

    /**
     * Wie [onAddMediaItems]; fällt der Starttitel weg, beginnt die Wiedergabe beim nächsten verbliebenen.
     * Ist die Liste länger als der Player erlaubt, kommt ein Ausschnitt mit dem Starttitel ([queueWindow]).
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        val player = mediaSession.player
        val (requested, requestedStart) = expandContext(mediaItems, startIndex)
        val resolved = resolve(player, requested)
        if (resolved.isEmpty() && requested.isNotEmpty()) {
            // Nichts abspielbar: Warteschlange behalten statt sie zu leeren.
            throw UnsupportedOperationException("Keiner der Titel ist abspielbar")
        }
        val items = resolved.map { it.second }
        val newIndex = resolved.indexOfFirst { it.first >= requestedStart }.takeIf { requestedStart != C.INDEX_UNSET }
        val position = when {
            newIndex == null -> startPositionMs
            newIndex >= 0 && resolved[newIndex].first == requestedStart -> startPositionMs
            else -> 0
        }
        val maxSize = maxQueueSize(player)
        if (items.size <= maxSize) {
            return@future MediaItemsWithStartPosition(items, newIndex?.coerceAtLeast(0) ?: C.INDEX_UNSET, position)
        }
        val window = queueWindow(items, newIndex?.coerceAtLeast(0) ?: 0, maxSize, player.shuffleModeEnabled)
        playerFactory?.onQueueLimited(window.items.size, items.size)
        MediaItemsWithStartPosition(window.items, window.startIndex, position)
    }

    /** Die Songs eines Ordners (Album, Playlist, Favoriten); `null`, wenn [folderId] keine Songliste ist. */
    private suspend fun songsOf(folderId: String): List<Song>? = when {
        folderId == MediaIds.FAVORITES -> favoritesRepository.favorites().first()
        MediaIds.searchQuery(folderId) != null -> libraryRepository.search(MediaIds.searchQuery(folderId)!!).songs
        MediaIds.albumId(folderId) != null -> libraryRepository.album(MediaIds.albumId(folderId)!!).first()?.songs.orEmpty()
        MediaIds.playlistId(folderId) != null ->
            playlistRepository.playlist(MediaIds.playlistId(folderId)!!).first()?.entries.orEmpty().map { it.song }
        else -> null
    }

    /**
     * Ein einzelner Song aus einer Liste (Android Auto schickt nur die Media-ID) wird zur ganzen Liste mit diesem
     * Song als Start. Alles andere bleibt unverändert.
     */
    private suspend fun expandContext(mediaItems: List<MediaItem>, startIndex: Int): Pair<List<MediaItem>, Int> {
        val item = mediaItems.singleOrNull() ?: return mediaItems to startIndex
        val voiceQuery = item.requestMetadata.searchQuery
        if (item.mediaId.isEmpty() && voiceQuery != null) {
            return voiceSongs(voiceQuery).map { it.toMediaItem() } to 0
        }
        val folderId = MediaIds.contextOf(item.mediaId) ?: return mediaItems to startIndex
        val songs = songsOf(folderId) ?: return mediaItems to startIndex
        val index = songs.indexOfFirst { it.id == item.songId }
        if (index < 0) return mediaItems to startIndex
        return songs.map { it.toMediaItem() } to index
    }

    /**
     * Was auf „Spiele …“ hin läuft: ohne Suchbegriff gemischte Favoriten (sonst die Bibliothek); mit Begriff ein Song
     * mit genau diesem Titel, sonst der erste passende Künstler oder das erste passende Album, sonst die Treffer.
     */
    private suspend fun voiceSongs(query: String): List<Song> {
        if (query.isBlank()) {
            val favorites = favoritesRepository.favorites().first()
            return favorites.ifEmpty { libraryRepository.songs().first() }.shuffled().take(VOICE_QUEUE_SIZE)
        }
        val results = libraryRepository.search(query)
        val exact = results.songs.filter { it.title.equals(query, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact
        results.artists.firstOrNull()?.let { artist ->
            val albums = libraryRepository.artist(artist.id).first()?.albums.orEmpty()
            return albums.flatMap { libraryRepository.album(it.id).first()?.songs.orEmpty() }
        }
        results.albums.firstOrNull()?.let { album ->
            return libraryRepository.album(album.id).first()?.songs.orEmpty()
        }
        return results.songs
    }

    private fun maxQueueSize(player: Player): Int = playerFactory?.maxQueueSize(player) ?: Int.MAX_VALUE

    /** Neu aufgebaute Items samt ihrer Position in [mediaItems]; unbekannte und nicht abspielbare Songs fehlen. */
    private suspend fun resolve(player: Player, mediaItems: List<MediaItem>): List<Pair<Int, MediaItem>> {
        val songIds = mediaItems.mapNotNull { it.songId }.distinct()
        val songs = libraryRepository.songs(songIds).associateBy { it.id }
        val playable = playerFactory?.playableSongIds(player, songs.keys) ?: songs.keys
        return mediaItems.mapIndexedNotNull { index, item ->
            item.songId?.takeIf { it in playable }?.let(songs::get)?.let { index to it.toMediaItem() }
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        val restored = queuePersistence.restore() ?: throw UnsupportedOperationException("Keine gespeicherte Warteschlange")
        mediaSession.player.shuffleModeEnabled = restored.shuffle
        mediaSession.player.repeatMode = restored.repeatMode
        MediaItemsWithStartPosition(restored.items, restored.startIndex, restored.startPositionMs)
    }

    private companion object {
        const val RECENT_ALBUMS = 50
        const val VOICE_QUEUE_SIZE = 100
    }
}
