package keinabersmart.kleinaberlaut.core.data.spoken

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.model.SpokenMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.spokenSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "spoken_settings")

@Singleton
class SpokenSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Schalter Musik / Podcasts / Bücher in Start und Bibliothek (Standard: Musik). */
    val mode: Flow<SpokenMode> = context.spokenSettingsDataStore.data.map { preferences ->
        // „SPOKEN“ hieß der Modus, bevor er in Podcasts und Bücher geteilt wurde.
        preferences[KEY_MODE]?.let { name -> if (name == "SPOKEN") SpokenMode.BOOKS else SpokenMode.entries.firstOrNull { it.name == name } }
            ?: SpokenMode.MUSIC
    }

    suspend fun setMode(mode: SpokenMode) {
        context.spokenSettingsDataStore.edit { it[KEY_MODE] = mode.name }
    }

    /** Suche über Musik und Bücher zugleich (Standard: an); aus = nur der Inhalt des gewählten Modus. */
    val searchAll: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_SEARCH_ALL] ?: true }

    suspend fun setSearchAll(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_SEARCH_ALL] = enabled }
    }

    /** Bücher aus dem Ordner „Audiobooks“ auf diesem Gerät anzeigen (Standard: an, wie die lokale Musik). */
    val useLocalBooks: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_LOCAL_BOOKS] ?: true }

    suspend fun setUseLocalBooks(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_LOCAL_BOOKS] = enabled }
    }

    private companion object {
        val KEY_LOCAL_BOOKS = booleanPreferencesKey("local_books")
        val KEY_MODE = stringPreferencesKey("mode")
        val KEY_SEARCH_ALL = booleanPreferencesKey("search_all")
    }
}
