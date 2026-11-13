package de.kleinabersmart.earloft.core.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.kleinabersmart.earloft.core.data.backup.BackedUpSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.downloadSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "download_settings")

@Singleton
class DownloadSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Standard: nur über ungetaktete Netze (WLAN) laden. */
    val wifiOnly: Flow<Boolean> = context.downloadSettingsDataStore.data.map { it[KEY_WIFI_ONLY] ?: true }

    suspend fun setWifiOnly(wifiOnly: Boolean) {
        context.downloadSettingsDataStore.edit { it[KEY_WIFI_ONLY] = wifiOnly }
    }

    /** Favoriten automatisch herunterladen (Standard: aus – es kann viel Speicher kosten). */
    val autoDownloadFavorites: Flow<Boolean> =
        context.downloadSettingsDataStore.data.map { it[KEY_AUTO_FAVORITES] ?: false }

    suspend fun setAutoDownloadFavorites(enabled: Boolean) {
        context.downloadSettingsDataStore.edit { it[KEY_AUTO_FAVORITES] = enabled }
    }

    /**
     * Mit [autoDownloadFavorites] auch alles von favorisierten Künstlern laden (Standard: aus – ein Künstler kann sehr groß
     * sein). Favorisierte Alben lädt die Automatik immer ganz.
     */
    val autoDownloadFavoriteArtists: Flow<Boolean> =
        context.downloadSettingsDataStore.data.map { it[KEY_AUTO_FAVORITE_ARTISTS] ?: false }

    suspend fun setAutoDownloadFavoriteArtists(enabled: Boolean) {
        context.downloadSettingsDataStore.edit { it[KEY_AUTO_FAVORITE_ARTISTS] = enabled }
    }

    /** Server (`sourceId`), von denen alles automatisch heruntergeladen wird (Standard: keiner). */
    val autoDownloadServers: Flow<Set<String>> =
        context.downloadSettingsDataStore.data.map { it[KEY_AUTO_SERVERS].orEmpty() }

    suspend fun setAutoDownloadServer(sourceId: String, enabled: Boolean) {
        context.downloadSettingsDataStore.edit {
            val current = it[KEY_AUTO_SERVERS].orEmpty()
            it[KEY_AUTO_SERVERS] = if (enabled) current + sourceId else current - sourceId
        }
    }

    /** So viele zuletzt hinzugefügte Alben automatisch herunterladen (Standard: 0 = aus). */
    val autoDownloadNewestAlbums: Flow<Int> =
        context.downloadSettingsDataStore.data.map { it[KEY_AUTO_NEWEST_ALBUMS] ?: 0 }

    suspend fun setAutoDownloadNewestAlbums(count: Int) {
        context.downloadSettingsDataStore.edit { it[KEY_AUTO_NEWEST_ALBUMS] = count }
    }

    /** So viele meistgehörte Alben automatisch herunterladen (Standard: 0 = aus). */
    val autoDownloadMostPlayedAlbums: Flow<Int> =
        context.downloadSettingsDataStore.data.map { it[KEY_AUTO_MOST_PLAYED_ALBUMS] ?: 0 }

    suspend fun setAutoDownloadMostPlayedAlbums(count: Int) {
        context.downloadSettingsDataStore.edit { it[KEY_AUTO_MOST_PLAYED_ALBUMS] = count }
    }

    companion object {
        /** Feste Wahlmöglichkeiten für [autoDownloadNewestAlbums] und [autoDownloadMostPlayedAlbums]; 0 = aus. */
        val AUTO_ALBUM_CHOICES = listOf(0, 10, 25, 50)

        /** Eigene Anzahl per Schieberegler: in diesen Schritten bis [AUTO_ALBUM_MAX]. */
        const val AUTO_ALBUM_STEP = 5
        const val AUTO_ALBUM_MAX = 100

        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val KEY_AUTO_FAVORITES = booleanPreferencesKey("auto_download_favorites")
        private val KEY_AUTO_FAVORITE_ARTISTS = booleanPreferencesKey("auto_download_favorite_artists")
        private val KEY_AUTO_SERVERS = stringSetPreferencesKey("auto_download_servers")
        private val KEY_AUTO_NEWEST_ALBUMS = intPreferencesKey("auto_download_newest_albums")
        private val KEY_AUTO_MOST_PLAYED_ALBUMS = intPreferencesKey("auto_download_most_played_albums")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DownloadSettingsBackupModule {
    /** Gehört in die Komplett-Sicherung (`FullBackup`). */
    @Provides
    @IntoSet
    fun provideBackedUpSettings(@ApplicationContext context: Context) = BackedUpSettings("download_settings", context.downloadSettingsDataStore)
}
