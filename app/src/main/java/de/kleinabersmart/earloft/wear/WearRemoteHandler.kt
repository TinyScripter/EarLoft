package de.kleinabersmart.earloft.wear

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.repository.PlaylistRepository
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.playback.SleepTimer
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearRemoteAction
import de.kleinabersmart.earloft.core.wearsync.WearRemoteBookDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteCommand
import de.kleinabersmart.earloft.core.wearsync.WearRemoteEpisodesRequest
import de.kleinabersmart.earloft.core.wearsync.WearRemoteEpisodesResult
import de.kleinabersmart.earloft.core.wearsync.WearRemoteListKind
import de.kleinabersmart.earloft.core.wearsync.WearRemotePlayRequest
import de.kleinabersmart.earloft.core.wearsync.WearRemoteTrackDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteTracksRequest
import de.kleinabersmart.earloft.core.wearsync.WearRemoteTracksResult
import de.kleinabersmart.earloft.core.wearsync.sendPayload
import de.kleinabersmart.earloft.ui.spoken.BookLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A track list of the phone with its name (album, playlist, favorites, everything). */
internal class RemoteTrackList(val title: String, val songs: List<Song>)

/**
 * Executes what the watch tells the phone to do (Wear concept 15): control commands, "play this list" and showing the
 * tracks of a list. Everything goes through the [PlaybackConnection] or the [BookLauncher], i.e. like an
 * interaction in the app itself.
 */
