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
import de.kleinabersmart.earloft.core.model.AlbumGridSize
import de.kleinabersmart.earloft.core.model.AlbumView
import de.kleinabersmart.earloft.core.model.ArtistSort
import de.kleinabersmart.earloft.core.model.MobileStreamQuality
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
     * Include music on the device (MediaStore) in the library (default: off, see [LocalSourcesDefault]). Off = servers
     * only; the local songs then disappear from the library, see [LibraryRefresher].
     */
    val useLocalMedia: Flow<Boolean> = context.librarySettingsDataStore.data.map { it[KEY_LOCAL_MEDIA] ?: false }

    suspend fun setUseLocalMedia(enabled: Boolean) {
        context.librarySettingsDataStore.edit { it[KEY_LOCAL_MEDIA] = enabled }
    }

    /** Interval of the automatic full sync of the servers in days: [SYNC_NEVER], 1, 7 or 30. */
    val syncIntervalDays: Flow<Int> = context.librarySettingsDataStore.data.map { it[KEY_SYNC_DAYS] ?: DEFAULT_SYNC_DAYS }

    suspend fun setSyncIntervalDays(days: Int) {
        context.librarySettingsDataStore.edit { it[KEY_SYNC_DAYS] = days.coerceAtLeast(SYNC_NEVER) }
    }

    /** Order of albums in the library (default: by title). */
    val albumSort: Flow<AlbumSort> = context.librarySettingsDataStore.data.map { it[KEY_ALBUM_SORT].toEnum(AlbumSort.TITLE) }

    suspend fun setAlbumSort(sort: AlbumSort) {
        context.librarySettingsDataStore.edit { it[KEY_ALBUM_SORT] = sort.name }
    }

    /** How the library shows albums (default: grid). */
    val albumView: Flow<AlbumView> = context.librarySettingsDataStore.data.map { it[KEY_ALBUM_VIEW].toEnum(AlbumView.GRID) }

    suspend fun setAlbumView(view: AlbumView) {
        context.librarySettingsDataStore.edit { it[KEY_ALBUM_VIEW] = view.name }
    }

    /** Tile size of the album grid (default: medium). */
    val albumGridSize: Flow<AlbumGridSize> =
        context.librarySettingsDataStore.data.map { it[KEY_ALBUM_GRID_SIZE].toEnum(AlbumGridSize.MEDIUM) }

    suspend fun setAlbumGridSize(size: AlbumGridSize) {
        context.librarySettingsDataStore.edit { it[KEY_ALBUM_GRID_SIZE] = size.name }
    }

    /** Order of artists in the library (default: by name). */
    val artistSort: Flow<ArtistSort> = context.librarySettingsDataStore.data.map { it[KEY_ARTIST_SORT].toEnum(ArtistSort.NAME) }

    suspend fun setArtistSort(sort: ArtistSort) {
        context.librarySettingsDataStore.edit { it[KEY_ARTIST_SORT] = sort.name }
    }

    /** Order of songs in the library (default: by title). */
    val songSort: Flow<SongSort> = context.librarySettingsDataStore.data.map { it[KEY_SONG_SORT].toEnum(SongSort.TITLE) }

    suspend fun setSongSort(sort: SongSort) {
        context.librarySettingsDataStore.edit { it[KEY_SONG_SORT] = sort.name }
    }

    /** Streams over mobile data: original or transcoded by the server (default: original). On Wi-Fi always original. */
    val mobileStreamQuality: Flow<MobileStreamQuality> =
        context.librarySettingsDataStore.data.map { it[KEY_MOBILE_STREAM_QUALITY].toEnum(MobileStreamQuality.ORIGINAL) }

    suspend fun setMobileStreamQuality(quality: MobileStreamQuality) {
        context.librarySettingsDataStore.edit { it[KEY_MOBILE_STREAM_QUALITY] = quality.name }
    }

    companion object {
        /** No automatic sync; only manually (and when setting up or enabling a server). */
        const val SYNC_NEVER = 0
        val SYNC_DAY_CHOICES = listOf(SYNC_NEVER, 1, 7, 30)
        const val DEFAULT_SYNC_DAYS = 1

        private val KEY_SYNC_DAYS = intPreferencesKey("sync_interval_days")
        private val KEY_ALBUM_SORT = stringPreferencesKey("album_sort")
        private val KEY_ALBUM_VIEW = stringPreferencesKey("album_view")
        private val KEY_ALBUM_GRID_SIZE = stringPreferencesKey("album_grid_size")
        private val KEY_ARTIST_SORT = stringPreferencesKey("artist_sort")
        private val KEY_SONG_SORT = stringPreferencesKey("song_sort")
        private val KEY_MOBILE_STREAM_QUALITY = stringPreferencesKey("mobile_stream_quality")

        /** Unknown values (e.g. from a newer backup) fall back to [default]. */
        private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
            enumValues<T>().firstOrNull { it.name == this } ?: default
    }
}
