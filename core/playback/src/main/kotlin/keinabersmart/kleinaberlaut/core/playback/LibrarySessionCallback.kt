package keinabersmart.kleinaberlaut.core.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
 * Controller schicken Items ohne abspielbare URI über Prozessgrenzen; [onAddMediaItems] baut sie
 * deshalb anhand der Media-ID neu aus der Datenbank.
 */
@OptIn(UnstableApi::class)
internal class LibrarySessionCallback(
    private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val queuePersistence: QueuePersistence,
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
        val songIds = mediaItems.mapNotNull { it.songId }
        libraryRepository.songs(songIds).map { it.toMediaItem() }
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
