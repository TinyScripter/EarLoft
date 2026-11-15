package de.kleinabersmart.earloft.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.playback.NowPlaying
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "widget_state")

/**
 * Was die Widgets für einen Bereich zuletzt zeigten, pausiert; Position und Länge (0 = unbekannt) nur bei Büchern und
 * Folgen. [lastActiveAt] (Wanduhr): wann der Bereich zuletzt im Player war.
 */
data class SavedWidget(val nowPlaying: NowPlaying, val positionMs: Long?, val durationMs: Long?, val lastActiveAt: Long)

/**
 * Letzter Stand je Bereich über das Ende des Prozesses hinaus, damit die Widgets nach langer Pause nicht leer werden und
 * ein Widget, dessen Bereiche gerade nicht laufen, ihr zuletzt Gehörtes zeigt. Cover und Farben werden daraus neu geladen.
 * Vom Backup ausgeschlossen (IDs der Hauptdatenbank).
 */
@Singleton
class WidgetStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun load(): Map<SpokenMode, SavedWidget> {
        val prefs = context.widgetStateDataStore.data.first()
        val saved = SpokenMode.entries.mapNotNull { area -> prefs.load(Keys(area.name.lowercase() + "_"))?.let { area to it } }.toMap()
        if (saved.isNotEmpty()) return saved
        // Frühere Versionen merkten sich nur einen Stand, ohne Bereich.
        val legacy = prefs.load(Keys("")) ?: return emptyMap()
        return mapOf(legacy.nowPlaying.area to legacy)
    }

    suspend fun save(saved: Collection<SavedWidget>) {
        context.widgetStateDataStore.edit { prefs ->
            saved.forEach { prefs.save(Keys(it.nowPlaying.area.name.lowercase() + "_"), it) }
        }
    }

    private fun Preferences.load(keys: Keys): SavedWidget? {
        val title = this[keys.title] ?: return null
        val bookId = this[keys.bookId]
        return SavedWidget(
            nowPlaying = NowPlaying(
                songId = this[keys.songId],
                title = title,
                artist = this[keys.artist].orEmpty(),
                albumId = this[keys.albumId],
                isPlaying = false,
                bookId = bookId,
                isEpisode = this[keys.episode] ?: false,
            ),
            positionMs = this[keys.positionMs]?.takeIf { bookId != null },
            durationMs = this[keys.durationMs]?.takeIf { bookId != null },
            lastActiveAt = this[keys.lastActiveAt] ?: 0L,
        )
    }

    private fun MutablePreferences.save(keys: Keys, saved: SavedWidget) {
        val nowPlaying = saved.nowPlaying
        this[keys.title] = nowPlaying.title
        this[keys.artist] = nowPlaying.artist
        setOrRemove(keys.songId, nowPlaying.songId)
        setOrRemove(keys.albumId, nowPlaying.albumId)
        setOrRemove(keys.bookId, nowPlaying.bookId)
        this[keys.episode] = nowPlaying.isEpisode
        setOrRemove(keys.positionMs, saved.positionMs)
        setOrRemove(keys.durationMs, saved.durationMs)
        this[keys.lastActiveAt] = saved.lastActiveAt
    }

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<Long>, value: Long?) {
        if (value == null) remove(key) else this[key] = value
    }

    /** Schlüssel eines Bereichs („music_title“ …); ohne [prefix] die einer früheren Version. */
    private class Keys(prefix: String) {
        val title = stringPreferencesKey("${prefix}title")
        val artist = stringPreferencesKey("${prefix}artist")
        val songId = longPreferencesKey("${prefix}song_id")
        val albumId = longPreferencesKey("${prefix}album_id")
        val bookId = longPreferencesKey("${prefix}book_id")
        val episode = booleanPreferencesKey("${prefix}episode")
        val positionMs = longPreferencesKey("${prefix}position_ms")
        val durationMs = longPreferencesKey("${prefix}duration_ms")
        val lastActiveAt = longPreferencesKey("${prefix}last_active_at")
    }
}
