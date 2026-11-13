package de.kleinabersmart.earloft.core.data.backup

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import de.kleinabersmart.earloft.core.data.podcast.episodeQueueDataStore
import de.kleinabersmart.earloft.core.data.podcast.podcastSettingsDataStore
import de.kleinabersmart.earloft.core.data.repository.artworkSettingsDataStore
import de.kleinabersmart.earloft.core.data.repository.lyricsSettingsDataStore
import de.kleinabersmart.earloft.core.data.spoken.KEY_LOCAL_BOOKS
import de.kleinabersmart.earloft.core.data.spoken.KEY_LOCAL_PODCASTS
import de.kleinabersmart.earloft.core.data.spoken.spokenSettingsDataStore
import de.kleinabersmart.earloft.core.data.sync.KEY_LOCAL_MEDIA
import de.kleinabersmart.earloft.core.data.sync.librarySettingsDataStore
import de.kleinabersmart.earloft.core.data.sync.syncSettingsDataStore
import de.kleinabersmart.earloft.core.datasource.local.AudioPermission
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ein DataStore mit Einstellungen, der in die Komplett-Sicherung gehört ([SettingsBackup]); jedes Modul trägt seine per
 * `@ElementsIntoSet` bei. Nicht dazu gehören Geheimnisse (`secrets`), Merker dieses Geräts (`local_scan`, `offline_images`,
 * `setup`) und flüchtige Stände (`widget_state`, `playback_state`).
 */
class BackedUpSettings(
    /** Name in der Sicherungsdatei; der des DataStores. */
    val name: String,
    val store: DataStore<Preferences>,
    /** Schalter für Quellen auf dem Gerät: nur übernommen, wenn die App Audiodateien lesen darf. */
    val needsAudioPermission: Set<String> = emptySet(),
)

/** Ausgang von [SettingsBackup.write]. */
data class SettingsRestore(
    /** Übernommene DataStores. */
    val stores: Int,
    /** Die Sicherung hatte Quellen auf dem Gerät eingeschaltet, hier fehlt aber die Berechtigung; sie blieben aus. */
    val localSourcesSkipped: Boolean,
)

/** Die Einstellungen der Komplett-Sicherung als JSON: je DataStore ein Objekt, je Schlüssel Typ und Wert. */
@Singleton
class SettingsBackup @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: Set<@JvmSuppressWildcards BackedUpSettings>,
) {
    suspend fun read(): JSONObject = JSONObject().apply {
        settings.sortedBy { it.name }.forEach { put(it.name, it.store.data.first().toJson()) }
    }

    /** Ersetzt den Inhalt jedes DataStores, den [json] enthält; die übrigen bleiben, wie sie sind. */
    suspend fun write(json: JSONObject): SettingsRestore {
        val permitted = AudioPermission.isGranted(context)
        var stores = 0
        var localSkipped = false
        settings.forEach { target ->
            val saved = json.optJSONObject(target.name) ?: return@forEach
            // Ohne Berechtigung bleiben die Schalter der lokalen Quellen, wie sie hier sind (Konzept 6.2).
            val keep = if (permitted) emptySet() else target.needsAudioPermission
            target.store.edit { preferences ->
                preferences.asMap().keys.filter { it.name !in keep }.forEach { preferences.remove(it) }
                saved.keys().forEach { name ->
                    val entry = saved.optJSONObject(name) ?: return@forEach
                    if (name in keep) {
                        if (entry.optBoolean(VALUE)) localSkipped = true
                    } else {
                        preferences.put(name, entry)
                    }
                }
            }
            stores++
        }
        return SettingsRestore(stores, localSkipped)
    }

    private companion object {
        const val TAG = "SettingsBackup"
        const val TYPE = "type"
        const val VALUE = "value"

        fun Preferences.toJson() = JSONObject().apply {
            asMap().forEach { (key, value) ->
                val type = when (value) {
                    is Boolean -> "boolean"
                    is Int -> "int"
                    is Long -> "long"
                    is Float -> "float"
                    is Double -> "double"
                    is String -> "string"
                    is Set<*> -> "stringSet"
                    else -> {
                        Log.w(TAG, "Einstellung ${key.name} mit unbekanntem Typ ausgelassen")
                        return@forEach
                    }
                }
                val json = if (value is Set<*>) JSONArray(value.map { it.toString() }) else value
                put(key.name, JSONObject().put(TYPE, type).put(VALUE, json))
            }
        }

        /** Unbekannte Typen (aus einer neueren Version) und kaputte Werte werden übergangen. */
        fun MutablePreferences.put(name: String, entry: JSONObject) {
            try {
                when (entry.getString(TYPE)) {
                    "boolean" -> this[booleanPreferencesKey(name)] = entry.getBoolean(VALUE)
                    "int" -> this[intPreferencesKey(name)] = entry.getInt(VALUE)
                    "long" -> this[longPreferencesKey(name)] = entry.getLong(VALUE)
                    "float" -> this[floatPreferencesKey(name)] = entry.getDouble(VALUE).toFloat()
                    "double" -> this[doublePreferencesKey(name)] = entry.getDouble(VALUE)
                    "string" -> this[stringPreferencesKey(name)] = entry.getString(VALUE)
                    "stringSet" -> this[stringSetPreferencesKey(name)] =
                        entry.getJSONArray(VALUE).let { array -> (0 until array.length()).map(array::getString).toSet() }
                    else -> Log.w(TAG, "Einstellung $name mit unbekanntem Typ übergangen")
                }
            } catch (e: JSONException) {
                Log.w(TAG, "Einstellung $name übergangen", e)
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object SettingsBackupModule {
    @Provides
    @ElementsIntoSet
    fun provideDataSettings(@ApplicationContext context: Context): Set<BackedUpSettings> = setOf(
        BackedUpSettings("library_settings", context.librarySettingsDataStore, setOf(KEY_LOCAL_MEDIA.name)),
        BackedUpSettings("spoken_settings", context.spokenSettingsDataStore, setOf(KEY_LOCAL_BOOKS.name, KEY_LOCAL_PODCASTS.name)),
        BackedUpSettings("podcast_settings", context.podcastSettingsDataStore),
        BackedUpSettings("episode_queue", context.episodeQueueDataStore),
        BackedUpSettings("sync_settings", context.syncSettingsDataStore),
        BackedUpSettings("artwork_settings", context.artworkSettingsDataStore),
        BackedUpSettings("lyrics_settings", context.lyricsSettingsDataStore),
    )
}
