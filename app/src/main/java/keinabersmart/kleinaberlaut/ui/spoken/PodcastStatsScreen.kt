package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.stats.StatsRepository
import keinabersmart.kleinaberlaut.core.model.MonthListening
import keinabersmart.kleinaberlaut.core.model.PodcastListening
import keinabersmart.kleinaberlaut.core.model.PodcastStats
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PodcastStatsViewModel @Inject constructor(repository: StatsRepository) : ViewModel() {
    /** `null`, solange die Zahlen noch geladen werden. */
    val stats: StateFlow<PodcastStats?> =
        repository.podcastStats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private const val HOUR_MS = 60 * 60 * 1000L
private const val CHART_HEIGHT_DP = 100

/** Reiter „Statistik“ der Podcast-Bibliothek: gehörte Zeit insgesamt, je Podcast und im Verlauf der letzten Monate. */
@Composable
fun PodcastStatsScreen(
    onFeedClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastStatsViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val current = stats ?: return
    if (current.totalMs == 0L && current.finishedEpisodes == 0) {
        Box(modifier.fillMaxSize()) { EmptyHint(stringResource(R.string.podcast_stats_empty), Modifier.padding(16.dp)) }
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        item(key = "summary") { Summary(current) }
        if (current.podcasts.isNotEmpty()) {
            item(key = "by-podcast") { SectionHeader(stringResource(R.string.podcast_stats_by_podcast)) }
            items(current.podcasts, key = { it.key }) { podcast ->
                PodcastRow(podcast, share = podcast.listenedMs.toFloat() / current.totalMs.coerceAtLeast(1), current.totalMs, onFeedClick)
            }
        }
        item(key = "history") { SectionHeader(stringResource(R.string.podcast_stats_history)) }
        item(key = "chart") {
            if (current.hasHistory) {
                MonthChart(current.months, Modifier.padding(horizontal = 16.dp))
            } else {
                EmptyHint(stringResource(R.string.podcast_stats_history_empty), Modifier.padding(horizontal = 16.dp))
            }
        }
        item(key = "end") { Box(Modifier.height(16.dp)) }
    }
}

@Composable
private fun Summary(stats: PodcastStats) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(formatListened(stats.totalMs), style = MaterialTheme.typography.displaySmall)
        val days = (stats.totalMs / (24 * HOUR_MS)).toInt()
        Text(
            if (days >= 2) pluralStringResource(R.plurals.podcast_stats_listened_days, days, days) else stringResource(R.string.podcast_stats_listened),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            pluralStringResource(R.plurals.podcast_stats_finished, stats.finishedEpisodes, stats.finishedEpisodes) + " · " +
                pluralStringResource(R.plurals.podcast_stats_subscriptions, stats.subscriptions, stats.subscriptions),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PodcastRow(podcast: PodcastListening, share: Float, totalMs: Long, onFeedClick: (String) -> Unit) {
    val percent = (podcast.listenedMs * 100 / totalMs.coerceAtLeast(1)).toInt()
    val feedId = podcast.feedId
    ListItem(
        headlineContent = { Text(podcast.title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val episodes = pluralStringResource(R.plurals.podcast_stats_episodes, podcast.episodes, podcast.episodes)
                val note = if (feedId == null) " · " + stringResource(R.string.podcast_stats_unsubscribed) else ""
                Text("$episodes · $percent %$note")
                LinearProgressIndicator(progress = { share.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        },
        trailingContent = { Text(formatListened(podcast.listenedMs), style = MaterialTheme.typography.titleSmall) },
        modifier = if (feedId != null) Modifier.clickable { onFeedClick(feedId) } else Modifier,
    )
}

/** Balken je Monat mit den Stunden darüber. */
@Composable
private fun MonthChart(months: List<MonthListening>, modifier: Modifier = Modifier) {
    val max = months.maxOf { it.listenedMs }.coerceAtLeast(1)
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.podcast_stats_hours_per_month),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            months.forEach { month ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(CHART_HEIGHT_DP.dp + 16.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            if (month.listenedMs > 0) {
                                Text(
                                    (month.listenedMs / HOUR_MS).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val bar = (CHART_HEIGHT_DP * month.listenedMs.toFloat() / max).coerceAtLeast(2f)
                                Box(
                                    Modifier
                                        .fillMaxWidth(0.6f)
                                        .height(bar.dp)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                                )
                            }
                        }
                    }
                    Text(
                        Month.of(month.month).getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** „913 h“ ab einer Stunde, sonst „45 min“. */
@Composable
private fun formatListened(ms: Long): String =
    if (ms >= HOUR_MS) stringResource(R.string.podcast_stats_hours, ms / HOUR_MS)
    else stringResource(R.string.podcast_stats_minutes, ms / 60_000)
