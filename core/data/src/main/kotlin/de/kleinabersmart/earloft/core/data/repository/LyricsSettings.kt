package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.lyricsSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "lyrics_settings")

/** Songtexte aus dem Internet. Standardmäßig aus, weil dafür Titel und Interpret an Dritte (LRCLIB) gehen. */
@Singleton
class LyricsSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Fehlende oder nur statische Songtexte bei LRCLIB nachschlagen. */
    val onlineLyrics: Flow<Boolean> = context.lyricsSettingsDataStore.data.map { it[KEY_ONLINE_LYRICS] ?: false }

    internal suspend fun setOnlineLyrics(enabled: Boolean) {
        context.lyricsSettingsDataStore.edit { it[KEY_ONLINE_LYRICS] = enabled }
    }

    private companion object {
        val KEY_ONLINE_LYRICS = booleanPreferencesKey("online_lyrics")
    }
}