@Singleton
class WearRemoteHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playback: PlaybackConnection,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val favorites: FavoritesRepository,
    private val messageClient: MessageClient,
    private val books: BookRepository,
    private val podcasts: PodcastRepository,
    private val continueListening: ContinueListening,
    private val bookLauncher: BookLauncher,
) {
    suspend fun command(command: WearRemoteCommand) {
        when (command.action) {
            WearRemoteAction.PLAY_PAUSE -> playback.togglePlayPause()
            WearRemoteAction.NEXT -> playback.next()
            WearRemoteAction.PREVIOUS -> playback.previous()
            WearRemoteAction.TOGGLE_SHUFFLE -> playback.toggleShuffle()
            WearRemoteAction.SEEK -> seek(command.value.coerceAtLeast(0))
            WearRemoteAction.SKIP_BACK -> playback.skipBack()
            WearRemoteAction.SKIP_FORWARD -> playback.skipForward()
            WearRemoteAction.VOLUME_STEPS -> changeVolume(command.value.toInt())
            WearRemoteAction.CYCLE_REPEAT -> playback.cycleRepeatMode()
            // The sleep timer belongs on the main thread; the watch's messages arrive on another one.
            WearRemoteAction.SLEEP_TIMER_START -> withContext(Dispatchers.Main) {
                playback.startSleepTimer(command.value.toInt().coerceIn(1, SleepTimer.MAX_MINUTES))
            }
            WearRemoteAction.SLEEP_TIMER_TO_END -> withContext(Dispatchers.Main) { playback.startSleepTimerToEnd() }
            WearRemoteAction.SLEEP_TIMER_CANCEL -> withContext(Dispatchers.Main) { playback.cancelSleepTimer() }
        }
    }

    /** For books the target is a position in the whole book; the timeline finds the matching file. */
    private suspend fun seek(positionMs: Long) {
        val bookId = playback.state.value.spoken?.bookId
        val target = bookId?.let { books.timeline(it) }?.locate(positionMs)
        if (target != null) playback.seekToTrack(target.trackIndex, target.offsetMs) else playback.seekTo(positionMs)
    }

    suspend fun play(request: WearRemotePlayRequest) {
        if (request.kind == WearRemoteListKind.CONTINUE) {
            request.refId?.let { bookLauncher.playConfirmed(it) }
            return
        }
        val list = resolve(request.kind, request.refId)
        if (list == null || list.songs.isEmpty()) {
            Log.w(TAG, "Play ${request.kind}/${request.refId}: no tracks")
            return
        }
        val byId = request.startSongId?.let { id -> list.songs.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        // If the list has shifted in the meantime, the track counts, not the position.
        val startIndex = request.startIndex.takeIf { it in list.songs.indices && (request.startSongId == null || list.songs[it].id == request.startSongId) }
            ?: byId
            ?: 0
        playback.play(list.songs, startIndex, request.shuffle)
    }

    /** Replies to the watch [nodeId] with the list's tracks (gzip, at most [WearRemoteTracksResult.MAX_TRACKS]). */
    suspend fun sendTracks(nodeId: String, request: WearRemoteTracksRequest) {
        val list = resolve(request.kind, request.refId)
        val result = if (list == null) {
            WearRemoteTracksResult(request.requestId, found = false)
        } else {
            WearRemoteTracksResult(
                requestId = request.requestId,
                title = list.title,
                tracks = list.songs.take(WearRemoteTracksResult.MAX_TRACKS).map { WearRemoteTrackDto(it.id, it.title, it.artist) },
                truncated = list.songs.size > WearRemoteTracksResult.MAX_TRACKS,
            )
        }
        messageClient.sendPayload(nodeId, WearPaths.REMOTE_TRACKS_RESULT, WearCodec.encodeCompressed(result))
    }

    /**
     * Replies to the watch [nodeId] with a subscription's episodes (Wear concept 17): started ones first, then the newest
     * [WearRemoteEpisodesResult.MAX_NEWEST].
     */
    suspend fun sendEpisodes(nodeId: String, request: WearRemoteEpisodesRequest) {
        val result = if (request.feedId == WearRemoteEpisodesRequest.QUEUE_FEED_ID) {
            WearRemoteEpisodesResult(request.requestId, episodes = remoteQueue(continueListening.episodes.first()))
        } else {
            val feed = podcasts.feed(request.feedId).first()
            if (feed == null) {
                WearRemoteEpisodesResult(request.requestId, found = false)
            } else {
                WearRemoteEpisodesResult(
                    requestId = request.requestId,
                    title = feed.title,
                    episodes = remoteEpisodes(podcasts.episodes(request.feedId).first(), WearRemoteEpisodesResult.MAX_NEWEST),
                )
            }
        }
        messageClient.sendPayload(nodeId, WearPaths.REMOTE_EPISODES_RESULT, WearCodec.encodeCompressed(result))
    }

    /** `null` if the album or playlist doesn't exist (any more). */
    internal suspend fun resolve(kind: WearRemoteListKind, refId: Long?): RemoteTrackList? = when (kind) {
        WearRemoteListKind.ALBUM -> refId?.let { library.album(it).first() }?.let { RemoteTrackList(it.album.title, it.songs) }
        WearRemoteListKind.PLAYLIST -> refId?.let { playlists.playlist(it).first() }
            ?.let { details -> RemoteTrackList(details.playlist.name, details.entries.map { it.song }) }
        WearRemoteListKind.FAVORITES -> RemoteTrackList("", favorites.favorites().first())
        WearRemoteListKind.ALL -> RemoteTrackList("", library.songs().first())
        // Books and episodes aren't songs; [play] plays them via the [BookLauncher].
        WearRemoteListKind.CONTINUE -> null
    }

    private fun changeVolume(steps: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    private companion object {
        const val TAG = "WearRemoteHandler"
    }
}

/** Started and not fully played. */
private val BookWithProgress.isStarted: Boolean get() = progress?.let { !it.finished && it.positionMs > 0 } == true

/** Started ones first (most recently played in front), then the rest in its order. */
private fun List<BookWithProgress>.startedFirst(): List<BookWithProgress> {
    val (started, rest) = partition { it.isStarted }
    return started.sortedByDescending { it.progress?.updatedAt ?: 0 } + rest
}

private fun BookWithProgress.toRemote() = WearRemoteBookDto(book.id, book.title, book.author, book.serverId, book.remoteId, isStarted)

/** Audiobooks for the watch's library index (Wear concept 17): started ones first, then in the phone's order. */
internal fun remoteBooks(books: List<BookWithProgress>): List<WearRemoteBookDto> = books.startedFirst().map { it.toRemote() }

/** The podcast queue ([episodes] already in its order) for the watch; at most [MAX_QUEUE] episodes. */
internal fun remoteQueue(episodes: List<BookWithProgress>): List<WearRemoteBookDto> =
    episodes.take(MAX_QUEUE).map { it.toRemote() }

private const val MAX_QUEUE = 50

/**
 * A subscription's episodes for the watch: all started ones (most recently played in front), then the newest [newest] of the rest.
 * [episodes] come newest first.
 */
internal fun remoteEpisodes(episodes: List<BookWithProgress>, newest: Int): List<WearRemoteBookDto> {
    val (started, rest) = episodes.partition { it.isStarted }
    return (started.startedFirst() + rest.take(newest)).map { it.toRemote() }
}
