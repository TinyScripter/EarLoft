package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.model.PodcastListening
import keinabersmart.kleinaberlaut.core.model.PodcastStats
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.GroupMargin
import keinabersmart.kleinaberlaut.ui.components.ListGroup
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.components.groupedItems
import java.time.Month
import java.time.format.TextStyle

/** Zusammenfassung oder Diagramm der Statistik als eigene Fläche (wie eine Listengruppe). */
@Composable
internal fun StatCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ListGroup(modifier.padding(vertical = 4.dp)) { content() }
}

/** Auswahl „Gesamt“ und einzelne Jahre (beide Statistik-Reiter). */
@Composable
internal fun YearChips(years: List<Int>, selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    if (years.isEmpty()) return
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = GroupMargin + 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.stats_year_all)) })
        years.forEach { year ->
            FilterChip(selected = year == selected, onClick = { onSelect(year) }, label = { Text(year.toString()) })
        }
    }
}

/** Reiter „Statistik“ der Podcast-Bibliothek: gehörte Zeit insgesamt, je Podcast und im Verlauf der letzten Monate. */
@Composable
fun PodcastStatsScreen(
    onFeedClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastStatsViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val current = stats ?: return
    if (current.year == null && current.totalMs == 0L && current.finishedEpisodes == 0) {
        Box(modifier.fillMaxSize()) { EmptyHint(stringResource(R.string.podcast_stats_empty), Modifier.padding(16.dp)) }
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        item(key = "years") { YearChips(current.years, current.year, viewModel::selectYear) }
        current.year?.let { year ->
            if (current.totalMs == 0L && current.finishedEpisodes == 0) {
                item(key = "year-empty") { EmptyHint(stringResource(R.string.stats_year_empty, year), Modifier.padding(16.dp)) }
                return@LazyColumn
            }
        }
        item(key = "summary") { StatCard { Summary(current) } }
        if (current.year != null) {
            item(key = "year-note") {
                Text(
                    stringResource(R.string.stats_year_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 4.dp),
                )
            }
        }
        if (current.podcasts.isNotEmpty()) {
            item(key = "by-podcast") { SectionHeader(stringResource(R.string.podcast_stats_by_podcast)) }
            groupedItems(current.podcasts, key = { it.key }) { podcast ->
                PodcastRow(podcast, share = podcast.listenedMs.toFloat() / current.totalMs.coerceAtLeast(1), current.totalMs, onFeedClick)
            }
        }
        item(key = "history") { SectionHeader(stringResource(R.string.podcast_stats_history)) }
        item(key = "chart") {
            if (current.hasHistory) {
                val locale = LocalConfiguration.current.locales[0]
                StatCard {
                    BarChart(
                        current.months.map { StatBar(Month.of(it.month).getDisplayName(TextStyle.SHORT, locale), it.listenedMs) },
                        stringResource(R.string.podcast_stats_hours_per_month),
                        Modifier.padding(16.dp),
                    )
                }
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
                val episodes = if (podcast.episodes > 0) pluralStringResource(R.plurals.podcast_stats_episodes, podcast.episodes, podcast.episodes) + " · " else ""
                val note = when {
                    podcast.archived -> " · " + stringResource(R.string.podcast_stats_archived)
                    feedId == null -> " · " + stringResource(R.string.podcast_stats_unsubscribed)
                    else -> ""
                }
                Text("$episodes$percent %$note")
                LinearProgressIndicator(progress = { share.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        },
        trailingContent = { Text(formatListened(podcast.listenedMs), style = MaterialTheme.typography.titleSmall) },
        modifier = if (feedId != null) Modifier.clickable { onFeedClick(feedId) } else Modifier,
    )
}
