package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.material3.TextButton
import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.ui.components.readableWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
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
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.BookListening
import de.kleinabersmart.earloft.core.model.DeviceBookStats
import de.kleinabersmart.earloft.core.model.MonthListening
import de.kleinabersmart.earloft.core.model.ServerBookStats
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.GroupMargin
import de.kleinabersmart.earloft.ui.components.SectionHeader
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.components.keepHorizontalScroll
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.Month
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/**
 * "Statistics" tab of the books library in two separate sections: what Audiobookshelf counts from the sessions of all
 * devices, and what this app counted itself.
 */
@Composable
fun BookStatsScreen(
    onBookClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookStatsViewModel = hiltViewModel(),
) {
    val device by viewModel.device.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val selected by viewModel.selectedServerId.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()
    val refresh by viewModel.refresh.collectAsStateWithLifecycle()

    val year = device?.year ?: server?.year
    val years = (device?.years.orEmpty() + server?.years.orEmpty() + listOfNotNull(year)).distinct().sortedDescending()

    LazyColumn(modifier.fillMaxSize().readableWidth(TwoColumnMinWidth)) {
        yearChipsHeader(years, year, viewModel::selectYear)
        if (year != null) {
            item(key = "year-note") {
                Text(
                    stringResource(R.string.stats_year_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = GroupMargin + 16.dp),
                )
            }
        }
        if (servers.isNotEmpty()) {
            item(key = "server-header") { SectionHeader(stringResource(R.string.book_stats_server_title)) }
            if (servers.size > 1) {
                item(key = "server-chips") {
                    val chipScroll = rememberScrollState()
                    Row(
                        Modifier.keepHorizontalScroll(chipScroll).horizontalScroll(chipScroll).padding(horizontal = GroupMargin + 16.dp).padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        servers.forEach { s ->
                            FilterChip(selected = s.serverId == selected, onClick = { viewModel.select(s.serverId) }, label = { Text(s.name) })
                        }
                    }
                }
            }
            val current = server
            if (current == null) {
                item(key = "server-empty") {
                    EmptyHint(
                        stringResource(if (refresh == StatsRefresh.FAILED) R.string.book_stats_server_failed else R.string.book_stats_server_loading),
                        Modifier.padding(16.dp),
                    )
                }
            } else {
                serverSection(current, refresh == StatsRefresh.FAILED, onBookClick)
            }
            // A failed fetch can be repeated, as in the other places that load from a server.
            if (refresh == StatsRefresh.FAILED) {
                item(key = "server-retry") {
                    TextButton(onClick = viewModel::retry, modifier = Modifier.padding(horizontal = GroupMargin + 4.dp)) {
                        Text(stringResource(R.string.lyrics_retry))
                    }
                }
            }
        }

        item(key = "device-header") { SectionHeader(stringResource(R.string.book_stats_device_title)) }
        val own = device
        if (own != null) {
            if (own.totalMs == 0L) {
                item(key = "device-empty") {
                    EmptyHint(
                        if (year != null) stringResource(R.string.stats_year_empty, year) else stringResource(R.string.book_stats_device_empty),
                        Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                deviceSection(own, onBookClick)
            }
        }
        item(key = "end") { Box(Modifier.height(16.dp)) }
    }
}

private fun LazyListScope.serverSection(stats: ServerBookStats, stale: Boolean, onBookClick: (Long) -> Unit) {
    item(key = "server-summary") {
        StatCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(formatListened(stats.totalMs), style = MaterialTheme.typography.displaySmall)
                Text(
                    stringResource(R.string.podcast_stats_listened),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (stats.year == null) {
                    Text(
                        stringResource(R.string.book_stats_today, formatListened(stats.todayMs)) +
                            (if (stats.streakDays > 0) " · " + pluralStringResource(R.plurals.book_stats_streak, stats.streakDays, stats.streakDays) else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
    if (stats.books.isNotEmpty()) {
        item(key = "server-top") { SectionHeader(stringResource(R.string.book_stats_top)) }
        groupedItems(stats.books, key = { "server-" + it.key }) { BookListeningRow(it, onBookClick) }
    }
    if (stats.hasHistory || stats.weekdays.any { it.listenedMs > 0 }) {
        item(key = "server-history") { SectionHeader(stringResource(R.string.podcast_stats_history)) }
    }
    if (stats.hasHistory) {
        item(key = "server-months") {
            StatCard {
                BarChart(stats.months.toBars(LocalConfiguration.current.locales[0]), stringResource(R.string.podcast_stats_hours_per_month), Modifier.padding(16.dp))
            }
        }
    }
    if (stats.weekdays.any { it.listenedMs > 0 }) {
        item(key = "server-weekdays") {
            val locale = LocalConfiguration.current.locales[0]
            StatCard {
                BarChart(
                    stats.weekdays.map { StatBar(DayOfWeek.of(it.dayOfWeek).getDisplayName(TextStyle.SHORT, locale), it.listenedMs) },
                    stringResource(R.string.book_stats_weekdays),
                    Modifier.padding(16.dp),
                )
            }
        }
    }
    item(key = "server-updated") {
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(stats.fetchedAt))
        Text(
            stringResource(if (stale) R.string.book_stats_stale else R.string.book_stats_updated, time),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 8.dp),
        )
    }
}

private fun LazyListScope.deviceSection(stats: DeviceBookStats, onBookClick: (Long) -> Unit) {
    item(key = "device-summary") {
        StatCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(formatListened(stats.totalMs), style = MaterialTheme.typography.displaySmall)
                Text(
                    pluralStringResource(R.plurals.book_stats_finished, stats.finishedBooks, stats.finishedBooks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SilenceSavedLine(stats.silenceSavedMs)
            }
        }
    }
    if (stats.books.isNotEmpty()) {
        item(key = "device-top") { SectionHeader(stringResource(R.string.book_stats_top)) }
        groupedItems(stats.books, key = { "device-" + it.key }) { BookListeningRow(it, onBookClick) }
    }
    if (stats.hasHistory) {
        item(key = "device-history") { SectionHeader(stringResource(R.string.podcast_stats_history)) }
        item(key = "device-months") {
            StatCard {
                BarChart(stats.months.toBars(LocalConfiguration.current.locales[0]), stringResource(R.string.podcast_stats_hours_per_month), Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun BookListeningRow(book: BookListening, onBookClick: (Long) -> Unit) {
    val bookId = book.bookId
    ListItem(
        headlineContent = { Text(book.title.ifBlank { "—" }) },
        trailingContent = { Text(formatListened(book.listenedMs), style = MaterialTheme.typography.titleSmall) },
        modifier = if (bookId != null) Modifier.clickable { onBookClick(bookId) } else Modifier,
    )
}

private fun List<MonthListening>.toBars(locale: Locale) =
    map { StatBar(Month.of(it.month).getDisplayName(TextStyle.SHORT, locale), it.listenedMs) }
