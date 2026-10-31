package keinabersmart.kleinaberlaut.core.data.repository

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

private val Context.artworkSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "artwork_settings")

/**
 * Bilder aus dem Internet. Beide Optionen sind standardmäßig aus, weil dafür Namen von Interpreten und
 * Alben an Dritte (Deezer, MusicBrainz) gehen.
 */
@Singleton
class ArtworkSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Interpreten-Bilder von Deezer nachladen. */
    val artistImages: Flow<Boolean> = context.artworkSettingsDataStore.data.map { it[KEY_ARTIST_IMAGES] ?: false }

    /** Fehlende Album-Cover über MusicBrainz und das Cover Art Archive nachladen. */
    val albumCovers: Flow<Boolean> = context.artworkSettingsDataStore.data.map { it[KEY_ALBUM_COVERS] ?: false }

    /** Fehlende Autorenbilder (Bücher) von Audnexus nachladen; der Name des Autors geht dabei an Dritte. */
    val authorImages: Flow<Boolean> = context.artworkSettingsDataStore.data.map { it[KEY_AUTHOR_IMAGES] ?: false }

    suspend fun setAuthorImages(enabled: Boolean) {
        context.artworkSettingsDataStore.edit { it[KEY_AUTHOR_IMAGES] = enabled }
    }

    suspend fun setArtistImages(enabled: Boolean) {
        context.artworkSettingsDataStore.edit { it[KEY_ARTIST_IMAGES] = enabled }
    }

    suspend fun setAlbumCovers(enabled: Boolean) {
        context.artworkSettingsDataStore.edit { it[KEY_ALBUM_COVERS] = enabled }
    }

    private companion object {
        val KEY_ARTIST_IMAGES = booleanPreferencesKey("artist_images")
        val KEY_ALBUM_COVERS = booleanPreferencesKey("album_covers")
        val KEY_AUTHOR_IMAGES = booleanPreferencesKey("author_images")
    }
}
