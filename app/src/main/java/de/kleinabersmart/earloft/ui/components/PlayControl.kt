package de.kleinabersmart.earloft.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.ui.spoken.BookLauncher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Round play (with [playing] pause) button sitting at the bottom right of a cover; it is aligned via the [Modifier]. */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes playDescription: Int = R.string.action_play,
) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier.size(36.dp)) {
        if (playing) {
            Icon(painterResource(DesignR.drawable.ic_pause), contentDescription = stringResource(R.string.action_pause))
        } else {
            Icon(painterResource(DesignR.drawable.ic_play), contentDescription = stringResource(playDescription))
        }
    }
}

/**
 * The album the music queue was started with, or null if it mixes albums (playlist, mix, songs added in between) or a book is
 * loaded. A playlist that happens to stand on a song of an album doesn't count as "this album is loaded".
 *
 * The queue counts as the album if it begins with its songs, the current title is one of them, and none of the album comes
 * after them: songs appended at the end – the endless mix behind the last track, or "Add to queue" – keep it the album.
 */
internal fun PlayerState.queuedAlbumId(): Long? {
    if (spoken != null || queue.isEmpty()) return null
    val albumId = queue.first().albumId ?: return null
    val albumEnd = queue.indexOfFirst { it.albumId != albumId }.takeIf { it >= 0 } ?: queue.size
    if (currentIndex !in 0 until albumEnd) return null
    for (i in albumEnd until queue.size) if (queue[i].albumId == albumId) return null
    return albumId
}

/**
 * Play/pause for tiles in the overviews that have no ViewModel of their own for it (albums, search).
 * If the album or episode is playing, the button pauses; if it is loaded and paused, it resumes; otherwise it starts.
 * An album counts as loaded only if the queue is exactly this album ([queuedAlbumId]).
 */
@HiltViewModel
class PlayControlViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val playback: PlaybackConnection,
    private val launcher: BookLauncher,
) : ViewModel() {

    /** The album currently playing as the whole queue (not: paused). */
    val playingAlbumId: StateFlow<Long?> = playback.state
        .map { if (it.isPlaying) it.queuedAlbumId() else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The book or episode currently playing (not: paused). */
    val playingBookId: StateFlow<Long?> = playback.state
        .map { if (it.isPlaying) it.spoken?.bookId else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun playOrPauseAlbum(albumId: Long) {
        if (playback.state.value.queuedAlbumId() == albumId) {
            playback.togglePlayPause()
        } else {
            viewModelScope.launch { library.album(albumId).first()?.songs?.let { playback.play(it) } }
        }
    }

    /** All songs of the [albumIds], album by album in this order (e.g. a shelf of the library). */
    fun playAlbums(albumIds: List<Long>) {
        viewModelScope.launch {
            val songs = library.songsOfAlbums(albumIds)
            if (songs.isNotEmpty()) playback.play(songs)
        }
    }

    fun playOrPauseBook(bookId: Long) {
        if (playback.state.value.spoken?.bookId == bookId) playback.togglePlayPause() else launcher.play(bookId)
    }
}
