package keinabersmart.kleinaberlaut.core.playback

import android.content.Context
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
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future

/**
 * Browse-Tree und Auflösung von Media-Items für alle Controller (App, System, Uhr, später Auto).
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
                folderMediaItem(MediaIds.ALBUMS, context.getString(R.string.albums), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            )
            parentId == MediaIds.ALBUMS -> libraryRepository.albums().first().map { it.toBrowsableMediaItem() }
            MediaIds.albumId(parentId) != null ->
                libraryRepository.album(MediaIds.albumId(parentId)!!).first()?.songs.orEmpty().map { it.toMediaItem() }
            else -> return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }
        val paged = children.drop(page * pageSize).take(pageSize)
        LibraryResult.ofItemList(paged, params)
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
        val resolved = resolve(player, mediaItems)
        if (resolved.isEmpty() && mediaItems.isNotEmpty()) {
            // Nichts abspielbar: Warteschlange behalten statt sie zu leeren.
            throw UnsupportedOperationException("Keiner der Titel ist abspielbar")
        }
        val items = resolved.map { it.second }
        val newIndex = resolved.indexOfFirst { it.first >= startIndex }.takeIf { startIndex != C.INDEX_UNSET }
        val position = when {
            newIndex == null -> startPositionMs
            newIndex >= 0 && resolved[newIndex].first == startIndex -> startPositionMs
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
}
