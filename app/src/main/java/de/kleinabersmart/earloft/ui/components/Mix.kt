package de.kleinabersmart.earloft.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.mix.MixRepository
import de.kleinabersmart.earloft.core.data.mix.MixSeed
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.snackbar.Snackbars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import de.kleinabersmart.earloft.core.designsystem.R as DesignR
import de.kleinabersmart.earloft.core.playback.R as PlaybackR

/**
 * Start a mix (concept 8 "Mix"): [start] replaces the queue with a mix from [MixSeed]; with `afterCurrent` the
 * current title keeps playing and the mix follows it (now playing).
 */
@Stable
class MixController(val start: (seed: MixSeed, afterCurrent: Boolean) -> Unit)

/** `null` (preview, tests): no mix. */
val LocalMixController = staticCompositionLocalOf<MixController?> { null }

/**
 * Keeps the mix alive beyond closing the menu; a new mix cancels one still in progress. If building it takes
 * longer than [BUILDING_HINT_DELAY_MS] (a server may take up to 4 s to respond), a hint shows that something is happening; an
 * error is reported instead of crashing the app.
 */
@HiltViewModel
class MixViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mixRepository: MixRepository,
    private val playbackConnection: PlaybackConnection,
    private val snackbars: Snackbars,
) : ViewModel() {
    private var running: Job? = null

    fun start(seed: MixSeed, afterCurrent: Boolean) {
        running?.cancel()
        running = viewModelScope.launch {
            val hint = launch {
                delay(BUILDING_HINT_DELAY_MS)
                snackbars.show(context.getString(R.string.mix_building))
            }
            try {
                val songs = mixRepository.mix(seed)
                hint.cancel()
                // After the current title: it is itself the starting point and comes first in the mix.
                val upcoming = if (afterCurrent) songs.drop(1) else songs
                when {
                    upcoming.isEmpty() -> snackbars.show(context.getString(PlaybackR.string.mix_empty))
                    afterCurrent -> {
                        playbackConnection.replaceUpcoming(upcoming)
                        snackbars.show(context.resources.getQuantityString(R.plurals.mix_queued, upcoming.size, upcoming.size))
                    }
                    else -> playbackConnection.play(upcoming)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Mix from $seed failed", e)
                snackbars.show(context.getString(PlaybackR.string.mix_failed))
            } finally {
                hint.cancel()
            }
        }
    }

    private companion object {
        const val BUILDING_HINT_DELAY_MS = 500L
        const val TAG = "MixViewModel"
    }
}

@Composable
fun rememberMixController(viewModel: MixViewModel = hiltViewModel()): MixController =
    remember(viewModel) { MixController(viewModel::start) }

/** "Start mix" button for the TopAppBar; invisible without [LocalMixController]. */
@Composable
fun MixButton(seed: MixSeed) {
    val controller = LocalMixController.current ?: return
    IconButton(onClick = { controller.start(seed, false) }) {
        Icon(painterResource(DesignR.drawable.ic_auto_awesome), contentDescription = stringResource(PlaybackR.string.action_start_mix))
    }
}

/** "Start mix" entry for the context menus; invisible without [LocalMixController]. */
@Composable
fun MixMenuItem(seed: MixSeed, onDismiss: () -> Unit, afterCurrent: Boolean = false) {
    val controller = LocalMixController.current ?: return
    DropdownMenuItem(
        text = { Text(stringResource(PlaybackR.string.action_start_mix)) },
        leadingIcon = { Icon(painterResource(DesignR.drawable.ic_auto_awesome), contentDescription = null) },
        onClick = {
            controller.start(seed, afterCurrent)
            onDismiss()
        },
    )
}
