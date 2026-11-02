package keinabersmart.kleinaberlaut.ui.spoken

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
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.spoken.AudiobookshelfServers
import keinabersmart.kleinaberlaut.core.data.stats.ServerStatsRepository
import keinabersmart.kleinaberlaut.core.data.stats.StatsRepository
import keinabersmart.kleinaberlaut.core.model.BookListening
import keinabersmart.kleinaberlaut.core.model.DeviceBookStats
import keinabersmart.kleinaberlaut.core.model.MonthListening
import keinabersmart.kleinaberlaut.core.model.ServerBookStats
import keinabersmart.kleinaberlaut.core.model.SpokenServer
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.Month
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class StatsRefresh { IDLE, RUNNING, FAILED }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookStatsViewModel @Inject constructor(
    stats: StatsRepository,
    private val serverStats: ServerStatsRepository,
    audiobookServers: AudiobookshelfServers,
) : ViewModel() {
    /** Was diese App selbst gezählt hat; `null`, solange es lädt. */
    private val yearFlow = MutableStateFlow<Int?>(null)

    val device: StateFlow<DeviceBookStats?> =
        yearFlow.flatMapLatest { stats.bookStats(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Eingeschaltete Audiobookshelf-Server. */
    val servers: StateFlow<List<SpokenServer>> = audiobookServers.servers
        .map { list -> list.filter { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val chosen = MutableStateFlow<String?>(null)

    /** Der angezeigte Server: der gewählte, sonst der erste. */
    val selectedServerId: StateFlow<String?> = combine(servers, chosen) { list, id ->
        list.firstOrNull { it.serverId == id }?.serverId ?: list.firstOrNull()?.serverId
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Letzter bekannter Stand des gewählten Servers (auch offline). */
    val server: StateFlow<ServerBookStats?> = selectedServerId
        .combine(yearFlow, ::Pair)
        .flatMapLatest { (id, year) -> if (id == null) flowOf(null) else serverStats.stats(id, year) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Zeigt die Zahlen dieses Jahres; `null` für „Gesamt“. */
    fun selectYear(value: Int?) {
        yearFlow.value = value
    }

    private val _refresh = MutableStateFlow(StatsRefresh.IDLE)
    val refresh: StateFlow<StatsRefresh> = _refresh.asStateFlow()

    init {
        // Beim Öffnen und bei jedem Wechsel des Servers den Stand abrufen, wenn er älter als eine Stunde ist.
        viewModelScope.launch {
            selectedServerId.filterNotNull().collect { id ->
                _refresh.value = StatsRefresh.RUNNING
                _refresh.value = if (serverStats.refresh(id)) StatsRefresh.IDLE else StatsRefresh.FAILED
            }
        }
    }

    fun select(serverId: String) {
        chosen.value = serverId
    }
}

/**
 * Reiter „Statistik“ der Bücher-Bibliothek in zwei getrennten Bereichen: was Audiobookshelf aus den Sitzungen aller
 * Geräte zählt, und was diese App selbst gezählt hat.
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

    LazyColumn(modifier.fillMaxSize()) {
        item(key = "years") { YearChips(years, year, viewModel::selectYear) }
        if (year != null) {
            item(key = "year-note") {
                Text(
                    stringResource(R.string.stats_year_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (servers.isNotEmpty()) {
            item(key = "server-header") { SectionHeader(stringResource(R.string.book_stats_server_title)) }
            if (servers.size > 1) {
                item(key = "server-chips") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    item(key = "server-months") {
        if (stats.hasHistory) {
            BarChart(stats.months.toBars(), stringResource(R.string.podcast_stats_hours_per_month), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
    item(key = "server-weekdays") {
        if (stats.weekdays.any { it.listenedMs > 0 }) {
            val locale = LocalConfiguration.current.locales[0]
            BarChart(
                stats.weekdays.map { StatBar(DayOfWeek.of(it.dayOfWeek).getDisplayName(TextStyle.SHORT, locale), it.listenedMs) },
                stringResource(R.string.book_stats_weekdays),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    if (stats.books.isNotEmpty()) {
        item(key = "server-top") { SectionHeader(stringResource(R.string.book_stats_top)) }
        items(stats.books, key = { "server-" + it.key }) { BookListeningRow(it, onBookClick) }
    }
    item(key = "server-updated") {
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(stats.fetchedAt))
        Text(
            stringResource(if (stale) R.string.book_stats_stale else R.string.book_stats_updated, time),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun LazyListScope.deviceSection(stats: DeviceBookStats, onBookClick: (Long) -> Unit) {
    item(key = "device-summary") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatListened(stats.totalMs), style = MaterialTheme.typography.displaySmall)
            Text(
                pluralStringResource(R.plurals.book_stats_finished, stats.finishedBooks, stats.finishedBooks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (stats.books.isNotEmpty()) {
        item(key = "device-top") { SectionHeader(stringResource(R.string.book_stats_top)) }
        items(stats.books, key = { "device-" + it.key }) { BookListeningRow(it, onBookClick) }
    }
    item(key = "device-months") {
        if (stats.hasHistory) {
            BarChart(stats.months.toBars(), stringResource(R.string.podcast_stats_hours_per_month), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
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

private fun List<MonthListening>.toBars() =
    map { StatBar(Month.of(it.month).getDisplayName(TextStyle.SHORT, Locale.getDefault()), it.listenedMs) }
