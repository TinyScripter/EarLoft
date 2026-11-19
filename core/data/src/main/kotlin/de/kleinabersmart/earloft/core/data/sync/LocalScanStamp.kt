package de.kleinabersmart.earloft.core.data.sync

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.localScanDataStore: DataStore<Preferences> by preferencesDataStore(name = "local_scan")

/**
 * State of the MediaStore at the last successful scan of local music: version and generation per volume (from API 30).
 * If it's unchanged at process start, the scan is skipped; changes afterwards are reported by `MediaStoreChanges`.
 * Excluded from backup like the database it belongs to.
 */
@Singleton
class LocalScanStamp @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** The current state; `null` below API 30 (no generation) or if the MediaStore doesn't provide it. */
    fun current(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            MediaStore.getExternalVolumeNames(context).sorted().joinToString(",") { volume ->
                "$volume:${MediaStore.getVersion(context, volume)}:${MediaStore.getGeneration(context, volume)}"
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaStore state not readable", e)
            null
        }
    }

    /** `true` = changed since the last scan, `false` = unchanged, `null` = unknown (then better scan). */
    suspend fun changedSinceLastScan(): Boolean? = scanNeeded(stored = stored(), current = current())

    /** After a successful scan; [stamp] is the state from *before* the scan, so nothing in between gets lost. */
    suspend fun save(stamp: String?) {
        context.localScanDataStore.edit { if (stamp == null) it.remove(KEY_STAMP) else it[KEY_STAMP] = stamp }
    }

    /** Local music has been removed from the library: scan in any case the next time it's turned on. */
    suspend fun clear() {
        if (stored() != null) save(null)
    }

    private suspend fun stored(): String? = context.localScanDataStore.data.first()[KEY_STAMP]

    companion object {
        private const val TAG = "LocalScanStamp"
        private val KEY_STAMP = stringPreferencesKey("mediastore_stamp")

        /** Without a current state unknown (`null`); without a remembered state (never scanned, turned off) changed. */
        internal fun scanNeeded(stored: String?, current: String?): Boolean? = when {
            current == null -> null
            stored == null -> true
            else -> stored != current
        }
    }
}
