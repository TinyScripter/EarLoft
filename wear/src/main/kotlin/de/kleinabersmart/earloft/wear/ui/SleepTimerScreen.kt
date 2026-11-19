package de.kleinabersmart.earloft.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.core.playback.SleepTimer
import de.kleinabersmart.earloft.wear.R

/** Rounded up to whole minutes: until it expires, "1 min" is shown instead of "0 min". */
internal fun minutesLeft(remainingMs: Long): Int = ((remainingMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()

internal const val MS_PER_MINUTE = 60_000L

/** Choose the phone's sleep timer (remote control, Wear concept 15). */
@Composable
fun RemoteSleepTimerScreen(onCustom: () -> Unit, onDone: () -> Unit, viewModel: RemotePlayerViewModel = hiltViewModel()) =
    SleepTimerScreen(onCustom, onDone, viewModel)

/** Custom duration for the phone's sleep timer. */
@Composable
fun RemoteSleepTimerCustomScreen(onDone: () -> Unit, viewModel: RemotePlayerViewModel = hiltViewModel()) =
    SleepTimerCustomScreen(onDone, viewModel)

/**
 * Choose the sleep timer: for episodes and books with chapters first "End of episode" or "End of chapter", then fixed
 * durations and "Custom duration". Ending it is done via the button in the now playing view.
 * [onDone] closes the screen after a selection. [viewModel] is the watch's timer or, in remote control, the phone's.
 */
@Composable
fun SleepTimerScreen(onCustom: () -> Unit, onDone: () -> Unit, viewModel: SleepTimerModel = hiltViewModel<PlayerViewModel>()) {
    val toEndLabel by viewModel.sleepToEndLabel.collectAsStateWithLifecycle()
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = contentPadding,
        ) {
            line(transformationSpec, header = true) { stringResource(R.string.sleep_timer_title) }
            toEndLabel?.let { label ->
                item {
                    Button(
                        onClick = {
                            viewModel.startSleepTimerToEnd()
                            onDone()
                        },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            SleepTimer.PRESET_MINUTES.forEach { minutes ->
                item {
                    Button(
                        onClick = {
                            viewModel.startSleepTimer(minutes)
                            onDone()
                        },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { Text(pluralStringResource(R.plurals.sleep_timer_minutes, minutes, minutes)) },
                    )
                }
            }
            item {
                Button(
                    onClick = onCustom,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(stringResource(R.string.sleep_timer_custom)) },
                )
            }
        }
    }
}

/** Custom duration: the crown (or +/−) changes the minutes in steps of five up to 240, "Start" applies them. */
@Composable
fun SleepTimerCustomScreen(onDone: () -> Unit, viewModel: SleepTimerModel = hiltViewModel<PlayerViewModel>()) {
    var minutes by remember { mutableIntStateOf(CUSTOM_DEFAULT_MINUTES) }
    val crown = remember { CrownStepper(CROWN_PX_PER_STEP) }
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    // As in the now playing view: without focus the crown never gets through, and focus tends to get lost on a screen change.
    LaunchedEffect(hasFocus) {
        if (!hasFocus) {
            delay(FOCUS_RETRY_MS)
            focusRequester.requestFocus()
        }
    }
    fun change(steps: Int) {
        minutes = (minutes + steps * SleepTimer.STEP_MINUTES).coerceIn(SleepTimer.STEP_MINUTES, SleepTimer.MAX_MINUTES)
    }
    ScreenScaffold {
        Box(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent {
                    val steps = crown.add(it.verticalScrollPixels)
                    if (steps != 0) change(steps)
                    true
                }
                .focusRequester(focusRequester)
                .onFocusChanged { hasFocus = it.isFocused }
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { change(-1) }, colors = IconButtonDefaults.filledTonalIconButtonColors()) { Text("−") }
                    Text(
                        pluralStringResource(R.plurals.sleep_timer_minutes, minutes, minutes),
                        modifier = Modifier.width(MINUTES_WIDTH),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = { change(1) }, colors = IconButtonDefaults.filledTonalIconButtonColors()) { Text("+") }
                }
                Button(
                    onClick = {
                        viewModel.startSleepTimer(minutes)
                        onDone()
                    },
                    label = { Text(stringResource(R.string.sleep_timer_start)) },
                )
            }
        }
    }
}

private const val CUSTOM_DEFAULT_MINUTES = 20
private const val FOCUS_RETRY_MS = 150L
private val MINUTES_WIDTH = 96.dp

/** As with the volume: just below a slow detent so that every detent gives exactly one step (5 min). */
private const val CROWN_PX_PER_STEP = 30f
