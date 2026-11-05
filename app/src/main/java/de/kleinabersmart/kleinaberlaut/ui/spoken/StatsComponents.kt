package de.kleinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.kleinabersmart.kleinaberlaut.R

// Bausteine der Statistik-Seiten (Podcasts, Bücher).

const val HOUR_MS = 60 * 60 * 1000L
private const val CHART_HEIGHT_DP = 100

/** Ein Balken: [label] darunter, [valueMs] bestimmt die Höhe und die Stunden darüber. */
data class StatBar(val label: String, val valueMs: Long)

/** Balkendiagramm; die Stunden stehen über den Balken, Balken ohne Wert bleiben leer. */
@Composable
fun BarChart(bars: List<StatBar>, caption: String, modifier: Modifier = Modifier) {
    val max = bars.maxOfOrNull { it.valueMs }?.coerceAtLeast(1) ?: 1
    Column(modifier.fillMaxWidth()) {
        Text(
            caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { bar ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(CHART_HEIGHT_DP.dp + 16.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            if (bar.valueMs > 0) {
                                Text(
                                    if (bar.valueMs < HOUR_MS) "<1" else (bar.valueMs / HOUR_MS).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val height = (CHART_HEIGHT_DP * bar.valueMs.toFloat() / max).coerceAtLeast(2f)
                                Box(
                                    Modifier
                                        .fillMaxWidth(0.6f)
                                        .height(height.dp)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                                )
                            }
                        }
                    }
                    Text(bar.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

/** „913 h“ ab einer Stunde, „45 min“ ab einer Minute, sonst „20 s“. */
@Composable
fun formatListened(ms: Long): String =
    when {
        ms >= HOUR_MS -> stringResource(R.string.podcast_stats_hours, ms / HOUR_MS)
        ms >= 60_000 -> stringResource(R.string.podcast_stats_minutes, ms / 60_000)
        else -> stringResource(R.string.podcast_stats_seconds, ms / 1000)
    }
