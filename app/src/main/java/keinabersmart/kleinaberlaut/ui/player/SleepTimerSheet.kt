package keinabersmart.kleinaberlaut.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.playback.SleepTimer

/** Auswahl des Einschlaf-Timers: feste Dauern oder eigene Dauer per Schieberegler. Beenden geschieht über das Symbol selbst. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    onStart: (minutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.sleep_timer_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            SleepTimer.PRESET_MINUTES.forEach { minutes ->
                ListItem(
                    headlineContent = { Text(pluralStringResource(R.plurals.sleep_timer_minutes, minutes, minutes)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        onStart(minutes)
                        onDismiss()
                    },
                )
            }
            CustomDuration(
                onStart = {
                    onStart(it)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun CustomDuration(onStart: (minutes: Int) -> Unit) {
    var minutes by remember { mutableFloatStateOf(CUSTOM_DEFAULT_MINUTES.toFloat()) }
    val step = SleepTimer.STEP_MINUTES
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.sleep_timer_custom), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = minutes,
                // Auf ganze Schritte einrasten: Der Regler liefert Zwischenwerte (39,99 statt 40), die abgeschnitten 39 ergäben.
                onValueChange = { minutes = (it / step).roundToInt() * step.toFloat() },
                valueRange = step.toFloat()..SleepTimer.MAX_MINUTES.toFloat(),
                steps = SleepTimer.MAX_MINUTES / step - 2,
                modifier = Modifier.weight(1f),
            )
            Text(pluralStringResource(R.plurals.sleep_timer_minutes, minutes.roundToInt(), minutes.roundToInt()))
        }
        Button(onClick = { onStart(minutes.roundToInt()) }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.sleep_timer_start))
        }
    }
}

private const val CUSTOM_DEFAULT_MINUTES = 30
