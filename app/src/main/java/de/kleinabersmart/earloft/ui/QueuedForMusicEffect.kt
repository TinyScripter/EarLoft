package de.kleinabersmart.earloft.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class QueuedForMusicViewModel @Inject constructor(private val playbackConnection: PlaybackConnection) : ViewModel() {
    val queued: Flow<Int> = playbackConnection.queuedForMusic

    fun resumeMusic() = playbackConnection.resumeMusic()
}

/**
 * "Play next"/"Add to queue" for songs while a book or episode was playing: the songs go into the saved
 * music queue. The hint says so and offers to play the music right away (replacing the spoken content).
 */
@Composable
fun QueuedForMusicEffect(viewModel: QueuedForMusicViewModel = hiltViewModel()) {
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.queued.collect { count ->
            snackbars.show(
                text = resources.getQuantityString(R.plurals.queued_for_music, count, count),
                duration = SnackbarDuration.Long,
                actionLabel = resources.getString(R.string.queued_for_music_play),
                onAction = viewModel::resumeMusic,
            )
        }
    }
}
