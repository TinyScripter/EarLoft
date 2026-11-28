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
import de.kleinabersmart.earloft.core.data.backup.UserDataBackup
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.playback.NowPlaying
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "widget_state")

/**
 * What the widgets last showed for a section, paused; position and duration (0 = unknown) only for books and
 * episodes. [lastActiveAt] (wall clock): when the section was last in the player.
 */
data class SavedWidget(val nowPlaying: NowPlaying, val positionMs: Long?, val durationMs: Long?, val lastActiveAt: Long)

/**
 * Last state per section beyond the end of the process, so the widgets don't go blank after a long pause and
 * a widget whose sections aren't currently playing shows what was last heard. Cover and colors are reloaded from it.
 * Excluded from backup (IDs of the main database). A state from before the main database was created anew refers to IDs that
 * Room hands out again: cover and colors of another album, "play" of another book (review5 P21); it is dropped.
 */
@Singleton
class WidgetStateStore internal constructor(
    private val context: Context,
    /** When the main database was created (wall clock); `null` if unknown. */
    private val databaseCreatedAt: suspend () -> Long?,
) {
    @Inject constructor(@ApplicationContext context: Context, backup: UserDataBackup) :
        this(context, { backup.databaseId()?.toLongOrNull() })

    suspend fun load(): Map<SpokenMode, SavedWidget> {
        val created = databaseCreatedAt() ?: 0L
        return loadAll().filterValues { it.lastActiveAt >= created }
    }

    private suspend fun loadAll(): Map<SpokenMode, SavedWidget> {
        val prefs = context.widgetStateDataStore.data.first()
        val saved = SpokenMode.entries.mapNotNull { area -> prefs.load(Keys(area.name.lowercase() + "_"))?.let { area to it } }.toMap()
        if (saved.isNotEmpty()) return saved
        // Earlier versions remembered only one state, without a section.
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

    /** Keys of a section ("music_title" …); without [prefix] those of an earlier version. */
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
