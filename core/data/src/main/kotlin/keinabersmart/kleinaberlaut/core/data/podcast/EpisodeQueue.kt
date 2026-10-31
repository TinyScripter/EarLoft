package keinabersmart.kleinaberlaut.core.data.podcast

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.episodeQueueDataStore: DataStore<Preferences> by preferencesDataStore(name = "episode_queue")

/**
 * Die Warteschlange der Podcasts ist die Zeile „Weiterhören“ (Konzept 12.4). Hier steht ihre Reihenfolge, soweit sie
 * festgelegt wurde (die Ids gehören zur Tabelle `books`; Folgen und Bücher teilen die Liste, jede Zeile liest nur ihre eigenen): [order] ganz vorn, dahinter die Folgen, die von selbst dort stehen (angefangene und neue). Wer die
 * Zeile ändert, nutzt `ContinueListening`.
 */
@Singleton
class EpisodeQueue @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Festgelegte Reihenfolge, die vorderste Folge zuerst. */
    val order: Flow<List<Long>> = context.episodeQueueDataStore.data.map { it.read(KEY_ORDER) }

    /** Von Hand aus der Zeile genommene Folgen; sie erscheinen nicht mehr, bis sie gespielt oder wieder eingefügt werden. */
    val hidden: Flow<Set<Long>> = context.episodeQueueDataStore.data.map { it.read(KEY_HIDDEN).toSet() }

    /** Stellt die Folge an die erste Stelle. */
    suspend fun moveToFront(bookId: Long) = edit { order -> listOf(bookId) + (order - bookId) }

    /** Stellt die Folge an die zweite Stelle, hinter die gerade gespielte; steht sie schon in der festgelegten Reihenfolge, bleibt sie dort. */
    suspend fun insertSecond(bookId: Long) = edit { order ->
        if (bookId in order) order else order.take(1) + bookId + order.drop(1)
    }

    /** Hängt die Folge ans Ende (steht sie schon in der festgelegten Reihenfolge, bleibt sie an ihrer Stelle). */
    suspend fun append(bookId: Long) = edit { order -> if (bookId in order) order else order + bookId }

    /** Legt die ganze Reihenfolge fest (Bearbeiten der Warteschlange); die Folgen darin sind nicht mehr ausgeblendet. */
    suspend fun setOrder(bookIds: List<Long>) = edit { old -> bookIds.distinct() + (old - bookIds.toSet()) }

    /** Nimmt die Folge aus der Zeile, egal ob sie festgelegt oder von selbst dort steht. */
    suspend fun remove(bookId: Long) {
        context.episodeQueueDataStore.edit { prefs ->
            prefs.write(KEY_ORDER, prefs.read(KEY_ORDER) - bookId)
            val hidden = prefs.read(KEY_HIDDEN)
            if (bookId !in hidden) prefs.write(KEY_HIDDEN, hidden + bookId)
        }
    }

    /** Ändert die Reihenfolge; die Folge ist danach nicht mehr ausgeblendet. */
    private suspend fun edit(change: (List<Long>) -> List<Long>) {
        context.episodeQueueDataStore.edit { prefs ->
            prefs.write(KEY_ORDER, change(prefs.read(KEY_ORDER)))
            // Die geänderte Folge steht jetzt wieder in der Zeile; alle anderen behalten ihren Zustand.
            val ids = prefs.read(KEY_ORDER).toSet()
            prefs.write(KEY_HIDDEN, prefs.read(KEY_HIDDEN).filter { it !in ids })
        }
    }

    private fun Preferences.read(key: Preferences.Key<String>): List<Long> =
        this[key].orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    private fun MutablePreferences.write(key: Preferences.Key<String>, ids: List<Long>) {
        this[key] = ids.joinToString(",")
    }

    private companion object {
        val KEY_ORDER = stringPreferencesKey("order")
        val KEY_HIDDEN = stringPreferencesKey("hidden")
    }
}
