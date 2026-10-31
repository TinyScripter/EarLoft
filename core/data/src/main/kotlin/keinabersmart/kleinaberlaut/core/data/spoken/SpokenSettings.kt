package keinabersmart.kleinaberlaut.core.data.spoken

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
    /** Schalter Musik / Gesprochenes in Start und Bibliothek (Standard: Musik). */
    val mode: Flow<SpokenMode> = context.spokenSettingsDataStore.data.map { preferences ->
        preferences[KEY_MODE]?.let { name -> SpokenMode.entries.firstOrNull { it.name == name } } ?: SpokenMode.MUSIC
    }

    suspend fun setMode(mode: SpokenMode) {
        context.spokenSettingsDataStore.edit { it[KEY_MODE] = mode.name }
    }

    private companion object {
        val KEY_MODE = stringPreferencesKey("mode")
    }
}
