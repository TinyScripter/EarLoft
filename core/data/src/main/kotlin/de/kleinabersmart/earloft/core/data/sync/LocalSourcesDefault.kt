package de.kleinabersmart.earloft.core.data.sync

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import de.kleinabersmart.earloft.core.datasource.local.AudioPermission

/**
 * Musik, Bücher und Podcasts auf dem Gerät sind standardmäßig aus; die Berechtigung kommt erst beim Einschalten. Früher
 * galt „an“: Installationen, die einen Schalter nie angefasst haben, behalten ihn, wenn die App die Musik schon lesen
 * darf, sonst gilt „aus“. Der Wert wird dabei gespeichert, die Migration läuft also nur einmal je Schlüssel.
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
