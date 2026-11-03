package keinabersmart.kleinaberlaut.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.stats.StatsRepository
import keinabersmart.kleinaberlaut.core.model.PodcastStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PodcastStatsViewModel @Inject constructor(repository: StatsRepository) : ViewModel() {
    private val year = MutableStateFlow<Int?>(null)

    /** `null`, solange die Zahlen noch geladen werden. */
    val stats: StateFlow<PodcastStats?> =
        year.flatMapLatest { repository.podcastStats(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Zeigt die Zahlen dieses Jahres; `null` für „Gesamt“. */
    fun selectYear(value: Int?) {
        year.value = value
    }
}
