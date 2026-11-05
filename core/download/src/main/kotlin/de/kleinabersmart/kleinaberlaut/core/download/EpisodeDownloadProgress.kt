package de.kleinabersmart.kleinaberlaut.core.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fortschritt der gerade ladenden Folge (nur im Speicher, der Worker meldet ihn alle halbe Sekunde). Der Wert liegt
 * zwischen 0 und 1; [UNKNOWN] heißt, der Server nennt die Dateigröße nicht.
 */
@Singleton
class EpisodeDownloadProgress @Inject constructor() {
    private val _fractions = MutableStateFlow<Map<Long, Float>>(emptyMap())

    /** Folge → Anteil; nur für Folgen, die gerade laden. */
    val fractions: StateFlow<Map<Long, Float>> = _fractions.asStateFlow()

    /**
     * Auf ganze Prozent gerundet: Bleibt der Wert gleich, ist auch die Map gleich und der [StateFlow] meldet nichts –
     * die Folgenliste setzt sich so höchstens hundertmal je Download neu zusammen (Review C5).
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
