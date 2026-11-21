package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.stats.ServerStatsRepository
import de.kleinabersmart.earloft.core.data.stats.StatsRepository
import de.kleinabersmart.earloft.core.model.DeviceBookStats
import de.kleinabersmart.earloft.core.model.ServerBookStats
import de.kleinabersmart.earloft.core.model.SpokenServer
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
    /** What this app counted itself; `null` while loading. */
    private val yearFlow = MutableStateFlow<Int?>(null)

    val device: StateFlow<DeviceBookStats?> =
        yearFlow.flatMapLatest { stats.bookStats(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Enabled Audiobookshelf servers. */
    val servers: StateFlow<List<SpokenServer>> = audiobookServers.servers
        .map { list -> list.filter { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val chosen = MutableStateFlow<String?>(null)

    /** The displayed server: the selected one, otherwise the first. */
    val selectedServerId: StateFlow<String?> = combine(servers, chosen) { list, id ->
        list.firstOrNull { it.serverId == id }?.serverId ?: list.firstOrNull()?.serverId
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Last known state of the selected server (also offline). */
    val server: StateFlow<ServerBookStats?> = selectedServerId
        .combine(yearFlow, ::Pair)
        .flatMapLatest { (id, year) -> if (id == null) flowOf(null) else serverStats.stats(id, year) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Shows this year's numbers; `null` for "Total". */
    fun selectYear(value: Int?) {
        yearFlow.value = value
    }

    private val _refresh = MutableStateFlow(StatsRefresh.IDLE)
    val refresh: StateFlow<StatsRefresh> = _refresh.asStateFlow()

    init {
        // On opening and on every server change, fetch the state if it is older than an hour.
        viewModelScope.launch {
            selectedServerId.filterNotNull().collect { id -> refresh(id) }
        }
    }

    /** "Try again" after a failed fetch. */
    fun retry() {
        val id = selectedServerId.value ?: return
        viewModelScope.launch { refresh(id) }
    }

    private suspend fun refresh(serverId: String) {
        _refresh.value = StatsRefresh.RUNNING
        _refresh.value = if (serverStats.refresh(serverId)) StatsRefresh.IDLE else StatsRefresh.FAILED
    }

    fun select(serverId: String) {
        chosen.value = serverId
    }
}
