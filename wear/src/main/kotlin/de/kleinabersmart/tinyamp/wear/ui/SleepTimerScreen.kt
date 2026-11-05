package de.kleinabersmart.tinyamp.wear.ui

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
import de.kleinabersmart.tinyamp.core.playback.SleepTimer
import de.kleinabersmart.tinyamp.wear.R

/** Aufgerundet auf ganze Minuten: Bis zum Ablauf steht „1 min“ statt „0 min“. */
internal fun minutesLeft(remainingMs: Long): Int = ((remainingMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()

private const val MS_PER_MINUTE = 60_000L

/**
 * Sleep-Timer wählen: feste Dauern und „Eigene Dauer“. Beenden geschieht über den Knopf in der Wiedergabe.
 * [onDone] schließt den Bildschirm nach einer Auswahl.
 */
@Composable
fun SleepTimerScreen(onCustom: () -> Unit, onDone: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
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

/** Eigene Dauer: die Krone (oder +/−) ändert die Minuten in Fünferschritten bis 240, „Starten“ übernimmt sie. */
@Composable
fun SleepTimerCustomScreen(onDone: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    var minutes by remember { mutableIntStateOf(CUSTOM_DEFAULT_MINUTES) }
    val crown = remember { CrownStepper(CROWN_PX_PER_STEP) }
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    // Wie in der Wiedergabe-Ansicht: Ohne Fokus kommt die Krone nie an, und er geht beim Bildschirmwechsel gern verloren.
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

/** Wie bei der Lautstärke: knapp unter einer langsamen Rastung, damit jede Rastung genau einen Schritt (5 min) gibt. */
private const val CROWN_PX_PER_STEP = 30f
