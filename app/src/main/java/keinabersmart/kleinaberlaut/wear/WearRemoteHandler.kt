package keinabersmart.kleinaberlaut.wear

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteAction
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteBookDto
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteCommand
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteEpisodesRequest
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteEpisodesResult
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteListKind
import keinabersmart.kleinaberlaut.core.wearsync.WearRemotePlayRequest
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteTrackDto
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteTracksRequest
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteTracksResult
import keinabersmart.kleinaberlaut.core.wearsync.sendPayload
import keinabersmart.kleinaberlaut.ui.spoken.BookLauncher
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Eine Titelliste des Handys mit ihrem Namen (Album, Playlist, Favoriten, alles). */
internal class RemoteTrackList(val title: String, val songs: List<Song>)

/**
 * Führt aus, was die Uhr dem Handy aufträgt (Wear-Konzept 15): Bedienbefehle, „diese Liste abspielen“ und die
 * Titel einer Liste anzeigen. Alles läuft über die [PlaybackConnection] bzw. den [BookLauncher], also wie eine
 * Bedienung in der App selbst.
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
        }
    }

    /** Bei Büchern ist das Ziel eine Position im ganzen Buch; die Datei dazu sucht die Zeitachse. */
    private suspend fun seek(positionMs: Long) {
        val bookId = playback.state.value.spoken?.bookId
        val target = bookId?.let { books.timeline(it) }?.locate(positionMs)
        if (target != null) playback.seekToTrack(target.trackIndex, target.offsetMs) else playback.seekTo(positionMs)
    }

    suspend fun play(request: WearRemotePlayRequest) {
        if (request.kind == WearRemoteListKind.CONTINUE) {
            request.refId?.let(bookLauncher::play)
            return
        }
        val list = resolve(request.kind, request.refId)
        if (list == null || list.songs.isEmpty()) {
            Log.w(TAG, "Abspielen von ${request.kind}/${request.refId}: keine Titel")
            return
        }
        val byId = request.startSongId?.let { id -> list.songs.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        // Hat sich die Liste inzwischen verschoben, gilt der Titel, nicht die Position.
        val startIndex = request.startIndex.takeIf { it in list.songs.indices && (request.startSongId == null || list.songs[it].id == request.startSongId) }
            ?: byId
            ?: 0
        playback.play(list.songs, startIndex, request.shuffle)
    }

    /** Antwortet der Uhr [nodeId] mit den Titeln der Liste (gzip, höchstens [WearRemoteTracksResult.MAX_TRACKS]). */
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
     * Antwortet der Uhr [nodeId] mit den Folgen eines Abos (Wear-Konzept 17): angefangene zuerst, dann die neuesten
     * [WearRemoteEpisodesResult.MAX_NEWEST].
     */
    suspend fun sendEpisodes(nodeId: String, request: WearRemoteEpisodesRequest) {
        val feed = podcasts.feed(request.feedId).first()
        val result = if (feed == null) {
            WearRemoteEpisodesResult(request.requestId, found = false)
        } else {
            WearRemoteEpisodesResult(
                requestId = request.requestId,
                title = feed.title,
                episodes = remoteEpisodes(podcasts.episodes(request.feedId).first(), WearRemoteEpisodesResult.MAX_NEWEST),
            )
        }
        messageClient.sendPayload(nodeId, WearPaths.REMOTE_EPISODES_RESULT, WearCodec.encodeCompressed(result))
    }

    /** `null`, wenn Album bzw. Playlist nicht (mehr) existiert. */
    internal suspend fun resolve(kind: WearRemoteListKind, refId: Long?): RemoteTrackList? = when (kind) {
        WearRemoteListKind.ALBUM -> refId?.let { library.album(it).first() }?.let { RemoteTrackList(it.album.title, it.songs) }
        WearRemoteListKind.PLAYLIST -> refId?.let { playlists.playlist(it).first() }
            ?.let { details -> RemoteTrackList(details.playlist.name, details.entries.map { it.song }) }
        WearRemoteListKind.FAVORITES -> RemoteTrackList("", favorites.favorites().first())
        WearRemoteListKind.ALL -> RemoteTrackList("", library.songs().first())
        // Bücher und Folgen sind keine Songs; sie spielt [play] über den [BookLauncher].
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

/** Angefangen und nicht zu Ende gehört. */
private val BookWithProgress.isStarted: Boolean get() = progress?.let { !it.finished && it.positionMs > 0 } == true

/** Angefangene zuerst (zuletzt gehörte vorn), dann der Rest in seiner Reihenfolge. */
private fun List<BookWithProgress>.startedFirst(): List<BookWithProgress> {
    val (started, rest) = partition { it.isStarted }
    return started.sortedByDescending { it.progress?.updatedAt ?: 0 } + rest
}

private fun BookWithProgress.toRemote() = WearRemoteBookDto(book.id, book.title, book.author, book.serverId, book.remoteId, isStarted)

/** Hörbücher für den Bibliotheksindex der Uhr (Wear-Konzept 17): angefangene zuerst, dann in der Reihenfolge des Handys. */
internal fun remoteBooks(books: List<BookWithProgress>): List<WearRemoteBookDto> = books.startedFirst().map { it.toRemote() }

/**
 * Folgen eines Abos für die Uhr: alle angefangenen (zuletzt gehörte vorn), dann die neuesten [newest] der übrigen.
 * [episodes] kommen neueste zuerst.
 */
internal fun remoteEpisodes(episodes: List<BookWithProgress>, newest: Int): List<WearRemoteBookDto> {
    val (started, rest) = episodes.partition { it.isStarted }
    return (started.startedFirst() + rest.take(newest)).map { it.toRemote() }
}
