package de.kleinabersmart.kleinaberlaut.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.kleinaberlaut.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.kleinaberlaut.core.data.stats.ServerStatsRepository
import de.kleinabersmart.kleinaberlaut.core.data.stats.StatsRepository
import de.kleinabersmart.kleinaberlaut.core.model.DeviceBookStats
import de.kleinabersmart.kleinaberlaut.core.model.ServerBookStats
import de.kleinabersmart.kleinaberlaut.core.model.SpokenServer
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
