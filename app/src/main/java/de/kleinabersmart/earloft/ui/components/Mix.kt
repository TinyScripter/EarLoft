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

/**
 * Mix starten (Konzept 8 „Mix“): [start] ersetzt die Warteschlange durch einen Mix ab [MixSeed]; mit `afterCurrent` spielt
 * der laufende Titel weiter und der Mix folgt ihm (Now Playing).
 */
@Stable
class MixController(val start: (seed: MixSeed, afterCurrent: Boolean) -> Unit)

/** `null` (Vorschau, Tests): kein Mix. */
val LocalMixController = staticCompositionLocalOf<MixController?> { null }

/**
 * Hält den Mix über das Schließen des Menüs hinaus; ein neuer Mix bricht einen noch laufenden ab. Dauert die Zusammenstellung
 * länger als [BUILDING_HINT_DELAY_MS] (ein Server darf bis zu 4 s antworten), zeigt ein Hinweis, dass etwas passiert; ein
 * Fehler wird gemeldet statt die App abzuschießen.
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
                // Nach dem laufenden Titel: Er selbst ist der Ausgangspunkt und steht im Mix vorn.
                val upcoming = if (afterCurrent) songs.drop(1) else songs
                when {
                    upcoming.isEmpty() -> snackbars.show(context.getString(R.string.mix_empty))
                    afterCurrent -> {
                        playbackConnection.replaceUpcoming(upcoming)
                        snackbars.show(context.resources.getQuantityString(R.plurals.mix_queued, upcoming.size, upcoming.size))
                    }
                    else -> playbackConnection.play(upcoming)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Mix von $seed fehlgeschlagen", e)
                snackbars.show(context.getString(R.string.mix_failed))
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

/** Knopf „Mix starten“ für die TopAppBar; ohne [LocalMixController] unsichtbar. */
@Composable
fun MixButton(seed: MixSeed) {
    val controller = LocalMixController.current ?: return
    IconButton(onClick = { controller.start(seed, false) }) {
        Icon(painterResource(DesignR.drawable.ic_auto_awesome), contentDescription = stringResource(R.string.action_start_mix))
    }
}

/** Eintrag „Mix starten“ für die Kontextmenüs; ohne [LocalMixController] unsichtbar. */
@Composable
fun MixMenuItem(seed: MixSeed, onDismiss: () -> Unit, afterCurrent: Boolean = false) {
    val controller = LocalMixController.current ?: return
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_start_mix)) },
        leadingIcon = { Icon(painterResource(DesignR.drawable.ic_auto_awesome), contentDescription = null) },
        onClick = {
            controller.start(seed, afterCurrent)
            onDismiss()
        },
    )
}
