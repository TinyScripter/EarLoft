package de.kleinabersmart.earloft.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.playback.SleepTimer

/** Where the sleep timer stops "until the end": at the end of the chapter (books with chapters), at the end of the episode or book. */
enum class SleepTimerEnd { CHAPTER, EPISODE, BOOK }

fun sleepTimerEnd(episode: Boolean, hasChapters: Boolean): SleepTimerEnd = when {
    episode -> SleepTimerEnd.EPISODE
    hasChapters -> SleepTimerEnd.CHAPTER
    else -> SleepTimerEnd.BOOK
}

/**
 * Sleep timer selection: for books with chapters and for episodes, first "End of chapter" or "End of episode"
 * ([toEnd]), then fixed durations or a custom duration via slider. Stopping happens via the icon itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    onStart: (minutes: Int) -> Unit,
    onDismiss: () -> Unit,
    toEnd: SleepTimerEnd? = null,
    onStartToEnd: () -> Unit = {},
) {
    // Fully expanded and scrollable right away: half expanded, "Custom duration" and "Start" were below the edge on small phones.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.sleep_timer_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            // A book without chapters only ends after hours; this choice isn't offered there.
            val endLabel = when (toEnd) {
                SleepTimerEnd.CHAPTER -> R.string.sleep_timer_chapter_end
                SleepTimerEnd.EPISODE -> R.string.sleep_timer_episode_end
                SleepTimerEnd.BOOK, null -> null
            }
            if (endLabel != null) {
                ListItem(
                    headlineContent = { Text(stringResource(endLabel)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        onStartToEnd()
                        onDismiss()
                    },
                )
            }
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
                // Snap to whole steps: the slider returns intermediate values (39.99 instead of 40), which truncated would give 39.
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
