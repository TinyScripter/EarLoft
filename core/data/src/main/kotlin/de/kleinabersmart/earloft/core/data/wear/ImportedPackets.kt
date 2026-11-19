package de.kleinabersmart.earloft.core.data.wear

import android.content.Context

/**
 * Remembers the identifiers of the most recently imported packets from the watch: if the phone crashes between import and
 * deleting the DataItem, a packet arrives again and must not be counted twice.
 */
internal class ImportedPackets(context: Context, preferencesName: String) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private fun seen(): List<String> = preferences.getString(KEY_PACKETS, null)?.split(SEPARATOR).orEmpty().filter { it.isNotEmpty() }

    operator fun contains(packetId: String): Boolean = packetId in seen()

    /** Only call after the import: a crash in between would rather let the packet arrive twice than not at all. */
    fun remember(packetId: String) {
        preferences.edit().putString(KEY_PACKETS, (seen() + packetId).takeLast(REMEMBERED_PACKETS).joinToString(SEPARATOR)).apply()
    }

    private companion object {
        const val KEY_PACKETS = "imported_packets"
        const val SEPARATOR = ","
        const val REMEMBERED_PACKETS = 100
    }
}
