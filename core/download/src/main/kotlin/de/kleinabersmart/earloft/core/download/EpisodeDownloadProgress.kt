package de.kleinabersmart.earloft.core.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Progress of the episode currently downloading (in memory only, the worker reports it every half second). The value is
 * between 0 and 1; [UNKNOWN] means the server doesn't report the file size.
 */
@Singleton
class EpisodeDownloadProgress @Inject constructor() {
    private val _fractions = MutableStateFlow<Map<Long, Float>>(emptyMap())

    /** Episode → fraction; only for episodes currently downloading. */
    val fractions: StateFlow<Map<Long, Float>> = _fractions.asStateFlow()

    /**
     * Rounded to whole percent: if the value stays the same, so does the map and the [StateFlow] emits nothing –
     * this way the episode list is rebuilt at most a hundred times per download (review C5).
     */
    internal fun report(bookId: Long, done: Long, total: Long?) {
        val fraction = if (total != null && total > 0) (done * 100 / total).coerceIn(0, 100) / 100f else UNKNOWN
        _fractions.update { it + (bookId to fraction) }
    }

    internal fun clear(bookId: Long) {
        _fractions.update { it - bookId }
    }

    companion object {
        const val UNKNOWN = -1f
    }
}
