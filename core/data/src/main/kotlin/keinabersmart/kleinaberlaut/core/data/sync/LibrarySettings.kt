package keinabersmart.kleinaberlaut.core.data.sync

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

private val Context.librarySettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "library_settings")

@Singleton
class LibrarySettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Musik auf dem Gerät (MediaStore) in die Bibliothek aufnehmen (Standard: an). Aus = nur Server;
     * die lokalen Songs verschwinden dann aus der Bibliothek, siehe [LibraryRefresher].
     */
    val useLocalMedia: Flow<Boolean> = context.librarySettingsDataStore.data.map { it[KEY_LOCAL_MEDIA] ?: true }

    suspend fun setUseLocalMedia(enabled: Boolean) {
        context.librarySettingsDataStore.edit { it[KEY_LOCAL_MEDIA] = enabled }
    }

    private companion object {
        val KEY_LOCAL_MEDIA = booleanPreferencesKey("use_local_media")
    }
}
