package keinabersmart.kleinaberlaut.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
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
import androidx.wear.compose.material3.Stepper
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.compose.ui.unit.dp
import keinabersmart.kleinaberlaut.core.playback.SleepTimer
import keinabersmart.kleinaberlaut.wear.R

/** Aufgerundet auf ganze Minuten: Bis zum Ablauf steht „1 min“ statt „0 min“. */
internal fun minutesLeft(remainingMs: Long): Int = ((remainingMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()

private const val MS_PER_MINUTE = 60_000L

/**
 * Sleep-Timer wählen: feste Dauern, „Eigene Dauer“ und – bei laufendem Timer – „Timer ausschalten“.
 * [onDone] schließt den Bildschirm nach einer Auswahl.
 */
@Composable
fun SleepTimerScreen(onCustom: () -> Unit, onDone: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val remainingMs by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
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
            remainingMs?.let { remaining ->
                line(transformationSpec) {
                    stringResource(R.string.sleep_timer_remaining, pluralStringResource(R.plurals.sleep_timer_minutes, minutesLeft(remaining), minutesLeft(remaining)))
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
            if (remainingMs != null) {
                item {
                    Button(
                        onClick = {
                            viewModel.cancelSleepTimer()
                            onDone()
                        },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text(stringResource(R.string.sleep_timer_off)) },
                    )
                }
            }
        }
    }
}

/** Eigene Dauer: die Krone (oder +/−) ändert die Minuten in Fünferschritten, „Starten“ übernimmt sie. */
@Composable
fun SleepTimerCustomScreen(onDone: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    var minutes by remember { mutableIntStateOf(CUSTOM_DEFAULT_MINUTES) }
    ScreenScaffold {
        Stepper(
            value = minutes,
            onValueChange = { minutes = it },
            valueProgression = CUSTOM_STEP_MINUTES..SleepTimer.MAX_MINUTES step CUSTOM_STEP_MINUTES,
            increaseIcon = { Text("+") },
            decreaseIcon = { Text("−") },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pluralStringResource(R.plurals.sleep_timer_minutes, minutes, minutes), style = MaterialTheme.typography.titleMedium)
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
private const val CUSTOM_STEP_MINUTES = 5
