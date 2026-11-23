package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.flow.map
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.stats.StatsRepository
import de.kleinabersmart.earloft.core.model.PodcastStats
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

    /** `null` as long as the numbers are still loading. */
    val stats: StateFlow<PodcastStats?> = year
        // Inside, so that a failed query doesn't end the year selection; a database error used to end the app (review2 J49).
        .flatMapLatest { repository.podcastStats(it).map<PodcastStats, PodcastStats?> { stats -> stats }.orOnError(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Shows this year's numbers; `null` for "Total". */
    fun selectYear(value: Int?) {
        year.value = value
    }
}
