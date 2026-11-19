package de.kleinabersmart.earloft.core.data.sync

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import de.kleinabersmart.earloft.core.datasource.local.AudioPermission

/**
 * Music, books and podcasts on the device are off by default; the permission is only requested when turning them on. It used
 * to be "on": installations that never touched a switch keep it on if the app is already allowed to read music,
 * otherwise "off" applies. The value is stored in the process, so the migration runs only once per key.
 */
internal class LocalSourcesDefault(
    private val context: Context,
    private val keys: List<Preferences.Key<Boolean>>,
) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = keys.any { it !in currentData }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val granted = AudioPermission.isGranted(context)
        return currentData.toMutablePreferences().apply {
            keys.forEach { key -> if (key !in this) this[key] = granted }
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
