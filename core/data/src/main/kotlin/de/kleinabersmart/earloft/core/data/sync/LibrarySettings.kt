package de.kleinabersmart.earloft.core.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.model.AlbumSort
import de.kleinabersmart.earloft.core.model.ArtistSort
import de.kleinabersmart.earloft.core.model.SongSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val KEY_LOCAL_MEDIA = booleanPreferencesKey("use_local_media")

internal val Context.librarySettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "library_settings",
    produceMigrations = { context -> listOf(LocalSourcesDefault(context, listOf(KEY_LOCAL_MEDIA))) },
)

@Singleton
class LibrarySettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Musik auf dem Gerät (MediaStore) in die Bibliothek aufnehmen (Standard: aus, siehe [LocalSourcesDefault]). Aus = nur
     * Server; die lokalen Songs verschwinden dann aus der Bibliothek, siehe [LibraryRefresher].
     */
    val useLocalMedia: Flow<Boolean> = context.librarySettingsDataStore.data.map { it[KEY_LOCAL_MEDIA] ?: false }

    suspend fun setUseLocalMedia(enabled: Boolean) {
        context.librarySettingsDataStore.edit { it[KEY_LOCAL_MEDIA] = enabled }
    }

    /** Abstand des automatischen Vollabgleichs der Server in Tagen: [SYNC_NEVER], 1, 7 oder 30. */
    val syncIntervalDays: Flow<Int> = context.librarySettingsDataStore.data.map { it[KEY_SYNC_DAYS] ?: DEFAULT_SYNC_DAYS }

    suspend fun setSyncIntervalDays(days: Int) {
        context.librarySettingsDataStore.edit { it[KEY_SYNC_DAYS] = days.coerceAtLeast(SYNC_NEVER) }
    }

    /** Reihenfolge der Alben in der Bibliothek (Standard: nach Titel). */
    val albumSort: Flow<AlbumSort> = context.librarySettingsDataStore.data.map { it[KEY_ALBUM_SORT].toEnum(AlbumSort.TITLE) }

    suspend fun setAlbumSort(sort: AlbumSort) {
        context.librarySettingsDataStore.edit { it[KEY_ALBUM_SORT] = sort.name }
    }

    /** Reihenfolge der Künstler in der Bibliothek (Standard: nach Name). */
    val artistSort: Flow<ArtistSort> = context.librarySettingsDataStore.data.map { it[KEY_ARTIST_SORT].toEnum(ArtistSort.NAME) }

    suspend fun setArtistSort(sort: ArtistSort) {
        context.librarySettingsDataStore.edit { it[KEY_ARTIST_SORT] = sort.name }
    }

    /** Reihenfolge der Songs in der Bibliothek (Standard: nach Titel). */
    val songSort: Flow<SongSort> = context.librarySettingsDataStore.data.map { it[KEY_SONG_SORT].toEnum(SongSort.TITLE) }

    suspend fun setSongSort(sort: SongSort) {
        context.librarySettingsDataStore.edit { it[KEY_SONG_SORT] = sort.name }
    }

    companion object {
        /** Kein automatischer Abgleich; nur von Hand (und beim Einrichten oder Einschalten eines Servers). */
        const val SYNC_NEVER = 0
        val SYNC_DAY_CHOICES = listOf(SYNC_NEVER, 1, 7, 30)
        const val DEFAULT_SYNC_DAYS = 1

        private val KEY_SYNC_DAYS = intPreferencesKey("sync_interval_days")
        private val KEY_ALBUM_SORT = stringPreferencesKey("album_sort")
        private val KEY_ARTIST_SORT = stringPreferencesKey("artist_sort")
        private val KEY_SONG_SORT = stringPreferencesKey("song_sort")

        /** Unbekannte Werte (z. B. aus einer neueren Sicherung) fallen auf [default] zurück. */
        private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
            enumValues<T>().firstOrNull { it.name == this } ?: default
    }
}
