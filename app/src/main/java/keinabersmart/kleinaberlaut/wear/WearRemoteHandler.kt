package keinabersmart.kleinaberlaut.wear

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import keinabersmart.kleinaberlaut.core.data.podcast.EpisodeQueue
import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteAction
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteCommand
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
    private val continueListening: ContinueListening,
    private val episodeQueue: EpisodeQueue,
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
        if (request.kind == WearRemoteListKind.CONTINUE) {
            val entries = continueListening()
            val result = WearRemoteTracksResult(
                requestId = request.requestId,
                tracks = entries.map { WearRemoteTrackDto(it.book.id, it.book.title, it.book.author) },
            )
            messageClient.sendPayload(nodeId, WearPaths.REMOTE_TRACKS_RESULT, WearCodec.encodeCompressed(result))
            return
        }
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

    /** `null`, wenn Album bzw. Playlist nicht (mehr) existiert. */
    internal suspend fun resolve(kind: WearRemoteListKind, refId: Long?): RemoteTrackList? = when (kind) {
        WearRemoteListKind.ALBUM -> refId?.let { library.album(it).first() }?.let { RemoteTrackList(it.album.title, it.songs) }
        WearRemoteListKind.PLAYLIST -> refId?.let { playlists.playlist(it).first() }
            ?.let { details -> RemoteTrackList(details.playlist.name, details.entries.map { it.song }) }
        WearRemoteListKind.FAVORITES -> RemoteTrackList("", favorites.favorites().first())
        WearRemoteListKind.ALL -> RemoteTrackList("", library.songs().first())
        // Bücher und Folgen sind keine Songs; dafür gibt es eigene Wege in [play] und [sendTracks].
        WearRemoteListKind.CONTINUE -> null
    }

    /** „Weiterhören“ für die Uhr: angefangene Bücher und die Warteschlange der Podcasts in einer Liste. */
    private suspend fun continueListening(): List<BookWithProgress> {
        val order = episodeQueue.order.first()
        return mergeContinueListening(
            books = books.continueListening(limit = CONTINUE_LIMIT, startOrder = episodeQueue.order).first(),
            episodes = continueListening.episodes.first(),
            startOrder = order,
            limit = CONTINUE_LIMIT,
        )
    }

    private fun changeVolume(steps: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    private companion object {
        const val TAG = "WearRemoteHandler"

        /** Auf der Uhr genügen die vorderen Einträge; am Handy zeigt „Weiterhören“ auch nicht mehr. */
        const val CONTINUE_LIMIT = 15
    }
}

/**
 * Bücher und Folgen in einer Liste, zuletzt gestartete zuerst: Die Startreihenfolge ([startOrder], `EpisodeQueue.order`)
 * gilt für beide. Was dort nicht steht, folgt in seiner bisherigen Reihenfolge, Bücher vor Folgen.
 */
internal fun mergeContinueListening(
    books: List<BookWithProgress>,
    episodes: List<BookWithProgress>,
    startOrder: List<Long>,
    limit: Int,
): List<BookWithProgress> {
    val rank = startOrder.withIndex().associate { it.value to it.index }
    return (books + episodes)
        .distinctBy { it.book.id }
        .sortedBy { rank[it.book.id] ?: Int.MAX_VALUE }
        .take(limit)
}
