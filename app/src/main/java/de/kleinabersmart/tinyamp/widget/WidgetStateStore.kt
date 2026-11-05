package de.kleinabersmart.tinyamp.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.core.playback.NowPlaying
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "widget_state")

/** Was das Widget zuletzt zeigte, pausiert; Position und Länge (0 = unbekannt) nur bei Büchern und Folgen. */
data class SavedWidget(val nowPlaying: NowPlaying, val positionMs: Long?, val durationMs: Long?)

/**
 * Letzter Stand des Widgets über das Ende des Prozesses hinaus, damit es nach langer Pause nicht leer wird. Cover und
 * Farben werden daraus neu geladen. Vom Backup ausgeschlossen (IDs der Hauptdatenbank).
 */
@Singleton
class WidgetStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun load(): SavedWidget? {
        val prefs = context.widgetStateDataStore.data.first()
        val title = prefs[KEY_TITLE] ?: return null
        val bookId = prefs[KEY_BOOK_ID]
        return SavedWidget(
            nowPlaying = NowPlaying(
                songId = prefs[KEY_SONG_ID],
                title = title,
                artist = prefs[KEY_ARTIST].orEmpty(),
                albumId = prefs[KEY_ALBUM_ID],
                isPlaying = false,
                bookId = bookId,
            ),
            positionMs = prefs[KEY_POSITION_MS]?.takeIf { bookId != null },
            durationMs = prefs[KEY_DURATION_MS]?.takeIf { bookId != null },
        )
    }

    suspend fun save(saved: SavedWidget) {
        context.widgetStateDataStore.edit { prefs ->
            val nowPlaying = saved.nowPlaying
            prefs[KEY_TITLE] = nowPlaying.title
            prefs[KEY_ARTIST] = nowPlaying.artist
            prefs.setOrRemove(KEY_SONG_ID, nowPlaying.songId)
            prefs.setOrRemove(KEY_ALBUM_ID, nowPlaying.albumId)
            prefs.setOrRemove(KEY_BOOK_ID, nowPlaying.bookId)
            prefs.setOrRemove(KEY_POSITION_MS, saved.positionMs)
            prefs.setOrRemove(KEY_DURATION_MS, saved.durationMs)
        }
    }

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<Long>, value: Long?) {
        if (value == null) remove(key) else this[key] = value
    }

    private companion object {
        val KEY_TITLE = stringPreferencesKey("title")
        val KEY_ARTIST = stringPreferencesKey("artist")
        val KEY_SONG_ID = longPreferencesKey("song_id")
        val KEY_ALBUM_ID = longPreferencesKey("album_id")
        val KEY_BOOK_ID = longPreferencesKey("book_id")
        val KEY_POSITION_MS = longPreferencesKey("position_ms")
        val KEY_DURATION_MS = longPreferencesKey("duration_ms")
    }
}
