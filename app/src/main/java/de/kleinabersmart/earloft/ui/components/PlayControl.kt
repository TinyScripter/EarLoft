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
 * Play/pause for tiles in the overviews that have no ViewModel of their own for it (albums, search).
 * If the album or episode is playing, the button pauses; if it is loaded and paused, it resumes; otherwise it starts.
 */
@HiltViewModel
class PlayControlViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val playback: PlaybackConnection,
    private val launcher: BookLauncher,
) : ViewModel() {

    /** The album a title is currently playing from (not: paused). */
    val playingAlbumId: StateFlow<Long?> = playback.state
        .map { if (it.isPlaying) it.current?.albumId else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The book or episode currently playing (not: paused). */
    val playingBookId: StateFlow<Long?> = playback.state
        .map { if (it.isPlaying) it.spoken?.bookId else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun playOrPauseAlbum(albumId: Long) {
        if (playback.state.value.current?.albumId == albumId) {
            playback.togglePlayPause()
        } else {
            viewModelScope.launch { library.album(albumId).first()?.songs?.let { playback.play(it) } }
        }
    }

    fun playOrPauseBook(bookId: Long) {
        if (playback.state.value.spoken?.bookId == bookId) playback.togglePlayPause() else launcher.play(bookId)
    }
}
