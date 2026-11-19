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
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.podcast.episodeQueueDataStore
import de.kleinabersmart.earloft.core.data.podcast.podcastSettingsDataStore
import de.kleinabersmart.earloft.core.data.repository.artworkSettingsDataStore
import de.kleinabersmart.earloft.core.data.repository.lyricsSettingsDataStore
import de.kleinabersmart.earloft.core.data.spoken.KEY_LOCAL_BOOKS
import de.kleinabersmart.earloft.core.data.spoken.KEY_LOCAL_PODCASTS
import de.kleinabersmart.earloft.core.data.spoken.SPOKEN_SETTINGS_MERGES
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
 * A DataStore with settings that belongs in the full backup ([SettingsBackup]); each module contributes its own via
 * `@ElementsIntoSet`. Not included: secrets (`secrets`), flags of this device (`local_scan`, `offline_images`,
 * `setup`) and transient state (`widget_state`, `playback_state`).
 */
class BackedUpSettings(
    /** Name in the backup file; that of the DataStore. */
    val name: String,
    val store: DataStore<Preferences>,
    /** Switches for on-device sources: only restored if the app may read audio files. */
    val needsAudioPermission: Set<String> = emptySet(),
    /**
     * Keys holding user data rather than settings (subscriptions, queue, server selection): they are merged with the local
     * value instead of replaced; if they are missing from the backup, they stay.
     */
    val merged: Map<String, SettingsMerge> = emptyMap(),
)

/** How a key holding user data is merged on restore ([BackedUpSettings.merged]). */
fun interface SettingsMerge {
    /**
     * The new value from the local [current] (`null`: none) and the backed-up [saved] (same type); [ids] rewrites IDs
     * of servers and subscriptions from the backup to the local ones ([UserDataBackup.import]).
     */
    fun merge(current: Any?, saved: Any, ids: Map<String, String>): Any

    companion object {
        /** Set: the local and the backed-up entries. */
        val UNION = SettingsMerge { current, saved, _ -> current.asStrings() + saved.asStrings() }

        /** Set of server IDs: the local and the backed-up ones, rewritten to the local IDs. */
        val UNION_IDS = SettingsMerge { current, saved, ids -> current.asStrings() + saved.asStrings().map { ids[it] ?: it } }

        /** Flag of this device: the local one stays; if there is none, the backed-up one applies. */
        val KEEP = SettingsMerge { current, saved, _ -> current ?: saved }

        @Suppress("UNCHECKED_CAST")
        private fun Any?.asStrings(): Set<String> = (this as? Set<String>).orEmpty()
    }
}

/** Result of [SettingsBackup.write]. */
data class SettingsRestore(
    /** Restored DataStores. */
    val stores: Int,
    /** The backup had on-device sources enabled, but the permission is missing here; they stayed off. */
    val localSourcesSkipped: Boolean,
)

/** The settings of the full backup as JSON: one object per DataStore, type and value per key. */
@Singleton
class SettingsBackup @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: Set<@JvmSuppressWildcards BackedUpSettings>,
) {
    suspend fun read(): JSONObject = JSONObject().apply {
        settings.sortedBy { it.name }.forEach { put(it.name, it.store.data.first().toJson()) }
    }

    /**
     * Replaces the contents of every DataStore contained in [json]; the others stay as they are. User data in them is
     * merged ([BackedUpSettings.merged]); [ids]: IDs from the backup that are named differently here.
     */
    suspend fun write(json: JSONObject, ids: Map<String, String> = emptyMap()): SettingsRestore {
        val permitted = AudioPermission.isGranted(context)
        var stores = 0
        var localSkipped = false
        settings.forEach { target ->
            val saved = json.optJSONObject(target.name) ?: return@forEach
            // Without permission, the switches of the local sources stay as they are here (concept 6.2).
            val keep = if (permitted) emptySet() else target.needsAudioPermission
            target.store.edit { preferences ->
                val current = preferences.asMap().mapKeys { it.key.name }
                preferences.asMap().keys.filter { it.name !in keep && it.name !in target.merged }.forEach { preferences.remove(it) }
                saved.keys().forEach { name ->
                    val entry = saved.optJSONObject(name) ?: return@forEach
                    if (name in keep) {
                        if (entry.optBoolean(VALUE)) localSkipped = true
                        return@forEach
                    }
                    val value = valueOf(name, entry) ?: return@forEach
                    val merge = target.merged[name]
                    // A local value of a different type (older version) is replaced.
                    val here = current[name]?.takeIf { it::class == value::class || (it is Set<*> && value is Set<*>) }
                    preferences.set(name, merge?.merge(here, value, ids) ?: value)
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
                        Log.w(TAG, "Setting ${key.name} with unknown type left out")
                        return@forEach
                    }
                }
                val json = if (value is Set<*>) JSONArray(value.map { it.toString() }) else value
                put(key.name, JSONObject().put(TYPE, type).put(VALUE, json))
            }
        }

        /** The backed-up value; `null` for an unknown type (from a newer version) or a broken value. */
        fun valueOf(name: String, entry: JSONObject): Any? = try {
            when (entry.getString(TYPE)) {
                "boolean" -> entry.getBoolean(VALUE)
                "int" -> entry.getInt(VALUE)
                "long" -> entry.getLong(VALUE)
                "float" -> entry.getDouble(VALUE).toFloat()
                "double" -> entry.getDouble(VALUE)
                "string" -> entry.getString(VALUE)
                "stringSet" -> entry.getJSONArray(VALUE).let { array -> (0 until array.length()).map(array::getString).toSet() }
                else -> null.also { Log.w(TAG, "Setting $name with unknown type skipped") }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Setting $name skipped", e)
            null
        }

        @Suppress("UNCHECKED_CAST")
        fun MutablePreferences.set(name: String, value: Any) {
            when (value) {
                is Boolean -> this[booleanPreferencesKey(name)] = value
                is Int -> this[intPreferencesKey(name)] = value
                is Long -> this[longPreferencesKey(name)] = value
                is Float -> this[floatPreferencesKey(name)] = value
                is Double -> this[doublePreferencesKey(name)] = value
                is String -> this[stringPreferencesKey(name)] = value
                is Set<*> -> this[stringSetPreferencesKey(name)] = value as Set<String>
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
        BackedUpSettings(
            "spoken_settings", context.spokenSettingsDataStore, setOf(KEY_LOCAL_BOOKS.name, KEY_LOCAL_PODCASTS.name), SPOKEN_SETTINGS_MERGES,
        ),
        BackedUpSettings("podcast_settings", context.podcastSettingsDataStore),
        BackedUpSettings("episode_queue", context.episodeQueueDataStore, merged = EpisodeQueue.BACKUP_MERGES),
        BackedUpSettings("sync_settings", context.syncSettingsDataStore),
        BackedUpSettings("artwork_settings", context.artworkSettingsDataStore),
        BackedUpSettings("lyrics_settings", context.lyricsSettingsDataStore),
    )
}
