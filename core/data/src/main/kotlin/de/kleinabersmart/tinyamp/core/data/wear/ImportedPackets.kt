package de.kleinabersmart.tinyamp.core.data.wear

import android.content.Context

/**
 * Merkt sich die Kennungen der zuletzt importierten Pakete der Uhr: Stürzt das Handy zwischen Import und Löschen des
 * DataItems ab, kommt ein Paket erneut an und darf nicht doppelt zählen.
 */
internal class ImportedPackets(context: Context, preferencesName: String) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private fun seen(): List<String> = preferences.getString(KEY_PACKETS, null)?.split(SEPARATOR).orEmpty().filter { it.isNotEmpty() }

    operator fun contains(packetId: String): Boolean = packetId in seen()

    /** Erst nach dem Import aufrufen: Ein Absturz dazwischen lässt das Paket lieber doppelt als gar nicht ankommen. */
    fun remember(packetId: String) {
        preferences.edit().putString(KEY_PACKETS, (seen() + packetId).takeLast(REMEMBERED_PACKETS).joinToString(SEPARATOR)).apply()
    }

    private companion object {
        const val KEY_PACKETS = "imported_packets"
        const val SEPARATOR = ","
        const val REMEMBERED_PACKETS = 100
    }
}
