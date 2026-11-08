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
 * Stand des MediaStore beim letzten erfolgreichen Scan der lokalen Musik: Version und Generation je Laufwerk (ab API 30).
 * Ist er beim Start des Prozesses unverändert, entfällt der Scan; Änderungen danach meldet `MediaStoreChanges`.
 * Vom Backup ausgeschlossen wie die Datenbank, zu der er gehört.
 */
@Singleton
class LocalScanStamp @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Der jetzige Stand; `null` unter API 30 (keine Generation) oder wenn der MediaStore ihn nicht liefert. */
    fun current(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            MediaStore.getExternalVolumeNames(context).sorted().joinToString(",") { volume ->
                "$volume:${MediaStore.getVersion(context, volume)}:${MediaStore.getGeneration(context, volume)}"
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Stand des MediaStore nicht lesbar", e)
            null
        }
    }

    /** `true` = geändert seit dem letzten Scan, `false` = unverändert, `null` = unbekannt (dann lieber scannen). */
    suspend fun changedSinceLastScan(): Boolean? = scanNeeded(stored = stored(), current = current())

    /** Nach einem erfolgreichen Scan; [stamp] ist der Stand von *vor* dem Scan, damit nichts dazwischen verloren geht. */
    suspend fun save(stamp: String?) {
        context.localScanDataStore.edit { if (stamp == null) it.remove(KEY_STAMP) else it[KEY_STAMP] = stamp }
    }

    /** Die lokale Musik ist aus der Bibliothek entfernt: beim nächsten Einschalten auf jeden Fall scannen. */
    suspend fun clear() {
        if (stored() != null) save(null)
    }

    private suspend fun stored(): String? = context.localScanDataStore.data.first()[KEY_STAMP]

    companion object {
        private const val TAG = "LocalScanStamp"
        private val KEY_STAMP = stringPreferencesKey("mediastore_stamp")

        /** Ohne jetzigen Stand unbekannt (`null`); ohne gemerkten Stand (noch nie gescannt, ausgeschaltet) geändert. */
        internal fun scanNeeded(stored: String?, current: String?): Boolean? = when {
            current == null -> null
            stored == null -> true
            else -> stored != current
        }
    }
}
